package org.drivine.model

import org.drivine.annotation.CompositeProperty
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphTransient
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeLabels
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.VectorIndex
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * Represents metadata about a class annotated with @GraphFragment.
 * This model captures the structure needed for mapping between graph nodes and domain objects.
 */
data class FragmentModel(
    /**
     * The fully qualified class name of the fragment class.
     * Example: "sample.mapped.fragment.Person"
     */
    val className: String,

    /**
     * The Class object for the fragment class.
     * Useful for instantiation and Java interop.
     */
    val clazz: Class<*>,

    /**
     * The labels associated with this fragment from the @GraphFragment annotation.
     * Example: ["Person"] or ["Person", "GithubPerson"]
     */
    val labels: List<String>,

    /**
     * The fields/properties of this fragment class with their types. Includes declared and inherited
     * fields, but **excludes** `@PropertyBag` fields (those are in [propertyBags]) — so the normal
     * SET and projection paths treat the bag's prefixed properties, not the map field itself.
     */
    val fields: List<FragmentField>,

    /**
     * The name of the field annotated with @GraphNodeId, if present.
     * This field represents the Neo4j node ID.
     */
    val nodeIdField: String?,

    /**
     * The `@PropertyBag` / `@CompositeProperty` fields on this fragment — open maps persisted as flat
     * prefixed node properties. Empty for the common case.
     */
    val propertyBags: List<PropertyBagModel> = emptyList(),
    /**
     * The `@NodeLabels` field on this fragment, if any — a set whose contents are node labels. Like a
     * bag it is **excluded** from [fields], so it is never written or projected as a property.
     */
    val nodeLabels: NodeLabelsModel? = null,
) {
    /**
     * Names of `@VectorIndex` (embedding) fields — the fields whose value must be written as the
     * engine's native vector type on save. Empty for the common (non-vector) fragment.
     */
    val vectorFieldNames: Set<String>
        get() = fields.filter { it.vectorIndexed }.map { it.name }.toSet()

    /** The name of the `@NodeStamp` field, or null when the fragment declares none. */
    val stampField: String?
        get() = fields.firstOrNull { it.stamp }?.name

    /**
     * The on-disk node-property name of the `@NodeId` field — the MERGE key and load-`WHERE` property.
     * Equals [nodeIdField] unless the id field carries a `@GraphProperty` override. Null when there is
     * no id field.
     */
    val nodeIdProperty: String?
        get() = nodeIdField?.let { idName -> fields.firstOrNull { it.name == idName }?.propertyName ?: idName }

    companion object {
        /**
         * Creates a FragmentModel from a class annotated with @GraphFragment.
         *
         * @param clazz The class to analyze
         * @return FragmentModel containing metadata about the class
         * @throws IllegalArgumentException if the class is not annotated with @GraphFragment
         */
        fun from(clazz: Class<*>): FragmentModel {
            clazz.getAnnotation(NodeFragment::class.java)
                ?: throw IllegalArgumentException("Class ${clazz.name} is not annotated with @GraphFragment")

            val labels = labelsFor(clazz)
            val allFields = extractFields(clazz)
            val nodeIdField = findNodeIdField(clazz)

            // The stamp first: two stamp fields share a property, and should be told so as stamps.
            validateStamp(allFields, clazz)
            validateGraphProperty(allFields, clazz)

            val nodeLabels = resolveNodeLabels(allFields, labels, clazz)
            val declared = allFields.filter { it.propertyBag == null && it.nodeLabels == null }

            // Partition @PropertyBag fields out of the regular fields: they are persisted/loaded as
            // flat prefixed properties, not as a single map-valued property.
            val prefixedBags = allFields.mapNotNull { field ->
                field.propertyBag?.takeIf { !it.flat }?.let { spec ->
                    val prefix = spec.prefix.ifEmpty { field.name }
                    PropertyBagModel(fieldName = field.name, storedPrefix = "$prefix${spec.delimiter}")
                }
            }
            validateNonOverlappingPrefixes(prefixedBags, clazz)
            (declared.map { it.propertyName } + prefixedBags.map { it.storedPrefix })
                .firstOrNull { it.startsWith(NodeLabelsModel.RESERVED_PREFIX) }
                ?.let {
                    throw IllegalArgumentException(
                        "${clazz.simpleName} stores a property under '$it'. Names beginning " +
                            "'${NodeLabelsModel.RESERVED_PREFIX}' are Drivine's own; choose another."
                    )
                }
            val flatBags = allFields.filter { it.propertyBag?.flat == true }.map { field ->
                PropertyBagModel(
                    fieldName = field.name,
                    storedPrefix = "",
                    flat = true,
                    // Declared properties, the row's label keys, and the names the other bags and the labels
                    // field are reassembled under on load — none of them is an open property.
                    reservedKeys = declared.map { it.propertyName }.toSet() + RESERVED_RESULT_KEYS +
                        allFields.filter { it.name != field.name && (it.propertyBag != null || it.nodeLabels != null) }.map { it.name },
                    reservedPrefixes = prefixedBags.map { it.storedPrefix } + NodeLabelsModel.RESERVED_PREFIX,
                )
            }
            require(flatBags.size <= 1) {
                "${clazz.simpleName} has ${flatBags.size} flat @PropertyBag fields " +
                    "(${flatBags.joinToString { "'${it.fieldName}'" }}). A flat bag owns every property nothing " +
                    "else declares, so a fragment can have only one."
            }

            return FragmentModel(
                className = clazz.name,
                clazz = clazz,
                labels = labels,
                fields = declared,
                nodeIdField = nodeIdField,
                // Prefixed bags first: on load each claims its own keys, and the flat bag takes the rest.
                propertyBags = prefixedBags + flatBags,
                nodeLabels = nodeLabels,
            )
        }

        /**
         * Keys every fragment projection adds to a result row beside the node's properties — the
         * node's labels, for subtype dispatch. A flat bag must not mistake them for properties.
         */
        private val RESERVED_RESULT_KEYS = setOf("labels", "__labels")

        /**
         * The fragment's `@NodeLabels` field, validated: at most one, and a closed set must not name
         * one of the fragment's own labels — a save under `CLEAR` would otherwise remove the label the
         * fragment is matched by.
         */
        private fun resolveNodeLabels(allFields: List<FragmentField>, labels: List<String>, clazz: Class<*>): NodeLabelsModel? {
            val candidates = allFields.mapNotNull { it.nodeLabels }
            require(candidates.size <= 1) {
                "${clazz.simpleName} has ${candidates.size} @NodeLabels fields " +
                    "(${candidates.joinToString { "'${it.fieldName}'" }}). A fragment may carry one."
            }
            val model = candidates.singleOrNull() ?: return null
            val own = model.closedSet.orEmpty().filter { it in labels }
            require(own.isEmpty()) {
                "@NodeLabels field '${model.fieldName}' on ${clazz.simpleName} is a set of an enum that names " +
                    "the fragment's own label${if (own.size > 1) "s" else ""} ${own.joinToString { "'$it'" }}. " +
                    "A fragment's own labels are fixed; remove ${if (own.size > 1) "them" else "it"} from the enum."
            }
            return model
        }

        /**
         * A fragment has at most one `@NodeStamp` field, and it is a nullable `String` that carries no other
         * mapping annotation: it is neither a property bag nor the node's labels, and no
         * `@GraphProperty` renames it.
         */
        private fun validateStamp(allFields: List<FragmentField>, clazz: Class<*>) {
            val stamps = allFields.filter { it.stamp }
            require(stamps.size <= 1) {
                "${clazz.simpleName} has ${stamps.size} @NodeStamp fields (${stamps.joinToString { "'${it.name}'" }}). A node has one stamp."
            }
            // A Java field does not say whether it can be null, and it can: only a Kotlin class is held to it.
            val declaresNullability = clazz.isAnnotationPresent(Metadata::class.java)
            stamps.forEach {
                // Nullable: a new object has no stamp yet, and neither has a node saved before 0.1.0.
                require(it.type == String::class.java && (it.nullable || !declaresNullability) && it.propertyBag == null && it.nodeLabels == null) {
                    "@NodeStamp field '${it.name}' on ${clazz.simpleName} must be a nullable String and carry no other mapping annotation."
                }
                require(it.propertyName == Stamps.QUOTED) {
                    "@NodeStamp field '${it.name}' on ${clazz.simpleName} also has @GraphProperty(\"${it.propertyName}\"). " +
                        "The stamp is stored under a property of its own, so it carries no other mapping annotation: remove @GraphProperty."
                }
            }
        }

        /**
         * Validates `@GraphProperty` overrides: it may not combine with `@PropertyBag` on the same
         * field (a bag manages its own prefixed names), and no two non-bag fields may resolve to the
         * same on-disk [FragmentField.propertyName] — whether via an override or a collision with
         * another field's default name. Both fail fast, naming the offending field(s).
         */
        private fun validateGraphProperty(allFields: List<FragmentField>, clazz: Class<*>) {
            allFields.forEach { field ->
                if (field.propertyBag != null && field.propertyName != field.name) {
                    throw IllegalArgumentException(
                        "Field '${field.name}' on ${clazz.simpleName} has both @GraphProperty and @PropertyBag. " +
                            "A property bag manages its own prefixed property names, so an explicit " +
                            "@GraphProperty override is contradictory — remove one."
                    )
                }
            }

            allFields.filter { it.propertyBag == null && it.nodeLabels == null }
                .groupBy { it.propertyName }
                .filterValues { it.size > 1 }
                .forEach { (propertyName, clashing) ->
                    throw IllegalArgumentException(
                        "Fields ${clashing.joinToString(" and ") { "'${it.name}'" }} on ${clazz.simpleName} " +
                            "both map to node property '$propertyName'. Give each a distinct " +
                            "@GraphProperty name so they don't overwrite each other on save."
                    )
                }
        }

        /**
         * Rejects fragments whose property bags have overlapping prefixes — if one bag's prefix is a
         * delimiter-prefix of another's, a stored key matches both on load and would be claimed
         * twice. Fail loudly at model build rather than silently mis-assigning entries.
         */
        private fun validateNonOverlappingPrefixes(bags: List<PropertyBagModel>, clazz: Class<*>) {
            for (a in bags) {
                for (b in bags) {
                    if (a !== b && b.storedPrefix.startsWith(a.storedPrefix)) {
                        throw IllegalArgumentException(
                            "@PropertyBag prefixes on ${clazz.simpleName} overlap: '${a.storedPrefix}' " +
                                "(field '${a.fieldName}') is a prefix of '${b.storedPrefix}' (field '${b.fieldName}'). " +
                                "Use distinct, non-nested prefixes."
                        )
                    }
                }
            }
        }

        /**
         * Creates a FragmentModel from a Kotlin class annotated with @GraphFragment.
         */
        fun from(kClass: KClass<*>): FragmentModel = from(kClass.java)

        /**
         * Resolves the complete set of @NodeFragment labels a class
         * persists with: the class's own labels unioned with those
         * declared on its superclasses and (transitively) implemented
         * interfaces.
         *
         * The class's own labels lead, followed by inherited labels in
         * breadth-first order, with duplicates removed. So a concrete
         * subtype of `@NodeFragment(labels = ["Signal"]) interface Signal`
         * persists as `:EmailSignal:Signal` without having to repeat
         * "Signal" in its own annotation — which is what makes
         * `MATCH (n:Signal)` find every subtype.
         *
         * Before this traversal existed only the concrete class's own
         * annotation was inspected, so interface/superclass labels were
         * silently dropped at save time and `pm.registerSubtype` only
         * wired load-time polymorphism, never the persisted label set.
         */
        fun labelsFor(clazz: Class<*>): List<String> {
            val labels = LinkedHashSet<String>()
            val visited = mutableSetOf<Class<*>>()
            val queue = ArrayDeque<Class<*>>()
            queue.add(clazz)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                current.getAnnotation(NodeFragment::class.java)
                    ?.let { labels.addAll(it.labels) }
                current.superclass
                    ?.takeIf { it != Any::class.java }
                    ?.let { queue.add(it) }
                current.interfaces.forEach { queue.add(it) }
            }
            return labels.toList()
        }

        /**
         * Extracts all fields from a class, including inherited fields.
         * Handles both Java and Kotlin classes.
         */
        private fun extractFields(clazz: Class<*>): List<FragmentField> {
            // Try Kotlin reflection first
            return try {
                extractKotlinFields(clazz)
            } catch (e: Exception) {
                // Fall back to Java reflection for Java classes or if Kotlin reflection fails
                extractJavaFields(clazz)
            }
        }

        /**
         * Extracts fields using Kotlin reflection.
         * Provides better type information including nullability.
         */
        private fun extractKotlinFields(clazz: Class<*>): List<FragmentField> {
            val kClass = clazz.kotlin
            return kClass.memberProperties
                .filterNot { it.isGraphTransient() }
                .filterNot { it.isStaticBackingField() }
                .map { property ->
                    val returnType = property.returnType
                    val javaType = returnType.javaType as? Class<*> ?: Any::class.java

                    FragmentField(
                        name = property.name,
                        type = javaType,
                        kotlinType = returnType.classifier as? KClass<*>,
                        nullable = returnType.isMarkedNullable,
                        typeString = returnType.toString(),
                        propertyBag = property.propertyBagSpec(),
                        vectorIndexed = property.isVectorIndexed(),
                        propertyName = storedName(property.name, property.isNodeStamp(), property.graphPropertyName()),
                        nodeLabels = property.nodeLabelsModel(clazz),
                        stamp = property.isNodeStamp(),
                    )
                }.sortedBy { it.name }
        }

        /**
         * The property a field is stored under: its `@GraphProperty` [override] or its own name, and
         * for a `@NodeStamp` field the stamp's property. A stamp field keeps an [override] it should
         * not have, which is how [validateStamp] finds it and refuses it.
         */
        private fun storedName(field: String, stamp: Boolean, override: String?): String =
            if (stamp && override == null) Stamps.QUOTED else override ?: field

        /** Whether a Kotlin property (or its backing field) carries `@NodeStamp`. */
        private fun KProperty1<*, *>.isNodeStamp(): Boolean =
            findAnnotation<NodeStamp>() != null || javaField?.isAnnotationPresent(NodeStamp::class.java) == true

        /** Whether a Kotlin property (or its backing field) carries `@VectorIndex`. */
        private fun KProperty1<*, *>.isVectorIndexed(): Boolean =
            findAnnotation<VectorIndex>() != null || javaField?.isAnnotationPresent(VectorIndex::class.java) == true

        /** The `@GraphProperty` override on a Kotlin property (or its backing field), or null. */
        private fun KProperty1<*, *>.graphPropertyName(): String? =
            (findAnnotation<GraphProperty>() ?: javaField?.getAnnotation(GraphProperty::class.java))?.value

        /** Reads `@PropertyBag` / `@CompositeProperty` off a Kotlin property (or its backing field). */
        private fun KProperty1<*, *>.propertyBagSpec(): PropertyBagSpec? {
            findAnnotation<PropertyBag>()?.let { return PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
            findAnnotation<CompositeProperty>()?.let { return PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
            val field = javaField
            field?.getAnnotation(PropertyBag::class.java)?.let { return PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
            field?.getAnnotation(CompositeProperty::class.java)?.let { return PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
            return null
        }

        /** Reads `@NodeLabels` off a Kotlin property (or its backing field), resolving its element type. */
        private fun KProperty1<*, *>.nodeLabelsModel(owner: Class<*>): NodeLabelsModel? {
            findAnnotation<NodeLabels>() ?: javaField?.getAnnotation(NodeLabels::class.java) ?: return null
            val element = (returnType.arguments.singleOrNull()?.type?.classifier as? KClass<*>)?.java
            return nodeLabelsModel(name, element, returnType.toString(), owner)
        }

        /**
         * The model for a `@NodeLabels` field whose collection holds [element]: a closed set for an
         * enum, an open one for `String`. Anything else has no label to give and is rejected.
         */
        private fun nodeLabelsModel(fieldName: String, element: Class<*>?, declared: String, owner: Class<*>): NodeLabelsModel =
            when {
                element == String::class.java -> NodeLabelsModel(fieldName)
                element != null && element.isEnum ->
                    NodeLabelsModel(fieldName, element.enumConstants.map { (it as Enum<*>).name }.toSet())
                else -> throw IllegalArgumentException(
                    "@NodeLabels field '$fieldName' on ${owner.simpleName} is declared as $declared. " +
                        "It must be a Set (or other collection) of String or of an enum."
                )
            }

        /**
         * True if the property is annotated [GraphTransient] on the
         * property itself, its getter, its setter, or its backing
         * field. Lets callers model computed / lazy / cache
         * properties on a `@NodeFragment` class without those
         * accessors becoming MERGE columns on save.
         */
        private fun KProperty1<*, *>.isGraphTransient(): Boolean {
            if (findAnnotation<GraphTransient>() != null) return true
            if (getter.findAnnotation<GraphTransient>() != null) return true
            if (this is KMutableProperty1<*, *> &&
                setter.findAnnotation<GraphTransient>() != null
            ) return true
            val backingField = javaField
            if (backingField?.isAnnotationPresent(GraphTransient::class.java) == true) return true
            return false
        }

        /**
         * Skip properties whose backing field is JVM-static — those
         * are companion-object refs (`Companion`) and other static
         * artefacts that Kotlin reflection surfaces via
         * `memberProperties` but that aren't instance-level state.
         */
        private fun KProperty1<*, *>.isStaticBackingField(): Boolean {
            val f = javaField ?: return false
            return Modifier.isStatic(f.modifiers)
        }

        /**
         * Extracts fields using Java reflection.
         * Used for Java classes or as a fallback.
         */
        private fun extractJavaFields(clazz: Class<*>): List<FragmentField> {
            val fields = mutableListOf<FragmentField>()
            var currentClass: Class<*>? = clazz

            while (currentClass != null && currentClass != Any::class.java) {
                currentClass.declaredFields
                    .filterNot { it.isSynthetic }
                    .forEach { field ->
                        val bag = field.getAnnotation(PropertyBag::class.java)?.let { PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
                            ?: field.getAnnotation(CompositeProperty::class.java)?.let { PropertyBagSpec(it.prefix, it.delimiter, it.flat) }
                        val nodeLabels = field.getAnnotation(NodeLabels::class.java)?.let {
                            val element = (field.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull() as? Class<*>
                            nodeLabelsModel(field.name, element, field.genericType.typeName, clazz)
                        }
                        fields.add(
                            FragmentField(
                                name = field.name,
                                type = field.type,
                                kotlinType = null,
                                nullable = true, // Java nullability cannot be reliably determined
                                typeString = field.genericType.typeName,
                                propertyBag = bag,
                                vectorIndexed = field.isAnnotationPresent(VectorIndex::class.java),
                                propertyName = storedName(
                                    field.name,
                                    field.isAnnotationPresent(NodeStamp::class.java),
                                    field.getAnnotation(GraphProperty::class.java)?.value,
                                ),
                                nodeLabels = nodeLabels,
                                stamp = field.isAnnotationPresent(NodeStamp::class.java),
                            )
                        )
                    }
                currentClass = currentClass.superclass
            }

            return fields.sortedBy { it.name }
        }

        /**
         * Finds the field annotated with @GraphNodeId.
         * Returns the field name if found, null otherwise.
         * Checks property- and getter-level annotations (`@get:NodeId`), including one **inherited**
         * from a supertype — e.g. a sealed subtype whose `@NodeId` is declared on the interface getter
         * (`interface X { @get:NodeId val id }`). Kotlin does not propagate the annotation onto an
         * `override`, so the subtype's own property/getter carries none; we look through supertypes.
         */
        private fun findNodeIdField(clazz: Class<*>): String? {
            // Try Kotlin reflection first
            return try {
                val kClass = clazz.kotlin
                kClass.memberProperties.find { property -> hasNodeId(kClass, property) }?.name
            } catch (e: Exception) {
                // Fall back to Java reflection
                findNodeIdFieldJava(clazz)
            }
        }

        /**
         * Whether [property] carries `@NodeId` on itself, its getter, or — for an `override` of a
         * supertype property — the same-named property/getter on any supertype (interface or class).
         */
        private fun hasNodeId(kClass: KClass<*>, property: KProperty1<*, *>): Boolean {
            if (property.findAnnotation<NodeId>() != null || property.getter.findAnnotation<NodeId>() != null) {
                return true
            }
            return kClass.allSuperclasses.any { supertype ->
                supertype.memberProperties.any { superProp ->
                    superProp.name == property.name &&
                        (superProp.findAnnotation<NodeId>() != null || superProp.getter.findAnnotation<NodeId>() != null)
                }
            }
        }

        /**
         * Finds the field annotated with @GraphNodeId using Java reflection.
         */
        private fun findNodeIdFieldJava(clazz: Class<*>): String? {
            var currentClass: Class<*>? = clazz

            while (currentClass != null && currentClass != Any::class.java) {
                val field = currentClass.declaredFields
                    .filterNot { it.isSynthetic }
                    .find { it.isAnnotationPresent(NodeId::class.java) }

                if (field != null) {
                    return field.name
                }

                currentClass = currentClass.superclass
            }

            return null
        }
    }
}
