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

        val fileSpec = FileSpec.builder(firstViewPackage, "GeneratedProperties")
            .addFileComment("Generated code - do not modify")
            .apply {
                propertiesClasses.forEach { addType(it) }
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

    private fun generateViewDslFile(graphViewClass: KSClassDeclaration, viewStructure: List<ViewProperty>) {
        val graphViewClassName = graphViewClass.toClassName()
        val graphViewSimpleName = graphViewClass.simpleName.asString()
        val dslClassName = "${graphViewSimpleName}QueryDsl"

        // Generate the main QueryDsl class
        val dslClass = generateQueryDslClass(graphViewClass, viewStructure)

        // Generate extension function
        val extensionFunction = generateExtensionFunction(graphViewClass)

        // Write to file
        val fileSpec = FileSpec.builder(graphViewClassName.packageName, dslClassName)
            .addFileComment("Generated code - do not modify")
            .addType(dslClass)
            .addFunction(extensionFunction)
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
        val relationshipAlias: String? = null
    )

    private data class FragmentType(
        val fragmentClass: KSClassDeclaration,
        val needsAliasConstructor: Boolean,
        val hasDefaultAlias: Boolean = false
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

            if (graphRelAnnotation != null) {
                // It's a relationship property
                val targetType = extractRelationshipTargetType(propertyType)
                val isNestedView = isGraphView(targetType)

                properties.add(
                    ViewProperty(
                        name = propertyName,
                        type = targetType,
                        isRootFragment = false,
                        isRelationship = true,
                        isNestedView = isNestedView,
                        relationshipAlias = propertyName
                    )
                )
            } else {
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
        }

        return properties
    }

    private fun isGraphView(type: KSType): Boolean {
        val decl = type.declaration
        return decl.annotations.any { it.shortName.asString() == "GraphView" }
    }

    private fun isGraphFragment(type: KSType): Boolean {
        val decl = type.declaration
        return decl.annotations.any { it.shortName.asString() == "GraphFragment" }
    }

    private fun collectFragmentTypes(viewStructure: List<ViewProperty>): List<FragmentType> {
        val fragmentTypes = mutableMapOf<String, FragmentType>()

        viewStructure.forEach { viewProp ->
            if (viewProp.isNestedView) {
                // For nested views, collect their fragment types recursively
                val nestedViewClass = viewProp.type.declaration as? KSClassDeclaration
                if (nestedViewClass != null) {
                    nestedViewClass.getAllProperties().forEach { nestedProp ->
                        val nestedType = nestedProp.type.resolve()
                        val nestedTargetType = extractRelationshipTargetType(nestedType)

                        if (isGraphFragment(nestedTargetType)) {
                            val nestedFragmentClass = nestedTargetType.declaration as? KSClassDeclaration
                            if (nestedFragmentClass != null) {
                                val nestedName = nestedFragmentClass.simpleName.asString()
                                if (!fragmentTypes.containsKey(nestedName)) {
                                    // Nested view fragments need alias constructor
                                    fragmentTypes[nestedName] = FragmentType(nestedFragmentClass, true)
                                }
                            }
                        }
                    }
                }
            } else if (isGraphFragment(viewProp.type)) {
                // Regular fragment
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration
                if (fragmentClass != null) {
                    val fragmentName = fragmentClass.simpleName.asString()
                    if (!fragmentTypes.containsKey(fragmentName)) {
                        fragmentTypes[fragmentName] = FragmentType(
                            fragmentClass,
                            needsAliasConstructor = !viewProp.isRootFragment
                        )
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
                hasDefaultAlias = fragmentType.hasDefaultAlias
            )
        }
    }

    private fun generateFragmentPropertiesClass(
        fragmentClass: KSClassDeclaration,
        propertiesClassName: String,
        needsAliasConstructor: Boolean,
        hasDefaultAlias: Boolean
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

            val aliasExpr = if (needsAliasConstructor) "alias" else "\"${fragmentClass.simpleName.asString().replaceFirstChar { it.lowercase() }}\""

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
            if (viewProp.isNestedView) {
                // For nested views, generate properties for each of their fragments/relationships
                val nestedViewClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach

                nestedViewClass.getAllProperties().forEach { nestedProp ->
                    val nestedPropName = "${viewProp.name}_${nestedProp.simpleName.asString()}"
                    val nestedPropType = nestedProp.type.resolve()
                    val nestedTargetType = extractRelationshipTargetType(nestedPropType)

                    if (isGraphFragment(nestedTargetType)) {
                        val fragmentClass = nestedTargetType.declaration as? KSClassDeclaration ?: return@forEach
                        val fragmentSimpleName = fragmentClass.simpleName.asString()
                        val propertiesClassName = "${fragmentSimpleName}Properties"

                        val propertyAlias = "${viewProp.relationshipAlias}_${nestedProp.simpleName.asString()}"

                        classBuilder.addProperty(
                            PropertySpec.builder(
                                nestedPropName,
                                ClassName(graphViewClassName.packageName, propertiesClassName)
                            )
                                .initializer("$propertiesClassName(\"$propertyAlias\")")
                                .build()
                        )
                    }
                }
            } else {
                // Regular fragment or relationship
                val fragmentClass = viewProp.type.declaration as? KSClassDeclaration ?: return@forEach
                val fragmentSimpleName = fragmentClass.simpleName.asString()
                val propertiesClassName = "${fragmentSimpleName}Properties"

                val initializer = if (viewProp.isRootFragment) {
                    "$propertiesClassName()"
                } else {
                    "$propertiesClassName(\"${viewProp.relationshipAlias}\")"
                }

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

    private fun generateExtensionFunction(graphViewClass: KSClassDeclaration): FunSpec {
        val graphViewClassName = graphViewClass.toClassName()
        val dslClassName = "${graphViewClass.simpleName.asString()}QueryDsl"
        val graphObjectManagerClass = ClassName("org.drivine.manager", "GraphObjectManager")
        val graphQuerySpecClass = ClassName("org.drivine.query.dsl", "GraphQuerySpec")
        val dslClass = ClassName(graphViewClassName.packageName, dslClassName)

        return FunSpec.builder("loadAll")
            .receiver(graphObjectManagerClass)
            .addParameter("type", Class::class.asClassName().parameterizedBy(graphViewClassName))
            .addParameter(
                ParameterSpec.builder(
                    "spec",
                    LambdaTypeName.get(
                        receiver = graphQuerySpecClass.parameterizedBy(dslClass),
                        returnType = Unit::class.asClassName()
                    )
                ).build()
            )
            .returns(List::class.asClassName().parameterizedBy(graphViewClassName))
            .addStatement("return loadAll(type, $dslClassName.INSTANCE, spec)")
            .build()
    }
}