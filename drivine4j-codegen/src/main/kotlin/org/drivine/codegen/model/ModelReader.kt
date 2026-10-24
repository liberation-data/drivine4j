package org.drivine.codegen.model

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin

/**
 * Reads a module's `@NodeFragment`, `@RelationshipFragment` and `@GraphView` classes as the model
 * file describes them: what each stores, and under which names, as the core library's
 * `FragmentModel` and `GraphViewModel` read the same classes when the application runs.
 *
 * Each class gives a map whose keys are in the order the format's schema lists them, with a null
 * for a key the class has nothing for, which [ModelJson] leaves out.
 */
internal class ModelReader {

    fun fragment(decl: KSClassDeclaration): Map<String, Any?> {
        val properties = storedProperties(decl)
        val bags = properties.filter { it.drivine("PropertyBag") != null || it.drivine("CompositeProperty") != null }
        val labelSet = properties.firstOrNull { it.drivine("NodeLabels") != null }
        val stamp = properties.filter { it.drivine("NodeStamp") != null }
        val plain = properties - bags.toSet() - stamp.toSet() - listOfNotNull(labelSet).toSet()

        // A class-level index over one property is that property's own.
        val classIndexes = INDEXES.flatMap { (annotation, kind) ->
            decl.drivineAll(annotation).map { kind to it.stringList("properties") }.filter { it.second.isNotEmpty() }
        }
        val singleIndexes = classIndexes.filter { it.second.size == 1 }.associate { it.second.single() to it.first }

        val (subtypes, subtypesComplete) = subtypes(decl)
        return linkedMapOf(
            "id" to id(decl),
            "name" to decl.simpleName.asString(),
            "labels" to labels(decl),
            "nodeId" to properties.firstOrNull { isNodeId(it) }?.simpleName?.asString(),
            "stamp" to if (stamp.isNotEmpty()) true else null,
            "subtypes" to subtypes.ifEmpty { null },
            "subtypesComplete" to if (subtypesComplete) null else false,
            "labelSet" to labelSet?.let { labelSet(it) },
            "bags" to bags.sortedBy { it.simpleName.asString() }.map { bag(it) }.ifEmpty { null },
            "properties" to plain.sortedBy { it.simpleName.asString() }
                .map { property(it, singleIndexes[it.simpleName.asString()]) },
            "indexes" to classIndexes.filter { it.second.size > 1 }
                .map { (kind, names) -> linkedMapOf("kind" to kind, "properties" to names) }
                .ifEmpty { null },
        )
    }

    fun relationshipFragment(decl: KSClassDeclaration): Map<String, Any?> {
        val properties = storedProperties(decl)
        val target = properties.firstOrNull { val t = it.type.resolve(); isFragment(t) || isView(t) }
        return linkedMapOf(
            "id" to id(decl),
            "name" to decl.simpleName.asString(),
            "target" to target?.let {
                linkedMapOf("field" to it.simpleName.asString(), "fragment" to id(it.type.resolve().declaration))
            },
            "properties" to properties.filter { it != target }
                .sortedBy { it.simpleName.asString() }
                .map { property(it, null) },
        )
    }

    fun view(decl: KSClassDeclaration): Map<String, Any?> {
        val properties = decl.getAllProperties().toList()
        // A Java view marks its root; a Kotlin one may leave it as the one fragment no annotation maps.
        val root = properties.firstOrNull { it.drivine("Root") != null }
            ?: properties.firstOrNull { prop -> VIEW_MAPPINGS.none { prop.drivine(it) != null } && isFragment(prop.type.resolve()) }
        return linkedMapOf(
            "id" to id(decl),
            "name" to decl.simpleName.asString(),
            "root" to root?.let {
                linkedMapOf("field" to it.simpleName.asString(), "fragment" to id(it.type.resolve().declaration))
            },
            "relationships" to properties
                .filter { it.drivine("GraphRelationship") != null || it.drivine("GraphPath") != null }
                .sortedBy { it.simpleName.asString() }
                .map { relationship(it) },
            "aggregates" to properties
                .mapNotNull { aggregate(it) }
                .sortedBy { it["field"] as String }
                .ifEmpty { null },
        )
    }

