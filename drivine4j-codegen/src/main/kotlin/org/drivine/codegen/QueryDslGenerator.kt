package org.drivine.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

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
        if (fragmentTypes.isEmpty()) return

        val firstViewPackage = graphViewClasses.first().packageName.asString()
        val propertiesClasses = generatePropertiesClasses(fragmentTypes)

        // Also generate Properties classes for nested @GraphView types
        val nestedViewProperties = generateNestedViewPropertiesClasses()

        // Also generate Properties classes for RelationshipFragments
        val relationshipFragmentProperties = generateRelationshipFragmentPropertiesClasses()

        val fileSpec = FileSpec.builder(firstViewPackage, "GeneratedProperties")
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

        logger.info("Generated shared properties at $firstViewPackage.GeneratedProperties")
    }

    private fun generateNestedViewPropertiesClasses(): List<TypeSpec> {
        val result = mutableListOf<TypeSpec>()
        val processed = mutableSetOf<String>()

        // Find all nested views across all graph views
        graphViewClasses.forEach { graphViewClass ->
            val viewStructure = analyzeGraphViewStructure(graphViewClass)
            viewStructure.forEach { viewProp ->
                if (viewProp.isNestedView && viewProp.nestedViewClass != null) {
                    val key = viewProp.nestedViewClass.qualifiedName?.asString() ?: return@forEach
                    if (!processed.contains(key)) {
                        processed.add(key)
                        result.add(generateViewPropertiesClass(viewProp.nestedViewClass))
                    }
                }
            }
        }

        return result
    }

    private fun generateRelationshipFragmentPropertiesClasses(): List<TypeSpec> {
        val result = mutableListOf<TypeSpec>()
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
                        result.add(generateRelationshipFragmentPropertiesClass(relFragmentClass))
                    }
                }
            }
        }

        return result
    }

    private fun generateRelationshipFragmentPropertiesClass(relFragmentClass: KSClassDeclaration): TypeSpec {
        val relFragmentName = relFragmentClass.simpleName.asString()
        val propertiesClassName = "${relFragmentName}Properties"

        val classBuilder = TypeSpec.classBuilder(propertiesClassName)

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
                        .parameterizedBy(propType.toClassName())
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
        val packageName = viewClass.packageName.asString()

        val classBuilder = TypeSpec.classBuilder(propertiesClassName)

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

        // Add properties for each fragment/relationship in the view
        viewStructure.forEach { viewProp ->
            val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
            val fragmentSimpleName = fragmentClass.simpleName.asString()
            val propPropertiesClassName = "${fragmentSimpleName}Properties"

            if (viewProp.isRootFragment) {
                // Root fragment uses the same alias as the view
                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(packageName, propPropertiesClassName)
                    )
                        .initializer("$propPropertiesClassName(alias)")
                        .build()
                )
            } else if (viewProp.isRelationship) {
                // Relationship uses composite alias
                val relationshipAlias = "\${alias}_${viewProp.name}"
                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(packageName, propPropertiesClassName)
                    )
                        .initializer("$propPropertiesClassName(\"$relationshipAlias\")")
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

        // Write to file
        val fileSpec = FileSpec.builder(graphViewClassName.packageName, dslClassName)
            .addFileComment("Generated code - do not modify")
            .addType(dslClass)
            .addFunction(loadAllExtension)
            .addFunction(deleteAllExtension)
            .build()

        fileSpec.writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(false, graphViewClass.containingFile!!)
        )

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

    private fun collectFragmentTypes(viewStructure: List<ViewProperty>): List<FragmentType> {
        val fragmentTypes = mutableMapOf<String, FragmentType>()

        viewStructure.forEach { viewProp ->
            if (viewProp.isNestedView) {
                // For nested views, we need to generate a Properties class for the view itself
                // This allows query.raisedBy.person.name instead of query.raisedBy_person.name
                val nestedViewClass = viewProp.type.declaration as? KSClassDeclaration
                if (nestedViewClass != null) {
                    // Collect fragment types from the nested view recursively
                    val nestedStructure = analyzeGraphViewStructure(nestedViewClass)
                    val nestedFragments = collectFragmentTypes(nestedStructure)
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
        val classBuilder = TypeSpec.classBuilder(propertiesClassName)

        // Add constructor parameter for alias if needed
        if (needsAliasConstructor) {
            val paramBuilder = ParameterSpec.builder("alias", String::class)
            if (hasDefaultAlias) {
                // Add default value using the fragment name as alias
                val defaultAlias = fragmentClass.simpleName.asString().replaceFirstChar { it.lowercase() }
                paramBuilder.defaultValue("\"$defaultAlias\"")
            }

            classBuilder.primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(paramBuilder.build())
                    .build()
            )
            classBuilder.addProperty(
                PropertySpec.builder("alias", String::class)
                    .initializer("alias")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )
        }

        // Generate property references for each property in the fragment
        fragmentClass.getAllProperties().forEach { prop ->
            val propName = prop.simpleName.asString()
            val propType = prop.type.resolve()

            val propertyRefType = when {
                propType.declaration.qualifiedName?.asString() == "kotlin.String" ->
                    ClassName("org.drivine.query.dsl", "StringPropertyReference")
                else ->
                    ClassName("org.drivine.query.dsl", "PropertyReference")
                        .parameterizedBy(propType.toClassName())
            }

            // For root fragments, use the field name from the GraphView (e.g., "issue")
            // Otherwise use the fragment class name (e.g., "issueCore")
            val aliasExpr = if (needsAliasConstructor) {
                "alias"
            } else {
                val defaultAlias = rootFieldName ?: fragmentClass.simpleName.asString().replaceFirstChar { it.lowercase() }
                "\"$defaultAlias\""
            }

            classBuilder.addProperty(
                PropertySpec.builder(propName, propertyRefType)
                    .initializer(
                        if (propType.declaration.qualifiedName?.asString() == "kotlin.String") {
                            "$propertyRefType($aliasExpr, \"$propName\")"
                        } else {
                            "$propertyRefType($aliasExpr, \"$propName\")"
                        }
                    )
                    .build()
            )
        }

        return classBuilder.build()
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

                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(graphViewClassName.packageName, propertiesClassName)
                    )
                        .initializer("$propertiesClassName(\"${viewProp.relationshipAlias}\")")
                        .build()
                )
            } else {
                // Regular fragment or relationship
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
                val fragmentSimpleName = fragmentClass.simpleName.asString()
                val propertiesClassName = "${fragmentSimpleName}Properties"

                // Always pass the field name as alias - for root fragments, this is the view's field name
                // (e.g., "core" for `@Root val core: GuideUser`)
                // For relationships, this is the relationship field name (e.g., "webUser")
                val alias = viewProp.relationshipAlias ?: viewProp.name
                val initializer = "$propertiesClassName(\"$alias\")"

                classBuilder.addProperty(
                    PropertySpec.builder(
                        viewProp.name,
                        ClassName(graphViewClassName.packageName, propertiesClassName)
                    )
                        .initializer(initializer)
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
}