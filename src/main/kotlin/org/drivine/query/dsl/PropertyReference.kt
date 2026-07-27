package org.drivine.query.dsl

import org.drivine.annotation.NodeFragment

/**
 * Base interface for generated Properties classes.
 * Exposes the node alias for use in type-based filtering (instanceOf).
 *
 * All generated XxxProperties classes implement this interface,
 * enabling the DSL to filter by node type:
 *
 * ```kotlin
 * where {
 *     webUser.instanceOf<AnonymousWebUser>()
 * }
 * ```
 */
interface NodeReference {
    /**
     * The Cypher alias for this node reference.
     * For example, "webUser" in a relationship target, or "core" for a root fragment.
     */
    val nodeAlias: String

    /**
     * Java-friendly instanceOf filter for polymorphic type filtering.
     *
     * Filters results to only include nodes that have all labels defined
     * in the @NodeFragment annotation of the given class.
     *
     * Example (Java):
     * ```java
     * graphObjectManager.query(GuideUserWithPolymorphicWebUser.class)
     *     .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
     *     .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
     *     .loadAll();
     * ```
     *
     * @param clazz The @NodeFragment annotated class to filter by
     * @return PropertyConditionBuilder for use with JavaQueryBuilder
     * @throws IllegalArgumentException if the class doesn't have a @NodeFragment annotation
     */
    fun instanceOf(clazz: Class<*>): PropertyConditionBuilder {
        val labels = extractLabelsFromNodeFragment(clazz)
        require(labels.isNotEmpty()) {
            "Type ${clazz.simpleName} does not have a @NodeFragment annotation with labels. " +
            "instanceOf() can only be used with types annotated with @NodeFragment."
        }
        return PropertyConditionBuilder(
            WhereCondition.LabelCondition(
                alias = this.nodeAlias,
                labels = labels
            )
        )
    }
}

/**
 * A [NodeReference] that additionally carries its fragment's **logical-key → stored-property** mapping,
 * so a caller can filter on a runtime key without knowing the on-disk name or the `@PropertyBag` prefix.
 * Codegen emits it into each `<Fragment>QueryDsl` from the fragment's `@GraphProperty` / `@PropertyBag`
 * annotations — the same single source of truth the typed accessors are generated from.
 *
 * @see field
 * @see predicateOn
 */
interface ResolvableNodeReference : NodeReference {
    /**
     * Maps a logical field key — both the declared (Kotlin/Java) name **and** any `@GraphProperty`
     * on-disk name — to the stored property name. `containerSectionId` and `container_section_id` both
     * map to `container_section_id`.
     */
    val fieldKeyPaths: Map<String, String>

    /**
     * The stored prefixes (incl. delimiter, e.g. `"metadata."`) of the fragment's `@PropertyBag` fields,
     * one per bag. An unmatched key resolves through the single prefix when there is exactly one; zero
     * or more than one makes an unmatched key unresolvable — see [resolveKey].
     */
    val bagPrefixes: List<String>
}

/**
 * Extension function to filter by node type using the @NodeFragment annotation.
 *
 * Example:
 * ```kotlin
 * where {
 *     webUser.instanceOf<AnonymousWebUser>()  // Filters to only AnonymousWebUser
 * }
 * ```
 *
 * This extracts the labels from the @NodeFragment annotation on the type
 * and generates a Cypher label check: `WHERE webUser:WebUser:Anonymous`
 *
 * @param T The NodeFragment subtype to filter by
 */
context(builder: WhereBuilder<*>)
inline fun <reified T : Any> NodeReference.instanceOf() {
    val labels = extractLabelsFromNodeFragment(T::class.java)
    require(labels.isNotEmpty()) {
        "Type ${T::class.simpleName} does not have a @NodeFragment annotation with labels. " +
        "instanceOf() can only be used with types annotated with @NodeFragment."
    }
    builder.conditions.add(
        WhereCondition.LabelCondition(
            alias = this.nodeAlias,
            labels = labels
        )
    )
}

/**
 * Extracts labels from a @NodeFragment annotation on the given class.
 *
 * @param clazz The class to extract labels from
 * @return List of labels, or empty list if no @NodeFragment annotation found
 */