    private fun relationship(prop: KSPropertyDeclaration): Map<String, Any?> {
        val type = prop.type.resolve()
        val many = typeName(type) in COLLECTIONS
        val element = if (many) type.arguments.firstOrNull()?.type?.resolve() ?: type else type
        // A Java field says nothing of null, and the core library takes it as optional.
        val nullable = prop.isJava() || type.isMarkedNullable
        // As GraphViewModel reads it: a path wins over a @GraphRelationship beside it, and is not sorted.
        val path = prop.drivine("GraphPath")
        val related = if (path == null) prop.drivine("GraphRelationship") else null
        // A relationship fragment is read one hop away, whatever its maxDepth says.
        val maxDepth = (related?.arg("maxDepth") as? Int)?.takeIf { !isRelationshipFragment(element) } ?: 1
        // As GraphViewModel marks it: a path, and a list read over several hops, are never written.
        val readOnly = prop.drivine("ReadOnly") != null || path != null || (maxDepth > 1 && !isView(element))
        val sortedBy = if (path == null) prop.drivine("SortedBy") else null
        return linkedMapOf(
            "field" to prop.simpleName.asString(),
            "type" to related?.arg("type") as? String,
            "direction" to related?.let { enumName(it.arg("direction")) ?: "OUTGOING" },
            "target" to id(element.declaration),
            "many" to many,
            "required" to (!many && !nullable),
            "maxDepth" to maxDepth.takeIf { it > 1 },
            "sortedBy" to sortedBy?.let {
                linkedMapOf("property" to it.arg("property") as? String, "ascending" to ((it.arg("ascending") as? Boolean) ?: true))
            },
            "readOnly" to if (readOnly) true else null,
            "path" to path?.let { hops(it) },
        )
    }

