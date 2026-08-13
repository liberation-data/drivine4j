package org.drivine.codegen

import com.google.devtools.ksp.getAllSuperTypes
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
    private val graphViewClasses: List<KSClassDeclaration>,
    /** Bare @NodeFragment classes to also generate a standalone query DSL for (not just view roots). */
    private val fragmentClasses: List<KSClassDeclaration> = emptyList(),
) {

    fun generateAll() {
        logger.info(
            "Generating QueryDsl for ${graphViewClasses.size} @GraphView and ${fragmentClasses.size} @NodeFragment classes"
        )

        // Standalone fragment DSLs: a query-object + INSTANCE + loadAll/count/deleteAll bound to it,
        // so a bare @NodeFragment is queryable like a view (its where surface is the node's properties).
        // The reified extensions are emitted only for "root" fragments (no generated @NodeFragment
        // supertype): a subtype's `loadAll<Sub>` would be ambiguous with the base's (a subtype
        // satisfies `T : Base`), so subtypes get only their QueryDsl class — usable via the explicit
        // 3-arg `loadAll(Sub::class.java, SubQueryDsl.INSTANCE) { }` and as an `instanceOf()` target;
        // `loadAll<Sub> { }` resolves to the base extension, typed `List<Sub>`.
        val fragmentNames = fragmentClasses.mapNotNull { it.qualifiedName?.asString() }.toSet()
        fragmentClasses.forEach { fragment ->
            val hasGeneratedSupertype = fragment.getAllSuperTypes().any {
                it.declaration.qualifiedName?.asString() in fragmentNames
            }
            generateFragmentDslFile(fragment, emitExtensions = !hasGeneratedSupertype)
        }

        if (graphViewClasses.isEmpty()) return

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

    /**
     * Generates the standalone query DSL for a bare `@NodeFragment`: a `<Fragment>QueryDsl` query
     * object (a [org.drivine.query.dsl.NodeReference] at the fragment root alias `"n"`, exposing the
     * node's property references + `instanceOf()` for sealed/abstract fragments) plus `INSTANCE`, and
     * the `INSTANCE`-injecting `loadAll` / `count` / `deleteAll` extensions bound to it. `where` targets
     * node properties directly; the parameterized binding path is the view DSL's ($param_*). Vector
     * search is already available via the hand-written non-filtered `loadNearest<T>`.
     */
    private fun generateFragmentDslFile(fragmentClass: KSClassDeclaration, emitExtensions: Boolean) {
        val packageName = fragmentClass.packageName.asString()
        val simpleName = fragmentClass.simpleName.asString()
        val dslClassName = "${simpleName}QueryDsl"
        val fragmentClassName = fragmentClass.toClassName()
        val dslClass = ClassName(packageName, dslClassName)

        val fileSpecBuilder = FileSpec.builder(packageName, dslClassName)
            .addFileComment("Generated code - do not modify")
            .addType(generateFragmentDslClass(fragmentClass, dslClassName))

        if (emitExtensions) {
            fileSpecBuilder
                .addFunction(
                    fragmentDslExtension(fragmentClassName, dslClass, dslClassName, "loadAll",
                        List::class.asClassName().parameterizedBy(TypeVariableName("T")))
                )
                .addFunction(
                    fragmentDslExtension(fragmentClassName, dslClass, dslClassName, "count", Long::class.asClassName())
                )
                .addFunction(
                    fragmentDslExtension(fragmentClassName, dslClass, dslClassName, "deleteAll", Int::class.asClassName())
                )

            // Filtered full-text-search wrapper — only for fragments carrying a @FullTextIndex.
            if (fragmentHasFullTextIndex(fragmentClass)) {
                fileSpecBuilder.addFunction(
                    fragmentLoadMatchingExtension(fragmentClassName, dslClass, dslClassName)
                )
            }
        }

        fileSpecBuilder.build().writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(false, fragmentClass.containingFile!!)
        )
        logger.info("Generated $dslClassName at $packageName.$dslClassName (extensions=$emitExtensions)")
    }

    /** The `<Fragment>QueryDsl` query object: a NodeReference at alias `"n"` with the node's property refs. */
    private fun generateFragmentDslClass(fragmentClass: KSClassDeclaration, dslClassName: String): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(dslClassName)
            // ResolvableNodeReference (: NodeReference) — carries the logical-key → stored-path resolver.
            .addSuperinterface(ClassName("org.drivine.query.dsl", "ResolvableNodeReference"))

        // Fixed root alias "n" — matches FragmentQueryBuilder.nodeAlias, so `where { query.x }` renders
        // `n.x` and instanceOf() renders `n:Label`, aligning with the fragment's MATCH/RETURN.
        classBuilder.addProperty(
            PropertySpec.builder("nodeAlias", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("\"n\"")
                .build()
        )

        // Accumulate the model-aware key resolver as each reference is emitted: a scalar field maps both
        // its Kotlin name and its @GraphProperty on-disk name to the on-disk name; a @PropertyBag adds a
        // stored prefix. field(key) / predicateOn(key) consult these at query time.
        val fieldKeyPaths = LinkedHashMap<String, String>()
        val bagPrefixes = mutableListOf<String>()
        fragmentClass.getAllProperties().forEach { prop ->
            when (val emitted = addPropertyReference(classBuilder, prop, "\"n\"")) {
                is EmittedRef.Field -> {
                    fieldKeyPaths[emitted.logicalName] = emitted.onDiskName
                    fieldKeyPaths[emitted.onDiskName] = emitted.onDiskName
                }
                is EmittedRef.Bag -> bagPrefixes.add(emitted.storedPrefix)
            }
        }

        val stringType = String::class.asClassName()
        val mapInit = if (fieldKeyPaths.isEmpty()) "emptyMap()" else
            "mapOf(${fieldKeyPaths.entries.joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }})"
        classBuilder.addProperty(
            PropertySpec.builder("fieldKeyPaths", Map::class.asClassName().parameterizedBy(stringType, stringType))
                .addModifiers(KModifier.OVERRIDE)
                .initializer(mapInit)
                .build()
        )
        val listInit = "listOf(${bagPrefixes.joinToString(", ") { "\"$it\"" }})"
        classBuilder.addProperty(
            PropertySpec.builder("bagPrefixes", List::class.asClassName().parameterizedBy(stringType))
                .addModifiers(KModifier.OVERRIDE)
                .initializer(listInit)
                .build()
        )

        classBuilder.addType(
            TypeSpec.companionObjectBuilder()
                .addProperty(
                    PropertySpec.builder("INSTANCE", ClassName(fragmentClass.packageName.asString(), dslClassName))
                        .initializer("$dslClassName()")
                        .build()
                )
                .build()
        )
        return classBuilder.build()
    }

    /**
     * One `INSTANCE`-injecting reified extension on `GraphObjectManager` for a fragment, mirroring the
     * view wrappers: `inline fun <reified T : Fragment> …(spec) = …(T::class.java, DslClass.INSTANCE, spec)`.
     */
    private fun fragmentDslExtension(
        fragmentClassName: ClassName,
        dslClass: ClassName,
        dslClassName: String,
        funcName: String,
        returnType: TypeName,
    ): FunSpec {
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        return FunSpec.builder(funcName)
            // A final (concrete) fragment makes `reified T : Fragment` predetermined — harmless, and the
            // explicit `loadAll<Fragment> { }` still reads well; suppress the resulting warning.
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class).addMember("%S", "FINAL_UPPER_BOUND").build()
            )
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(TypeVariableName("T", fragmentClassName).copy(reified = true))
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE).build()
            )
            .returns(returnType)
            .addStatement("return $funcName(T::class.java, $dslClassName.INSTANCE, spec)")
            .build()
    }

    /**
     * The `INSTANCE`-injecting filtered `loadMatching` extension for a fragment — full-text relevance
     * plus a `where { }` predicate over the fragment's node properties, in one call:
     *
     * ```kotlin
     * inline fun <reified T : ChunkNode> GraphObjectManager.loadMatching(
     *     query: String, topK: Int, threshold: Double = 0.0,
     *     noinline spec: GraphQuerySpec<ChunkNodeQueryDsl>.() -> Unit,
     * ): List<Scored<T>> = loadMatching(T::class.java, ChunkNodeQueryDsl.INSTANCE, query, topK, threshold, spec)
     * ```
     *
     * Emitted only for root fragments (the same `emitExtensions` gate the other fragment wrappers use)
     * that carry a `@FullTextIndex`. The non-filtered `loadMatching<T>(query, topK)` is hand-written.
     */
    private fun fragmentLoadMatchingExtension(
        fragmentClassName: ClassName,
        dslClass: ClassName,
        dslClassName: String,
    ): FunSpec {
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val scoredClass = ClassName("org.drivine.manager", "Scored")
        return FunSpec.builder("loadMatching")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class).addMember("%S", "FINAL_UPPER_BOUND").build()
            )
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(TypeVariableName("T", fragmentClassName).copy(reified = true))
            .addParameter("query", String::class.asClassName())
            .addParameter("topK", Int::class.asClassName())
            .addParameter(
                ParameterSpec.builder("threshold", Double::class.asClassName())
                    .defaultValue("0.0")
                    .build()
            )
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).addModifiers(KModifier.NOINLINE).build()
            )
            .returns(List::class.asClassName().parameterizedBy(scoredClass.parameterizedBy(TypeVariableName("T"))))
            .addStatement("return loadMatching(T::class.java, $dslClassName.INSTANCE, query, topK, threshold, spec)")
            .build()
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

        // Filtered full-text-search wrapper — only for views whose root fragment has a text index.
        if (rootFragmentHasFullTextIndex(graphViewClass)) {
            fileSpecBuilder.addFunction(generateLoadMatchingExtensionFunction(graphViewClass))
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

    /** What [addPropertyReference] emitted, so callers can accumulate the model-aware key resolver. */
    sealed interface EmittedRef {
        /** A scalar/typed reference: [logicalName] (Kotlin name) and its stored [onDiskName]. */
        data class Field(val logicalName: String, val onDiskName: String) : EmittedRef
        /** A `@PropertyBag` reference with its [storedPrefix] (incl. delimiter). */
        data class Bag(val storedPrefix: String) : EmittedRef
    }

    /** Emits one property reference, dispatching to the bag / string / scalar shape. */
    private fun addPropertyReference(classBuilder: TypeSpec.Builder, prop: KSPropertyDeclaration, aliasExpr: String): EmittedRef {
        // @PropertyBag / @CompositeProperty → a PropertyBagReference with key(name), not a scalar.
        val bagAnnotation = prop.annotations.find {
            val name = it.shortName.asString()
            name == "PropertyBag" || name == "CompositeProperty"
        }
        if (bagAnnotation != null) {
            val storedPrefix = addPropertyBagReference(classBuilder, prop.simpleName.asString(), aliasExpr, bagAnnotation)
            return EmittedRef.Bag(storedPrefix)
        }

        val propName = prop.simpleName.asString()
        // @GraphProperty overrides the on-disk property name in the WHERE LHS; the accessor keeps the
        // Kotlin field name. The bind-param derives from this path (now the on-disk name) — internal.
        val onDiskName = prop.annotations
            .find { it.shortName.asString() == "GraphProperty" }
            ?.arguments?.firstOrNull()?.value as? String
            ?: propName
        val propType = prop.type.resolve()
        val propertyRefType = if (propType.declaration.qualifiedName?.asString() == "kotlin.String") {
            ClassName("org.drivine.query.dsl", "StringPropertyReference")
        } else {
            ClassName("org.drivine.query.dsl", "PropertyReference")
                .parameterizedBy(propType.toTypeName().copy(nullable = false))
        }

        classBuilder.addProperty(
            PropertySpec.builder(propName, propertyRefType)
                .initializer("$propertyRefType($aliasExpr, \"$onDiskName\")")
                .build()
        )
        return EmittedRef.Field(propName, onDiskName)
    }

    /** Emits a `PropertyBagReference(alias, storedPrefix)`; returns the stored prefix (incl. delimiter). */
    private fun addPropertyBagReference(
        classBuilder: TypeSpec.Builder,
        propName: String,
        aliasExpr: String,
        bagAnnotation: KSAnnotation,
    ): String {
        val prefix = (bagAnnotation.arguments.find { it.name?.asString() == "prefix" }?.value as? String) ?: ""
        val delimiter = (bagAnnotation.arguments.find { it.name?.asString() == "delimiter" }?.value as? String) ?: "."
        val storedPrefix = "${prefix.ifEmpty { propName }}$delimiter"
        val bagRefType = ClassName("org.drivine.query.dsl", "PropertyBagReference")
        classBuilder.addProperty(
            PropertySpec.builder(propName, bagRefType)
                .initializer("%T($aliasExpr, \"$storedPrefix\")", bagRefType)
                .build()
        )
        return storedPrefix
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
     *     vector: List<Float>, topK: Int, threshold: Double? = null, searchK: Int? = null,
     *     partitionLabel: String? = null,
     *     noinline spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit,
     * ): List<Scored<T>> = loadNearest(T::class.java, PropositionViewQueryDsl.INSTANCE, vector, topK, threshold, searchK, partitionLabel, spec)
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
                ParameterSpec.builder("searchK", Int::class.asClassName().copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .addParameter(
                ParameterSpec.builder("partitionLabel", String::class.asClassName().copy(nullable = true))
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
            .addStatement("return loadNearest(T::class.java, $dslClassName.INSTANCE, vector, topK, threshold, searchK, partitionLabel, spec)")
            .build()
    }

    /**
     * Emits a filtered full-text-search extension that injects the view's `…QueryDsl.INSTANCE`, the
     * full-text mirror of [generateLoadNearestExtensionFunction]:
     *
     * ```kotlin
     * inline fun <reified T : ChunkView> GraphObjectManager.loadMatching(
     *     query: String, topK: Int, threshold: Double = 0.0,
     *     noinline spec: GraphQuerySpec<ChunkViewQueryDsl>.() -> Unit,
     * ): List<Scored<T>> = loadMatching(T::class.java, ChunkViewQueryDsl.INSTANCE, query, topK, threshold, spec)
     * ```
     *
     * Only the filtered (spec) form is generated — the non-filtered `loadMatching<T>(query, topK,
     * threshold)` is a hand-written reified extension. Gated on the root fragment carrying a
     * `@FullTextIndex` (see [rootFragmentHasFullTextIndex]); a view with no text index can't be searched.
     */
    private fun generateLoadMatchingExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val scoredClass = ClassName("org.drivine.manager", "Scored")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)

        return FunSpec.builder("loadMatching")
            .addModifiers(KModifier.INLINE)
            .receiver(graphObjectManagerClass)
            .addTypeVariable(
                TypeVariableName("T", graphViewClassName).copy(reified = true)
            )
            .addParameter("query", String::class.asClassName())
            .addParameter("topK", Int::class.asClassName())
            .addParameter(
                ParameterSpec.builder("threshold", Double::class.asClassName())
                    .defaultValue("0.0")
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
            .addStatement("return loadMatching(T::class.java, $dslClassName.INSTANCE, query, topK, threshold, spec)")
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

    /**
     * Whether the view's `@Root` fragment carries a `@FullTextIndex` — property-level (on any
     * property) or class-level (on the fragment itself, for a multi-property index) — so it is
     * full-text searchable.
     */
    private fun rootFragmentHasFullTextIndex(graphViewClass: KSClassDeclaration): Boolean {
        val rootProperty = graphViewClass.getAllProperties().find { prop ->
            prop.annotations.any { it.shortName.asString() == "Root" }
        } ?: return false
        val rootFragment = rootProperty.type.resolve().declaration as? KSClassDeclaration ?: return false
        return fragmentHasFullTextIndex(rootFragment)
    }

    /** Whether a fragment carries a `@FullTextIndex` — property-level or class-level (multi-property). */
    private fun fragmentHasFullTextIndex(fragmentClass: KSClassDeclaration): Boolean {
        val classLevel = fragmentClass.annotations.any { it.shortName.asString() == "FullTextIndex" }
        val propertyLevel = fragmentClass.getAllProperties().any { p ->
            p.annotations.any { it.shortName.asString() == "FullTextIndex" }
        }
        return classLevel || propertyLevel
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
     * Generates extensions for WhereBuilder, OrderBuilder, and SeekBuilder contexts.
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

            // Generate context property for SeekBuilder
            code.appendLine("""
context(builder: org.drivine.query.dsl.SeekBuilder<$dslClassName>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val ${viewProp.name}: $fullyQualifiedPropertiesClassName
    get() = builder.queryObject.${viewProp.name}
""".trimIndent())
            code.appendLine()
        }

        return code.toString()
    }
}