fun extractLabelsFromNodeFragment(clazz: Class<*>): List<String> {
    val annotation = clazz.getAnnotation(NodeFragment::class.java)
    return annotation?.labels?.toList() ?: emptyList()
}

/**
 * Represents a reference to a property in a GraphFragment or GraphView.
 * Enables type-safe property access in the query DSL.
 *
 * Example: issue.state where "issue" is the alias and "state" is the property name.
 *
 * **Kotlin usage** (with context parameters - conditions auto-register):
 * ```kotlin
 * where {
 *     issue.state eq "open"
 * }
 * ```
 *
 * **Java usage** (methods return PropertyConditionBuilder):
 * ```java
 * graphObjectManager.query(PersonCareer.class)
 *     .where(q -> q.person().name().eq("Alice"))
 *     .loadAll();
 * ```
 */
open class PropertyReference<T>(
    internal val alias: String,
    internal val propertyName: String
) {
    // ==================== Java-friendly methods (return PropertyConditionBuilder) ====================
    // These methods return a builder that can be used with JavaQueryBuilder.
    // They have different signatures than the context parameter versions (Unit vs PropertyConditionBuilder).

    /**
     * Equality condition: property = value
     * Returns a PropertyConditionBuilder for use with Java query builder.
     */
    fun eq(value: T?): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.EQUALS,
                value = value
            )
        )
    }

    /**
     * Not equals condition: property <> value
     */
    fun neq(value: T?): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.NOT_EQUALS,
                value = value
            )
        )
    }

    /**
     * Greater than condition: property > value
     */
    fun gt(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.GREATER_THAN,
                value = value
            )
        )
    }

    /**
     * Greater than or equal condition: property >= value
     */
    fun gte(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                value = value
            )
        )
    }

    /**
     * Less than condition: property < value
     */
    fun lt(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.LESS_THAN,
                value = value
            )
        )
    }

    /**
     * Less than or equal condition: property <= value
     */
    fun lte(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.LESS_THAN_OR_EQUAL,
                value = value
            )
        )
    }

    /**
     * IN condition: property IN [values]
     */
    fun isIn(values: Collection<T>): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IN,
                value = values
            )
        )
    }

    /**
     * IS NULL condition: property IS NULL
     */
    fun isNull(): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NULL,
                value = null
            )
        )
    }

    /**
     * IS NOT NULL condition: property IS NOT NULL
     */
    fun isNotNull(): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NOT_NULL,
                value = null
            )
        )
    }

    /**
     * Ascending order specification.
     */
    fun asc(): OrderSpec {
        return OrderSpec(
            propertyPath = "$alias.$propertyName",
            direction = OrderDirection.ASC
        )
    }

    /**
     * Descending order specification.
     */
    fun desc(): OrderSpec {
        return OrderSpec(
            propertyPath = "$alias.$propertyName",
            direction = OrderDirection.DESC
        )
    }

    // ==================== Kotlin context parameter methods ====================
    // These methods auto-register conditions when used within a where/orderBy block.
    // They have different signatures (take WhereBuilder context, return Unit).

    /**
     * Equality condition with context parameter (Kotlin DSL).
     * Automatically registers itself when used in a where block.
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("eqContext")
    infix fun eq(value: T?) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.EQUALS, value))
    }

    /**
     * Not equals condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("neqContext")
    infix fun neq(value: T?) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.NOT_EQUALS, value))
    }

    /**
     * Greater than condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("gtContext")
    infix fun gt(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.GREATER_THAN, value))
    }

    /**
     * Greater than or equal condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("gteContext")
    infix fun gte(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.GREATER_THAN_OR_EQUAL, value))
    }

    /**
     * Less than condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("ltContext")
    infix fun lt(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.LESS_THAN, value))
    }

    /**
     * Less than or equal condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("lteContext")
    infix fun lte(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.LESS_THAN_OR_EQUAL, value))
    }

    /**
     * IN condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("inContext")
    infix fun `in`(values: Collection<T>) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IN, values))
    }

    /**
     * IN condition with context parameter (Kotlin DSL) — a readable alias for [`in`] that avoids the
     * backtick-quoted keyword. `resolvedId inList entityIds` → `resolvedId IN $entityIds`.
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("inListContext")
    infix fun inList(values: Collection<T>) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IN, values))
    }

    /**
     * IS NULL condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("isNullContext")
    fun isNull() {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IS_NULL, null))
    }

    /**
     * IS NOT NULL condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("isNotNullContext")
    fun isNotNull() {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IS_NOT_NULL, null))
    }

    /**
     * Ascending order with context parameter (Kotlin DSL).
     */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("ascContext")
    fun asc() {
        builder.orders.add(OrderSpec("$alias.$propertyName", OrderDirection.ASC))
    }

    /**
     * Descending order with context parameter (Kotlin DSL).
     */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("descContext")
    fun desc() {
        builder.orders.add(OrderSpec("$alias.$propertyName", OrderDirection.DESC))
    }

    // Helper to create PropertyCondition
    private fun makePropertyCondition(operator: ComparisonOperator, value: Any?): WhereCondition.PropertyCondition {
        return WhereCondition.PropertyCondition(
            propertyPath = "$alias.$propertyName",
            operator = operator,
            value = value
        )
    }

    // hasItem is intentionally an extension on PropertyReference<List<E>> (below), not a member —
    // it only type-checks when the property itself is list-valued.
}

