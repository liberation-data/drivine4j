package org.drivine.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

@OptIn(ExperimentalKotlinPoetApi::class)

/**
 * Generates query DSL classes and extension functions for a @GraphView annotated class.
 *
 * For each @GraphView, generates:
 * 1. Properties classes for each fragment type
 * 2. QueryDsl class that aggregates all property references
 * 3. Extension function on GraphObjectManager for clean API
 */
class QueryDslGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val graphViewClasses: List<KSClassDeclaration>
) {

    fun generateAll() {
        logger.info("Generating QueryDsl for ${graphViewClasses.size} @GraphView classes")

        // Collect all fragment types across all views
        val allFragmentTypes = mutableMapOf<String, FragmentType>()
        val allViewStructures = mutableMapOf<KSClassDeclaration, List<ViewProperty>>()

        graphViewClasses.forEach { graphViewClass ->
            val viewStructure = analyzeGraphViewStructure(graphViewClass)
            allViewStructures[graphViewClass] = viewStructure

            val fragmentTypes = collectFragmentTypes(viewStructure)
            fragmentTypes.forEach { fragmentType ->
                val key = fragmentType.fragmentClass.qualifiedName?.asString() ?: return@forEach
                if (!allFragmentTypes.containsKey(key)) {
                    allFragmentTypes[key] = fragmentType
                } else {
                    // Merge: if used anywhere with alias, must support alias
                    // if used anywhere without alias (as root), must support that too
                    val existing = allFragmentTypes[key]!!
                    val needsBoth = (fragmentType.needsAliasConstructor && !existing.needsAliasConstructor) ||
                                   (!fragmentType.needsAliasConstructor && existing.needsAliasConstructor)
                    allFragmentTypes[key] = FragmentType(
                        fragmentType.fragmentClass,
                        needsAliasConstructor = existing.needsAliasConstructor || fragmentType.needsAliasConstructor,
                        hasDefaultAlias = !existing.needsAliasConstructor || !fragmentType.needsAliasConstructor
                    )
                }
            }
        }

        // Generate shared properties file
        generateSharedPropertiesFile(allFragmentTypes.values.toList())

        // Generate individual DSL files for each view
        graphViewClasses.forEach { graphViewClass ->
            val viewStructure = allViewStructures[graphViewClass] ?: return@forEach
            generateViewDslFile(graphViewClass, viewStructure)
        }
    }

    private fun generateSharedPropertiesFile(fragmentTypes: List<FragmentType>) {
        // Group fragment types by their source package
        val fragmentsByPackage = fragmentTypes.groupBy { it.fragmentClass.packageName.asString() }

        // Group nested view Properties by their source package
        val nestedViewPropertiesByPackage = collectNestedViewPropertiesClasses()

        // Group RelationshipFragment Properties by their source package
        val relationshipFragmentPropertiesByPackage = collectRelationshipFragmentPropertiesClasses()

        // Collect all unique packages that need GeneratedProperties files
        val allPackages = (fragmentsByPackage.keys + nestedViewPropertiesByPackage.keys +
                         relationshipFragmentPropertiesByPackage.keys).toSet()

        if (allPackages.isEmpty()) return

        allPackages.forEach { packageName ->
            val fragmentTypesForPackage = fragmentsByPackage[packageName] ?: emptyList()
            val propertiesClasses = generatePropertiesClasses(fragmentTypesForPackage)
            val nestedViewProperties = nestedViewPropertiesByPackage[packageName] ?: emptyList()
            val relationshipFragmentProperties = relationshipFragmentPropertiesByPackage[packageName] ?: emptyList()

            // Skip empty packages
            if (propertiesClasses.isEmpty() && nestedViewProperties.isEmpty() && relationshipFragmentProperties.isEmpty()) {
                return@forEach
            }

            val fileSpec = FileSpec.builder(packageName, "GeneratedProperties")
                .addFileComment("Generated code - do not modify")
                .apply {
                    propertiesClasses.forEach { addType(it) }
                    nestedViewProperties.forEach { addType(it) }
                    relationshipFragmentProperties.forEach { addType(it) }
                }
                .build()

            fileSpec.writeTo(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(
                    aggregating = true,
                    sources = graphViewClasses.mapNotNull { it.containingFile }.toTypedArray()
                )
            )

            logger.info("Generated shared properties at $packageName.GeneratedProperties")
        }
    }

    /**
     * Collects nested view Properties classes grouped by their source package.
     * Returns a map of package name to list of TypeSpec for Properties classes.
     */
    private fun collectNestedViewPropertiesClasses(): Map<String, List<TypeSpec>> {
        val result = mutableMapOf<String, MutableList<TypeSpec>>()
        val processed = mutableSetOf<String>()

        fun processViewClass(viewClass: KSClassDeclaration) {
            val viewStructure = analyzeGraphViewStructure(viewClass)
            viewStructure.forEach { viewProp ->
                if (viewProp.isNestedView && viewProp.nestedViewClass != null) {
                    val key = viewProp.nestedViewClass.qualifiedName?.asString() ?: return@forEach
                    if (!processed.contains(key)) {
                        processed.add(key)
                        val packageName = viewProp.nestedViewClass.packageName.asString()
                        result.getOrPut(packageName) { mutableListOf() }
                            .add(generateViewPropertiesClass(viewProp.nestedViewClass))
                        // Recurse into sub-views (processed set prevents infinite loops)
                        processViewClass(viewProp.nestedViewClass)
                    }
                }
            }
        }

        // Find all nested views across all graph views
        graphViewClasses.forEach { graphViewClass -> processViewClass(graphViewClass) }

        return result
    }

    /**
     * Collects RelationshipFragment Properties classes grouped by their source package.
     * Returns a map of package name to list of TypeSpec for Properties classes.
     */
    private fun collectRelationshipFragmentPropertiesClasses(): Map<String, List<TypeSpec>> {
        val result = mutableMapOf<String, MutableList<TypeSpec>>()
        val processed = mutableSetOf<String>()

        // Find all RelationshipFragments across all graph views
        graphViewClasses.forEach { graphViewClass ->
            val viewStructure = analyzeGraphViewStructure(graphViewClass)
            viewStructure.forEach { viewProp ->
                val propType = viewProp.type
                if (isRelationshipFragment(propType)) {
                    val relFragmentClass = propType.declaration as? KSClassDeclaration ?: return@forEach
                    val key = relFragmentClass.qualifiedName?.asString() ?: return@forEach
                    if (!processed.contains(key)) {
                        processed.add(key)
                        val packageName = relFragmentClass.packageName.asString()
                        result.getOrPut(packageName) { mutableListOf() }
                            .add(generateRelationshipFragmentPropertiesClass(relFragmentClass))
                    }
                }
            }
        }

        return result
    }

    private fun generateRelationshipFragmentPropertiesClass(relFragmentClass: KSClassDeclaration): TypeSpec {
        val relFragmentName = relFragmentClass.simpleName.asString()
        val propertiesClassName = "${relFragmentName}Properties"

        // Implement NodeReference for instanceOf() support
        val nodeReferenceClass = ClassName("org.drivine.query.dsl", "NodeReference")
        val classBuilder = TypeSpec.classBuilder(propertiesClassName)
            .addSuperinterface(nodeReferenceClass)

        // Add constructor with alias parameter
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("alias", String::class)
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("alias", String::class)
                .initializer("alias")
                .addModifiers(KModifier.PRIVATE)
                .build()
        )
        // Implement NodeReference.nodeAlias
        classBuilder.addProperty(
            PropertySpec.builder("nodeAlias", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return alias").build())
                .build()
        )

        // Generate property references for each property in the RelationshipFragment
        // Skip the 'target' property since that's handled separately as a relationship
        relFragmentClass.getAllProperties().forEach { prop ->
            val propName = prop.simpleName.asString()
            if (propName == "target") {
                // Skip - target is the node, we expose it separately
                return@forEach
            }

            val propType = prop.type.resolve()

            val propertyRefType = when {
                propType.declaration.qualifiedName?.asString() == "kotlin.String" ->
                    ClassName("org.drivine.query.dsl", "StringPropertyReference")
                else ->
                    ClassName("org.drivine.query.dsl", "PropertyReference")
                        .parameterizedBy(propType.toTypeName().copy(nullable = false))
            }

            classBuilder.addProperty(
                PropertySpec.builder(propName, propertyRefType)
                    .initializer(
                        if (propType.declaration.qualifiedName?.asString() == "kotlin.String") {
                            "$propertyRefType(alias, \"$propName\")"
                        } else {
                            "$propertyRefType(alias, \"$propName\")"
                        }
                    )
                    .build()
            )
        }

        return classBuilder.build()
    }

    private fun generateViewPropertiesClass(viewClass: KSClassDeclaration): TypeSpec {
        val viewName = viewClass.simpleName.asString()
        val propertiesClassName = "${viewName}Properties"
        val viewStructure = analyzeGraphViewStructure(viewClass)

        // Implement NodeReference for instanceOf() support
        val nodeReferenceClass = ClassName("org.drivine.query.dsl", "NodeReference")
        val classBuilder = TypeSpec.classBuilder(propertiesClassName)
            .addSuperinterface(nodeReferenceClass)

        // Add constructor with alias parameter
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("alias", String::class)
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("alias", String::class)
                .initializer("alias")
                .addModifiers(KModifier.PRIVATE)
                .build()
        )
        // Implement NodeReference.nodeAlias
        classBuilder.addProperty(
            PropertySpec.builder("nodeAlias", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return alias").build())
                .build()
        )

        // Add properties for each fragment/relationship in the view
        viewStructure.forEach { viewProp ->
            val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
            val fragmentSimpleName = fragmentClass.simpleName.asString()
            val propPropertiesClassName = "${fragmentSimpleName}Properties"
            // Properties class is generated in the fragment's package
            val fragmentPackage = fragmentClass.packageName.asString()

            if (viewProp.isRootFragment) {
                // Root fragment uses the same alias as the view
                val propClassName = ClassName(fragmentPackage, propPropertiesClassName)
                classBuilder.addProperty(
                    PropertySpec.builder(viewProp.name, propClassName)
                        .initializer("%T(alias)", propClassName)
                        .build()
                )
            } else if (viewProp.isRelationship) {
                // Relationship uses composite alias
                val relationshipAlias = "\${alias}_${viewProp.name}"
                val propClassName = ClassName(fragmentPackage, propPropertiesClassName)
                classBuilder.addProperty(
                    PropertySpec.builder(viewProp.name, propClassName)
                        .initializer("%T(\"$relationshipAlias\")", propClassName)
                        .build()
                )
            }
        }

        return classBuilder.build()
    }

    private fun generateViewDslFile(graphViewClass: KSClassDeclaration, viewStructure: List<ViewProperty>) {
        val graphViewClassName = graphViewClass.toClassName()
        val graphViewSimpleName = graphViewClass.simpleName.asString()
        val dslClassName = "${graphViewSimpleName}QueryDsl"

        // Generate the main QueryDsl class
        val dslClass = generateQueryDslClass(graphViewClass, viewStructure)

        // Generate extension functions
        val loadAllExtension = generateLoadAllExtensionFunction(graphViewClass)
        val deleteAllExtension = generateDeleteAllExtensionFunction(graphViewClass)
        val countExtension = generateCountExtensionFunction(graphViewClass)

        // Generate context property extensions as raw code (KotlinPoet doesn't support named context params)
        val contextPropertyExtensionsCode = generateContextPropertyExtensionsCode(graphViewClass, viewStructure)

        // Build the main file content with KotlinPoet
        val fileSpecBuilder = FileSpec.builder(graphViewClassName.packageName, dslClassName)
            .addFileComment("Generated code - do not modify")
            .addType(dslClass)
            .addFunction(loadAllExtension)
            .addFunction(deleteAllExtension)
            .addFunction(countExtension)

        // Filtered vector-search wrapper — only for views whose root fragment is vector-indexed.
        if (rootFragmentHasVectorIndex(graphViewClass)) {
            fileSpecBuilder.addFunction(generateLoadNearestExtensionFunction(graphViewClass))
        }

        val fileSpec = fileSpecBuilder.build()

        // Write to file manually so we can append raw code for context property extensions
        val outputStream = codeGenerator.createNewFile(
            dependencies = Dependencies(false, graphViewClass.containingFile!!),
            packageName = graphViewClassName.packageName,
            fileName = dslClassName
        )
        outputStream.bufferedWriter().use { writer ->
            // Write the KotlinPoet-generated content
            writer.write(fileSpec.toString())
            // Append the raw context property extensions
            writer.write("\n")
            writer.write(contextPropertyExtensionsCode)
        }

        logger.info("Generated $dslClassName at ${graphViewClassName.packageName}.$dslClassName")
    }

    private data class ViewProperty(
        val name: String,
        val type: KSType,
        val isRootFragment: Boolean,
        val isRelationship: Boolean,
        val isNestedView: Boolean = false,
        val relationshipAlias: String? = null,
        val nestedViewClass: KSClassDeclaration? = null
    )

    private data class FragmentType(
        val fragmentClass: KSClassDeclaration,
        val needsAliasConstructor: Boolean,
        val hasDefaultAlias: Boolean = false,
        val rootFieldName: String? = null  // For root fragments, the field name from the GraphView
    )

    private fun analyzeGraphViewStructure(graphViewClass: KSClassDeclaration): List<ViewProperty> {
        val properties = mutableListOf<ViewProperty>()

        graphViewClass.getAllProperties().forEach { property ->
            val propertyName = property.simpleName.asString()
            val propertyType = property.type.resolve()

            // Check if it's a @GraphRelationship
            val graphRelAnnotation = property.annotations.find {
                it.shortName.asString() == "GraphRelationship"
            }

            // Check if it's a @Root
            val rootAnnotation = property.annotations.find {
                it.shortName.asString() == "Root"
            }

            if (graphRelAnnotation != null) {
                // It's a relationship property
                val targetType = extractRelationshipTargetType(propertyType)
                val isNestedView = isGraphView(targetType)
                val nestedViewClass = if (isNestedView) targetType.declaration as? KSClassDeclaration else null

                properties.add(
                    ViewProperty(
                        name = propertyName,
                        type = targetType,
                        isRootFragment = false,
                        isRelationship = true,
                        isNestedView = isNestedView,
                        relationshipAlias = propertyName,
                        nestedViewClass = nestedViewClass
                    )
                )
            } else if (rootAnnotation != null) {
                // It's the root fragment
                properties.add(
                    ViewProperty(
                        name = propertyName,
                        type = propertyType,
                        isRootFragment = true,
                        isRelationship = false,
                        isNestedView = false
                    )
                )
            }
            // Skip other properties (e.g., delegated properties from interfaces)
        }

        return properties
    }

    private fun isGraphView(type: KSType): Boolean {
        val decl = type.declaration
        return decl.annotations.any { it.shortName.asString() == "GraphView" }
    }

    private fun isGraphFragment(type: KSType): Boolean {
        val decl = type.declaration
        return decl.annotations.any { it.shortName.asString() == "NodeFragment" }
    }

    private fun isRelationshipFragment(type: KSType): Boolean {
        val decl = type.declaration
        return decl.annotations.any { it.shortName.asString() == "RelationshipFragment" }
    }

    private fun collectFragmentTypes(
        viewStructure: List<ViewProperty>,
        visited: MutableSet<String> = mutableSetOf()
    ): List<FragmentType> {
        val fragmentTypes = mutableMapOf<String, FragmentType>()

        viewStructure.forEach { viewProp ->
            if (viewProp.isNestedView) {
                // For nested views, we need to generate a Properties class for the view itself
                // This allows query.raisedBy.person.name instead of query.raisedBy_person.name
                val nestedViewClass = viewProp.type.declaration as? KSClassDeclaration
                if (nestedViewClass != null) {
                    val nestedKey = nestedViewClass.qualifiedName?.asString() ?: return@forEach
                    // Guard against recursive/self-referential @GraphView types
                    if (visited.contains(nestedKey)) return@forEach
                    visited.add(nestedKey)
                    // Collect fragment types from the nested view recursively
                    val nestedStructure = analyzeGraphViewStructure(nestedViewClass)
                    val nestedFragments = collectFragmentTypes(nestedStructure, visited)
                    nestedFragments.forEach { fragmentType ->
                        val key = fragmentType.fragmentClass.simpleName.asString()
                        if (!fragmentTypes.containsKey(key)) {
                            fragmentTypes[key] = fragmentType
                        }
                    }
                }
            } else if (isGraphFragment(viewProp.type)) {
                // Regular NodeFragment
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration
                if (fragmentClass != null) {
                    val fragmentName = fragmentClass.simpleName.asString()
                    if (!fragmentTypes.containsKey(fragmentName)) {
                        fragmentTypes[fragmentName] = FragmentType(
                            fragmentClass,
                            needsAliasConstructor = true,  // Always need alias - root fragments pass their field name
                            rootFieldName = if (viewProp.isRootFragment) viewProp.name else null
                        )
                    }
                }
            } else if (isRelationshipFragment(viewProp.type)) {
                // RelationshipFragment - need to extract its 'target' property's NodeFragment
                val relFragmentClass = viewProp.type.declaration as? KSClassDeclaration
                if (relFragmentClass != null) {
                    // Find the 'target' property in the RelationshipFragment
                    relFragmentClass.getAllProperties().forEach { prop ->
                        val propName = prop.simpleName.asString()
                        if (propName == "target") {
                            val targetType = prop.type.resolve()
                            if (isGraphFragment(targetType)) {
                                val targetFragmentClass = targetType.declaration as? KSClassDeclaration
                                if (targetFragmentClass != null) {
                                    val fragmentName = targetFragmentClass.simpleName.asString()
                                    if (!fragmentTypes.containsKey(fragmentName)) {
                                        fragmentTypes[fragmentName] = FragmentType(
                                            targetFragmentClass,
                                            needsAliasConstructor = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return fragmentTypes.values.toList()
    }

    private fun extractRelationshipTargetType(propertyType: KSType): KSType {
        // Handle List<T> -> T
        val typeArgs = propertyType.arguments
        return if (typeArgs.isNotEmpty()) {
            typeArgs.first().type?.resolve() ?: propertyType
        } else {
            propertyType
        }
    }

    private fun generatePropertiesClasses(fragmentTypes: List<FragmentType>): List<TypeSpec> {
        return fragmentTypes.map { fragmentType ->
            val fragmentClass = fragmentType.fragmentClass
            val fragmentSimpleName = fragmentClass.simpleName.asString()
            val propertiesClassName = "${fragmentSimpleName}Properties"

            generateFragmentPropertiesClass(
                fragmentClass = fragmentClass,
                propertiesClassName = propertiesClassName,
                needsAliasConstructor = fragmentType.needsAliasConstructor,
                hasDefaultAlias = fragmentType.hasDefaultAlias,
                rootFieldName = fragmentType.rootFieldName
            )
        }
    }

    private fun generateFragmentPropertiesClass(
        fragmentClass: KSClassDeclaration,
        propertiesClassName: String,
        needsAliasConstructor: Boolean,
        hasDefaultAlias: Boolean,
        rootFieldName: String? = null
    ): TypeSpec {
        // Implement NodeReference for instanceOf() support
        val classBuilder = TypeSpec.classBuilder(propertiesClassName)
            .addSuperinterface(ClassName("org.drivine.query.dsl", "NodeReference"))

        // For root fragments, the alias is the GraphView field name (e.g. "issue"); otherwise the
        // decapitalized fragment class name (e.g. "issueCore").
        val defaultAlias = rootFieldName ?: fragmentClass.simpleName.asString().replaceFirstChar { it.lowercase() }
        addAliasMembers(classBuilder, needsAliasConstructor, hasDefaultAlias, defaultAlias)

        // Property references read the constructor param when aliased, else the literal default alias.
        val aliasExpr = if (needsAliasConstructor) "alias" else "\"$defaultAlias\""
        fragmentClass.getAllProperties().forEach { prop ->
            addPropertyReference(classBuilder, prop, aliasExpr)
        }

        return classBuilder.build()
    }

    /**
     * Emits the alias plumbing: an `alias` constructor param + private backing property + `nodeAlias`
     * getter when the fragment needs a caller-supplied alias, or a fixed `nodeAlias` initializer
     * otherwise.
     */
    private fun addAliasMembers(
        classBuilder: TypeSpec.Builder,
        needsAliasConstructor: Boolean,
        hasDefaultAlias: Boolean,
        defaultAlias: String,
    ) {
        if (!needsAliasConstructor) {
            classBuilder.addProperty(
                PropertySpec.builder("nodeAlias", String::class)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("\"$defaultAlias\"")
                    .build()
            )
            return
        }

        val paramBuilder = ParameterSpec.builder("alias", String::class)
        if (hasDefaultAlias) {
            paramBuilder.defaultValue("\"$defaultAlias\"")
        }
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder().addParameter(paramBuilder.build()).build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("alias", String::class)
                .initializer("alias")
                .addModifiers(KModifier.PRIVATE)
                .build()
        )
        // Implement NodeReference.nodeAlias
        classBuilder.addProperty(
            PropertySpec.builder("nodeAlias", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return alias").build())
                .build()
        )
    }

    /** Emits one property reference, dispatching to the bag / string / scalar shape. */
    private fun addPropertyReference(classBuilder: TypeSpec.Builder, prop: KSPropertyDeclaration, aliasExpr: String) {
        // @PropertyBag / @CompositeProperty → a PropertyBagReference with key(name), not a scalar.
        val bagAnnotation = prop.annotations.find {
            val name = it.shortName.asString()
            name == "PropertyBag" || name == "CompositeProperty"
        }
        if (bagAnnotation != null) {
            addPropertyBagReference(classBuilder, prop.simpleName.asString(), aliasExpr, bagAnnotation)
            return
        }

        val propName = prop.simpleName.asString()
        val propType = prop.type.resolve()
        val propertyRefType = if (propType.declaration.qualifiedName?.asString() == "kotlin.String") {
            ClassName("org.drivine.query.dsl", "StringPropertyReference")
        } else {
            ClassName("org.drivine.query.dsl", "PropertyReference")
                .parameterizedBy(propType.toTypeName().copy(nullable = false))
        }

        classBuilder.addProperty(
            PropertySpec.builder(propName, propertyRefType)
                .initializer("$propertyRefType($aliasExpr, \"$propName\")")
                .build()
        )
    }

    /** Emits a `PropertyBagReference(alias, storedPrefix)` for a `@PropertyBag` / `@CompositeProperty`. */
    private fun addPropertyBagReference(
        classBuilder: TypeSpec.Builder,
        propName: String,
        aliasExpr: String,
        bagAnnotation: KSAnnotation,
    ) {
        val prefix = (bagAnnotation.arguments.find { it.name?.asString() == "prefix" }?.value as? String) ?: ""
        val delimiter = (bagAnnotation.arguments.find { it.name?.asString() == "delimiter" }?.value as? String) ?: "."
        val storedPrefix = "${prefix.ifEmpty { propName }}$delimiter"
        val bagRefType = ClassName("org.drivine.query.dsl", "PropertyBagReference")
        classBuilder.addProperty(
            PropertySpec.builder(propName, bagRefType)
                .initializer("%T($aliasExpr, \"$storedPrefix\")", bagRefType)
                .build()
        )
    }

    private fun generateQueryDslClass(graphViewClass: KSClassDeclaration, viewStructure: List<ViewProperty>): TypeSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val classBuilder = TypeSpec.classBuilder(dslClassName)

        // Add property for each part of the view
        viewStructure.forEach { viewProp ->
            if (viewProp.isNestedView && viewProp.nestedViewClass != null) {
                // For nested views, use the generated ViewProperties class
                // This allows query.raisedBy.person.name instead of query.raisedBy_person.name
                val nestedViewName = viewProp.nestedViewClass.simpleName.asString()
                val propertiesClassName = "${nestedViewName}Properties"
                // Properties class is generated in the nested view's package
                val propertiesPackage = viewProp.nestedViewClass.packageName.asString()

                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(propertiesPackage, propertiesClassName)
                    )
                        .initializer("%T(\"${viewProp.relationshipAlias}\")", ClassName(propertiesPackage, propertiesClassName))
                        .build()
                )
            } else {
                // Regular fragment or relationship
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
                val fragmentSimpleName = fragmentClass.simpleName.asString()
                val propertiesClassName = "${fragmentSimpleName}Properties"
                // Properties class is generated in the fragment's package
                val propertiesPackage = fragmentClass.packageName.asString()

                // Always pass the field name as alias - for root fragments, this is the view's field name
                // (e.g., "core" for `@Root val core: GuideUser`)
                // For relationships, this is the relationship field name (e.g., "webUser")
                val alias = viewProp.relationshipAlias ?: viewProp.name

                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(propertiesPackage, propertiesClassName)
                    )
                        .initializer("%T(\"$alias\")", ClassName(propertiesPackage, propertiesClassName))
                        .build()
                )
            }
        }

        // Add companion object with singleton instance
        classBuilder.addType(
            TypeSpec.companionObjectBuilder()
                .addProperty(
                    PropertySpec.builder(
                        "INSTANCE",
                        ClassName(graphViewClassName.packageName, dslClassName)
                    )
                        .initializer("$dslClassName()")
                        .build()
                )
                .build()
        )

        return classBuilder.build()
    }

    private fun generateLoadAllExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)

        return FunSpec.builder("loadAll")
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(
                TypeVariableName("T", graphViewClassName).copy(reified = true)
            )
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE)
                .build()
            )
            .returns(List::class.asClassName().parameterizedBy(TypeVariableName("T")))
            .addStatement("return loadAll(T::class.java, $dslClassName.INSTANCE, spec)")
            .build()
    }

    /**
     * Emits a filtered vector-search extension that injects the view's `…QueryDsl.INSTANCE`, mirroring
     * the generated `loadAll(spec)` wrapper:
     *
     * ```kotlin
     * inline fun <reified T : PropositionView> GraphObjectManager.loadNearest(
     *     vector: List<Float>, topK: Int, threshold: Double? = null,
     *     noinline spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit,
     * ): List<Scored<T>> = loadNearest(T::class.java, PropositionViewQueryDsl.INSTANCE, vector, topK, threshold, spec)
     * ```
     *
     * Only the filtered (spec) form is generated — the non-filtered `loadNearest<T>(vector, topK,
     * threshold)` is a hand-written reified extension and needs no query object. Gated on the root
     * fragment carrying a `@VectorIndex` (see [rootFragmentHasVectorIndex]); a view with no embedding
     * can't be searched.
     */
    private fun generateLoadNearestExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val scoredClass = ClassName("org.drivine.manager", "Scored")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)
        val floatList = List::class.asClassName().parameterizedBy(Float::class.asClassName())

        return FunSpec.builder("loadNearest")
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(
                TypeVariableName("T", graphViewClassName).copy(reified = true)
            )
            .addParameter("vector", floatList)
            .addParameter("topK", Int::class.asClassName())
            .addParameter(
                ParameterSpec.builder("threshold", Double::class.asClassName().copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE)
                .build()
            )
            .returns(List::class.asClassName().parameterizedBy(scoredClass.parameterizedBy(TypeVariableName("T"))))
            .addStatement("return loadNearest(T::class.java, $dslClassName.INSTANCE, vector, topK, threshold, spec)")
            .build()
    }

    /**
     * Emits the `count(spec)` wrapper that injects the view's `…QueryDsl.INSTANCE`, mirroring the
     * generated `loadAll(spec)` — `count<View> { where { } }` instead of
     * `count(View::class.java, ViewQueryDsl.INSTANCE) { }`.
     */
    private fun generateCountExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)

        return FunSpec.builder("count")
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(
                TypeVariableName("T", graphViewClassName).copy(reified = true)
            )
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE)
                .build()
            )
            .returns(Long::class.asClassName())
            .addStatement("return count(T::class.java, $dslClassName.INSTANCE, spec)")
            .build()
    }

    /** Whether the view's `@Root` fragment declares a `@VectorIndex` property (so it is searchable). */
    private fun rootFragmentHasVectorIndex(graphViewClass: KSClassDeclaration): Boolean {
        val rootProperty = graphViewClass.getAllProperties().find { prop ->
            prop.annotations.any { it.shortName.asString() == "Root" }
        } ?: return false
        val rootFragment = rootProperty.type.resolve().declaration as? KSClassDeclaration ?: return false
        return rootFragment.getAllProperties().any { p ->
            p.annotations.any { it.shortName.asString() == "VectorIndex" }
        }
    }

    private fun generateDeleteAllExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)

        return FunSpec.builder("deleteAll")
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(
                TypeVariableName("T", graphViewClassName).copy(reified = true)
            )
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE)
                .build()
            )
            .returns(Int::class)
            .addStatement("return deleteAll(T::class.java, $dslClassName.INSTANCE, spec)")
            .build()
    }

    /**
     * Generates context property extension code for cleaner DSL syntax.
     * Returns raw Kotlin code since KotlinPoet's contextReceivers() doesn't support named parameters.
     *
     * For example, instead of:
     * ```kotlin
     * where { query.core.guideProgress gte 0 }
     * ```
     *
     * You can write:
     * ```kotlin
     * where { core.guideProgress gte 0 }
     * ```
     *
     * Generates extensions for both WhereBuilder and OrderBuilder contexts.
     */
    private fun generateContextPropertyExtensionsCode(
        graphViewClass: KSClassDeclaration,
        viewStructure: List<ViewProperty>
    ): String {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"

        val code = StringBuilder()

        viewStructure.forEach { viewProp ->
            // Get the fully qualified Properties class name
            val fullyQualifiedPropertiesClassName = if (viewProp.isNestedView && viewProp.nestedViewClass != null) {
                val nestedViewPackage = viewProp.nestedViewClass.packageName.asString()
                val nestedViewName = viewProp.nestedViewClass.simpleName.asString()
                "$nestedViewPackage.${nestedViewName}Properties"
            } else {
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
                val fragmentPackage = fragmentClass.packageName.asString()
                "$fragmentPackage.${fragmentClass.simpleName.asString()}Properties"
            }

            // Generate context property for WhereBuilder
            code.appendLine("""
context(builder: org.drivine.query.dsl.WhereBuilder<$dslClassName>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val ${viewProp.name}: $fullyQualifiedPropertiesClassName
    get() = builder.queryObject.${viewProp.name}
""".trimIndent())
            code.appendLine()

            // Generate context property for OrderBuilder
            code.appendLine("""
context(builder: org.drivine.query.dsl.OrderBuilder<$dslClassName>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val ${viewProp.name}: $fullyQualifiedPropertiesClassName
    get() = builder.queryObject.${viewProp.name}
""".trimIndent())
            code.appendLine()
        }

        return code.toString()
    }
}