    private fun hops(path: KSAnnotation): List<Map<String, Any?>> =
        (path.arg("hops") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>().map { hop ->
            val orderBy = (hop.arg("orderBy") as? String).orEmpty().ifEmpty { null }
            linkedMapOf(
                "type" to hop.arg("type") as? String,
                "direction" to (enumName(hop.arg("direction")) ?: "OUTGOING"),
                "label" to (hop.arg("label") as? String).orEmpty().ifEmpty { null },
                "orderBy" to orderBy,
                "ascending" to orderBy?.let { (hop.arg("ascending") as? Boolean) ?: true },
            )
        }

    private fun aggregate(prop: KSPropertyDeclaration): Map<String, Any?>? {
        val count = prop.drivine("Count")
        val aggregate = prop.drivine("Aggregate")
        val annotation = count ?: aggregate ?: return null
        return linkedMapOf(
            "field" to prop.simpleName.asString(),
            "function" to if (count != null) "COUNT" else enumName(aggregate!!.arg("function")),
            "type" to annotation.arg("type") as? String,
            "direction" to (enumName(annotation.arg("direction")) ?: "OUTGOING"),
            "property" to (aggregate?.arg("property") as? String).orEmpty().ifEmpty { null },
        )
    }

    private fun property(prop: KSPropertyDeclaration, classIndex: String?): Map<String, Any?> {
        val type = prop.type.resolve()
        val typed = typed(type)
        val packed = prop.drivine("JsonPacked") != null
        return linkedMapOf(
            "name" to prop.simpleName.asString(),
            "stored" to ((prop.drivine("GraphProperty")?.arg("value") as? String) ?: prop.simpleName.asString()),
            // A packed value the format has no type for is JSON text, whatever it is in the code.
            "type" to if (packed && typed.type == "any") "json" else typed.type,
            "of" to typed.of,
            "values" to typed.values,
            "nullable" to nullable(prop, type),
            "whenAbsent" to when {
                prop.drivine("Default") != null -> "default"
                prop.drivine("EmptyWhenAbsent") != null -> "empty"
                else -> null
            },
            "packed" to if (packed) "json" else null,
            "index" to (INDEXES.firstOrNull { (annotation, _) -> prop.drivine(annotation) != null }?.second ?: classIndex),
            "vector" to prop.drivine("VectorIndex")?.let {
                linkedMapOf("similarity" to (enumName(it.arg("similarity")) ?: "COSINE"))
            },
            "source" to source(type),
        )
    }

    private fun bag(prop: KSPropertyDeclaration): Map<String, Any?> {
        val annotation = prop.drivine("PropertyBag") ?: prop.drivine("CompositeProperty")!!
        val flat = (annotation.arg("flat") as? Boolean) ?: false
        val prefix = (annotation.arg("prefix") as? String).orEmpty().ifEmpty { prop.simpleName.asString() }
        val delimiter = (annotation.arg("delimiter") as? String) ?: "."
        return linkedMapOf(
            "field" to prop.simpleName.asString(),
            // As FragmentModel resolves it: a flat bag stores each entry under its bare key.
            "prefix" to if (flat) "" else "$prefix$delimiter",
            "flat" to if (flat) true else null,
            "type" to elementType(prop.type.resolve(), 1),
        )
    }

    private fun labelSet(prop: KSPropertyDeclaration): Map<String, Any?> {
        val element = prop.type.resolve().arguments.firstOrNull()?.type?.resolve()?.let { resolveAlias(it.declaration) }
        return linkedMapOf(
            "field" to prop.simpleName.asString(),
            "values" to (element as? KSClassDeclaration)?.takeIf { it.classKind == ClassKind.ENUM_CLASS }?.let { enumEntries(it) },
        )
    }

    /**
     * The fragments a load of [decl] may give instead, and whether that is all of them. A sealed
     * class's subclasses and a `@JsonSubTypes` list are known as the module builds; a class that is
     * open to subclassing may have more registered in code, with `registerSubtype`.
     */
    private fun subtypes(decl: KSClassDeclaration): Pair<List<String>, Boolean> {
        decl.annotations.firstOrNull { it.shortName.asString() == "JsonSubTypes" }?.let { json ->
            val types = (json.arg("value") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>()
                .mapNotNull { (it.arg("value") as? KSType)?.declaration?.let(::id) }
            return types.sorted() to true
        }
        if (Modifier.SEALED in decl.modifiers) {
            return decl.getSealedSubclasses().filter { isFragment(it) }.map { id(it) }.toList().sorted() to true
        }
        val open = decl.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in decl.modifiers ||
            Modifier.OPEN in decl.modifiers || (decl.isJava() && Modifier.FINAL !in decl.modifiers)
        return emptyList<String>() to !open
    }

    /**
     * The class's labels and those of every `@NodeFragment` it extends or implements, its own first,
     * then the inherited ones breadth first, as `FragmentModel.labelsFor` gathers them.
     */
    private fun labels(decl: KSClassDeclaration): List<String> {
        val labels = LinkedHashSet<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(decl))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(id(current))) continue
            current.drivine("NodeFragment")?.let { labels.addAll(it.stringList("labels")) }
            current.superTypes.mapNotNull { resolveAlias(it.resolve().declaration) as? KSClassDeclaration }
                .filter { it.qualifiedName?.asString() != "kotlin.Any" }
                // The superclass before the interfaces, as the JVM lists them, whatever order the source has.
                .sortedBy { it.classKind == ClassKind.INTERFACE }
                .forEach { queue.add(it) }
        }
        return labels.toList()
    }

    /** The properties a class stores: every one but those marked `@GraphTransient`. */
    private fun storedProperties(decl: KSClassDeclaration): List<KSPropertyDeclaration> =
        decl.getAllProperties().filter { it.drivine("GraphTransient") == null }.toList()

    /**
     * Whether [prop] is the node's id: `@NodeId` on it, its getter, or on the property it overrides,
     * which Kotlin does not carry over to an `override`.
     */
    private fun isNodeId(prop: KSPropertyDeclaration): Boolean =
        generateSequence(prop) { it.findOverridee() }.any { it.drivine("NodeId") != null }

    private fun nullable(prop: KSPropertyDeclaration, type: KSType): Any {
        if (!prop.isJava()) return type.isMarkedNullable
        val names = prop.mappings().map { it.shortName.asString() }.toSet()
        return when {
            names.any { it in JAVA_NULLABLE } -> true
            names.any { it in JAVA_NON_NULL } -> false
            else -> "unknown"
        }
    }

    private data class Typed(val type: String, val of: String? = null, val values: List<String>? = null)

    private fun typed(type: KSType): Typed {
        val decl = resolveAlias(type.declaration)
        if (decl is KSClassDeclaration && decl.classKind == ClassKind.ENUM_CLASS) return Typed("enum", values = enumEntries(decl))
        val name = decl.qualifiedName?.asString()
        SCALARS[name]?.let { return Typed(it) }
        PRIMITIVE_ARRAYS[name]?.let { return Typed("list", of = it) }
        if (name in COLLECTIONS || name == "kotlin.Array") return Typed("list", of = elementType(type, 0))
        if (name in MAPS) return Typed("map", of = elementType(type, 1))
        return Typed("any")
    }

    private fun elementType(type: KSType, index: Int): String =
        type.arguments.getOrNull(index)?.type?.resolve()?.let { typed(it).type } ?: "any"

    /** The type as Kotlin writes it, without its nullability, which `nullable` says. */
    private fun source(type: KSType): String {
        val name = typeName(type) ?: type.declaration.simpleName.asString()
        if (type.arguments.isEmpty()) return name
        return name + type.arguments.joinToString(", ", "<", ">") { arg -> arg.type?.resolve()?.let { source(it) } ?: "*" }
    }

    private fun enumEntries(decl: KSClassDeclaration): List<String> =
        decl.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()

    private fun isFragment(type: KSType) = resolveAlias(type.declaration).drivine("NodeFragment") != null
    private fun isFragment(decl: KSClassDeclaration) = decl.drivine("NodeFragment") != null
    private fun isView(type: KSType) = resolveAlias(type.declaration).drivine("GraphView") != null
    private fun isRelationshipFragment(type: KSType) = resolveAlias(type.declaration).drivine("RelationshipFragment") != null

    internal companion object {
        const val DRIVINE_ANNOTATIONS = "org.drivine.annotation"

        /** The annotations that map a view's field to something other than its root. */
        val VIEW_MAPPINGS = listOf("GraphRelationship", "GraphPath", "Count", "Aggregate")

        /** Each index annotation and its kind in the format, first wins when a property has several. */
        val INDEXES = listOf("Unique" to "unique", "RangeIndex" to "range", "TextIndex" to "text", "FullTextIndex" to "fullText")

        val JAVA_NULLABLE = setOf("Nullable", "CheckForNull")
        val JAVA_NON_NULL = setOf("NonNull", "Nonnull", "NotNull")

        val SCALARS = mapOf(
            "kotlin.String" to "string", "kotlin.Char" to "string",
            "kotlin.Int" to "int", "kotlin.Long" to "int", "kotlin.Short" to "int", "kotlin.Byte" to "int",
            "java.math.BigInteger" to "int",
            "kotlin.Float" to "float", "kotlin.Double" to "float", "java.math.BigDecimal" to "float",
            "kotlin.Boolean" to "boolean",
            "java.util.UUID" to "uuid",
            "java.time.LocalDate" to "date",
            "java.time.OffsetTime" to "time",
            "java.time.LocalTime" to "localTime",
            "java.time.Instant" to "datetime", "java.time.ZonedDateTime" to "datetime",
            "java.time.OffsetDateTime" to "datetime", "java.util.Date" to "datetime",
            "java.time.LocalDateTime" to "localDatetime",
            "java.time.Duration" to "duration",
        )

        val PRIMITIVE_ARRAYS = mapOf(
            "kotlin.FloatArray" to "float", "kotlin.DoubleArray" to "float",
            "kotlin.IntArray" to "int", "kotlin.LongArray" to "int", "kotlin.ShortArray" to "int",
            "kotlin.BooleanArray" to "boolean",
        )

        val COLLECTIONS = setOf(
            "kotlin.collections.List", "kotlin.collections.MutableList",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
            "kotlin.collections.Iterable", "kotlin.collections.MutableIterable",
            "java.util.List", "java.util.ArrayList", "java.util.Set", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.Collection",
        )

        val MAPS = setOf(
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
        )

        fun id(decl: KSDeclaration): String = resolveAlias(decl).qualifiedName?.asString() ?: decl.simpleName.asString()

        fun typeName(type: KSType): String? = resolveAlias(type.declaration).qualifiedName?.asString()

        fun resolveAlias(decl: KSDeclaration): KSDeclaration =
            if (decl is KSTypeAlias) resolveAlias(decl.type.resolve().declaration) else decl

        fun enumName(value: Any?): String? = when (value) {
            null -> null
            is KSType -> value.declaration.simpleName.asString()
            is KSDeclaration -> value.simpleName.asString()
            else -> value.toString().substringAfterLast('.')
        }

        fun KSDeclaration.isJava() = origin == Origin.JAVA || origin == Origin.JAVA_LIB

        fun KSAnnotation.arg(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value

        fun KSAnnotation.stringList(name: String): List<String> = (arg(name) as? List<*>).orEmpty().filterIsInstance<String>()

        /** Whether this is Drivine's own annotation, not another library's of the same simple name. */
        fun KSAnnotation.isDrivine(name: String): Boolean =
            shortName.asString() == name &&
                annotationType.resolve().declaration.qualifiedName?.asString() == "$DRIVINE_ANNOTATIONS.$name"

        fun KSAnnotated.drivine(name: String): KSAnnotation? = annotations.firstOrNull { it.isDrivine(name) }

        fun KSAnnotated.drivineAll(name: String): List<KSAnnotation> = annotations.filter { it.isDrivine(name) }.toList()

        /**
         * Every annotation that maps a property: on it, its getter and setter, and on the constructor
         * parameter it is declared by, where Kotlin puts an annotation that may also target one.
         */
        fun KSPropertyDeclaration.mappings(): Sequence<KSAnnotation> {
            val parameter = (parentDeclaration as? KSClassDeclaration)?.primaryConstructor?.parameters
                ?.firstOrNull { it.name?.asString() == simpleName.asString() && (it.isVal || it.isVar) }
            return annotations + getter?.annotations.orEmpty() + setter?.annotations.orEmpty() +
                parameter?.annotations.orEmpty()
        }

        fun KSPropertyDeclaration.drivine(name: String): KSAnnotation? = mappings().firstOrNull { it.isDrivine(name) }
    }
}