/**
 * List-membership condition with context parameter (Kotlin DSL): a caller [value] contained in a
 * **list-valued** node property. The mirror of [PropertyReference.inList] (property IN caller list);
 * here it is caller value IN list-property.
 *
 * ```kotlin
 * where { proposition.grounding hasItem "chunk-1" }   // -> 'chunk-1' IN proposition.grounding
 * ```
 *
 * Named `hasItem` rather than `contains` on purpose: Kotlin reserves `operator fun contains` for the
 * `in` operator and requires it to return `Boolean`, whereas the DSL operators register by
 * side-effect and return `Unit`. Keeping them distinct avoids that collision.
 */
context(builder: WhereBuilder<*>)
infix fun <E> PropertyReference<List<E>>.hasItem(value: E) {
    builder.conditions.add(
        WhereCondition.ListMembershipCondition(
            propertyPath = "$alias.$propertyName",
            value = value
        )
    )
}

/**
 * String-specific property reference with additional string operations.
 */
class StringPropertyReference(
    private val stringAlias: String,
    private val stringPropertyName: String
) : PropertyReference<String>(stringAlias, stringPropertyName) {

    // ==================== Java-friendly methods ====================

    /**
     * CONTAINS condition: property CONTAINS value
     * Returns PropertyConditionBuilder for Java usage.
     */
    fun contains(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.CONTAINS,
                value = value
            )
        )
    }

    /**
     * STARTS WITH condition: property STARTS WITH value
     */
    fun startsWith(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.STARTS_WITH,
                value = value
            )
        )
    }

    /**
     * ENDS WITH condition: property ENDS WITH value
     */
    fun endsWith(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.ENDS_WITH,
                value = value
            )
        )
    }

    // ==================== Kotlin context parameter methods ====================

    /**
     * CONTAINS condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("containsContext")
    infix fun contains(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.CONTAINS,
            value = value
        ))
    }

    /**
     * STARTS WITH condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("startsWithContext")
    infix fun startsWith(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.STARTS_WITH,
            value = value
        ))
    }

    /**
     * ENDS WITH condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("endsWithContext")
    infix fun endsWith(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.ENDS_WITH,
            value = value
        ))
    }
}

/**
 * DSL accessor for a `@PropertyBag` field. A bag has no fixed schema, so individual entries are
 * reached by key:
 *
 * ```kotlin
 * where { proposition.metadata.key("source") eq "wiki" }
 * ```
 * → `WHERE proposition.`metadata.source` = $p`
 *
 * `key(name)` returns a [PropertyReference] bound to the stored property `"$storedPrefix$name"`, so it
 * composes with every operator (`eq`, `neq`, `in`, `contains`, …) on both the load path and the
 * `loadNearest { where { } }` path. The generated query DSL emits one of these per `@PropertyBag` field
 * instead of a scalar reference.
 *
 * @param alias the node alias the bag lives on (root field name, or relationship alias)
 * @param storedPrefix the bag's stored-key prefix incl. delimiter, e.g. `"metadata."`
 */
class PropertyBagReference(
    private val alias: String,
    private val storedPrefix: String,
) {
    /** A reference to the bag entry [name] (stored as `"$storedPrefix$name"`). */
    fun key(name: String): PropertyReference<Any?> = PropertyReference(alias, "$storedPrefix$name")
}

/**
 * Dynamic (untyped) reference to a node property by its **runtime** name — the escape hatch for
 * filtering on property paths not known at compile time (so no generated typed accessor exists),
 * e.g. arbitrary `@PropertyBag` keys or caller/tool-supplied filter keys.
 *
 * ```kotlin
 * where { query.property("metadata.source") eq "wiki" }   // → n.`metadata.source` = $param
 * ```
 *
 * [path] is the stored property name (a `@PropertyBag` entry is stored as `"prefix.key"`, so pass the
 * full dotted name). It is rendered exactly like [PropertyBagReference.key]: a path containing a dot is
 * backtick-quoted (and internal backticks escaped) by [org.drivine.query.dsl.CypherGenerator], and the
 * value binds as a `$param_*` — so untrusted **values** cannot inject. Prefer the generated typed
 * accessors (`query.someField`) when the key is known; use this only for genuinely dynamic keys.
 *
 * The returned reference composes with every base operator (`eq`, `neq`, `gt`, `in`, `isNull`, …). For
 * data-driven filters (translating a runtime filter object), see [predicate], which takes the operator
 * as a value and covers the string operators too.
 */
fun NodeReference.property(path: String): PropertyReference<Any?> =
    PropertyReference(this.nodeAlias, path)

/**
 * Appends a single property predicate built from a **runtime** `(path, operator, value)` triple — the
 * programmatic counterpart to [property], for translating a data-driven filter (a list/tree of
 * key/op/value leaves) into `where { }` without a compile-time accessor per key.
 *
 * ```kotlin
 * where {
 *     runtimeFilter.leaves.forEach { query.predicate(it.path, it.op, it.value) }
 * }
 * ```
 *
 * Covers the full [ComparisonOperator] set (including `CONTAINS` / `STARTS_WITH` / `ENDS_WITH`, which
 * the untyped [property] reference does not expose). [value] is ignored for `IS_NULL` / `IS_NOT_NULL`
 * and should be a list for `IN`. The [path] is rendered (and dot-containing paths backtick-quoted)
 * exactly as [property]; the value binds as a parameter.
 */
context(builder: WhereBuilder<*>)
fun NodeReference.predicate(path: String, operator: ComparisonOperator, value: Any? = null) {
    builder.conditions.add(
        WhereCondition.PropertyCondition(
            propertyPath = "${this.nodeAlias}.$path",
            operator = operator,
            value = value,
        )
    )
}

/**
 * Resolves a **logical** [key] to its stored property name using the fragment's own annotations
 * (via [ResolvableNodeReference]): a declared field (matched by Kotlin/Java name **or** `@GraphProperty`
 * on-disk name) resolves to its on-disk name; an unmatched key resolves through the fragment's single
 * `@PropertyBag` prefix. Throws when a key matches no field and there is not exactly one bag — never
 * silently guesses.
 */
fun ResolvableNodeReference.resolveKey(key: String): String {
    fieldKeyPaths[key]?.let { return it }
    return when (bagPrefixes.size) {
        1 -> "${bagPrefixes.single()}$key"
        0 -> throw IllegalArgumentException(
            "Cannot resolve key '$key': it matches no declared field and this fragment has no @PropertyBag. " +
                "Known keys: ${fieldKeyPaths.keys.sorted()}."
        )
        else -> throw IllegalArgumentException(
            "Cannot resolve key '$key': it matches no declared field and this fragment has multiple @PropertyBag " +
                "prefixes ($bagPrefixes) — ambiguous. Use property(\"<prefix>$key\") with the explicit prefix."
        )
    }
}

/**
 * Resolving counterpart of [property]: takes a **logical** [key], resolves it to the stored path via the
 * fragment's `@GraphProperty` / `@PropertyBag` annotations (see [resolveKey]), and returns a reference
 * composing with every base operator — so the caller needn't know the on-disk name or the bag prefix.
 *
 * ```kotlin
 * where { query.field("containerSectionId") eq "s1" }   // @GraphProperty → n.container_section_id
 * where { query.field("source") eq "wiki" }             // @PropertyBag   → n.`metadata.source`
 * ```
 */
fun ResolvableNodeReference.field(key: String): PropertyReference<Any?> =
    PropertyReference(this.nodeAlias, resolveKey(key))

/**
 * Resolving counterpart of [predicate]: appends a predicate from a `(logicalKey, operator, value)`
 * triple, resolving the key to its stored path via the fragment's annotations. Full [ComparisonOperator]
 * set — the entry point for translating a data-driven filter keyed by *logical* model keys.
 */
context(builder: WhereBuilder<*>)
fun ResolvableNodeReference.predicateOn(key: String, operator: ComparisonOperator, value: Any? = null) {
    builder.conditions.add(
        WhereCondition.PropertyCondition(
            propertyPath = "${this.nodeAlias}.${resolveKey(key)}",
            operator = operator,
            value = value,
        )
    )
}

/**
 * Filters to nodes carrying **any** of [labels] — `ANY(l IN labels(alias) WHERE l IN $p)`. Contrast
 * [instanceOf], which requires **all** of a type's labels. The counterpart of embabel's
 * `EntityFilter.HasAnyLabel`.
 *
 * ```kotlin
 * where { query.hasAnyLabel("Chunk", "Section") }   // → ANY(_lbl IN labels(n) WHERE _lbl IN $p)
 * ```
 */
context(builder: WhereBuilder<*>)
fun NodeReference.hasAnyLabel(vararg labels: String) {
    require(labels.isNotEmpty()) { "hasAnyLabel() requires at least one label" }
    builder.conditions.add(WhereCondition.AnyLabelCondition(this.nodeAlias, labels.toList()))
}

// ----- Phase B operator sugar (composes with property()/typed references; also usable via predicate) -----

/** `NOT lhs IN $p` — the negation of [PropertyReference.isIn]. */
context(builder: WhereBuilder<*>)
infix fun <T> PropertyReference<T>.notIn(values: List<T>) {
    builder.conditions.add(
        WhereCondition.PropertyCondition("$alias.$propertyName", ComparisonOperator.NOT_IN, values)
    )
}

/** Regex match, `lhs =~ $p`. */
context(builder: WhereBuilder<*>)
infix fun StringPropertyReference.matches(pattern: String) {
    builder.conditions.add(
        WhereCondition.PropertyCondition("$alias.$propertyName", ComparisonOperator.MATCHES, pattern)
    )
}

/** Case-insensitive contains, `toLower(lhs) CONTAINS $p`. */
context(builder: WhereBuilder<*>)
infix fun StringPropertyReference.containsIgnoreCase(value: String) {
    builder.conditions.add(
        WhereCondition.PropertyCondition("$alias.$propertyName", ComparisonOperator.CONTAINS_IGNORE_CASE, value)
    )
}

/** Case-insensitive equals, `toLower(lhs) = $p`. */
context(builder: WhereBuilder<*>)
infix fun StringPropertyReference.eqIgnoreCase(value: String) {
    builder.conditions.add(
        WhereCondition.PropertyCondition("$alias.$propertyName", ComparisonOperator.EQUALS_IGNORE_CASE, value)
    )
}

/**
 * Reversed list-membership on an **untyped** reference (`property(path)` / `field(key)`): `$value IN
 * n.<key>`. The dynamic-key twin of the typed [hasItem] (which needs a `PropertyReference<List<E>>`);
 * use this for a runtime/bagged list key whose element type isn't known at compile time.
 */
context(builder: WhereBuilder<*>)
infix fun PropertyReference<Any?>.hasElement(value: Any?) {
    builder.conditions.add(
        WhereCondition.PropertyCondition("$alias.$propertyName", ComparisonOperator.HAS_ELEMENT, value)
    )
}

/**
 * Intermediate builder that holds a condition.
 * Automatically added to WhereBuilder when created in the DSL context.
 */
class PropertyConditionBuilder(internal val condition: WhereCondition) {
    /**
     * Allows chaining with `and` for explicit multiple conditions.
     */
    infix fun and(other: PropertyConditionBuilder): PropertyConditionChain {
        return PropertyConditionChain(listOf(this.condition, other.condition))
    }
}

/**
 * Represents a chain of conditions connected with AND.
 */
class PropertyConditionChain(internal val conditions: List<WhereCondition>) {
    infix fun and(other: PropertyConditionBuilder): PropertyConditionChain {
        return PropertyConditionChain(conditions + other.condition)
    }
}