package org.drivine.annotation

/**
 * Maps an open `Map<String, *>` field on a [NodeFragment] to a set of **flat node properties**,
 * round-tripped on save and load. Each map entry becomes a real node property
 * `"$prefix$delimiter$key"`, so bag entries are first-class graph properties — visible, filterable,
 * and indexable like any declared field (unlike [JsonPacked], which stores the whole map as one
 * opaque JSON string).
 *
 * Mirrors Spring Data Neo4j's `@CompositeProperty` (kept as the alias [CompositeProperty]).
 *
 * ```kotlin
 * @NodeFragment(labels = ["Proposition"])
 * data class PropositionNode(
 *     @NodeId val id: String,
 *     val text: String,
 *     @PropertyBag val metadata: Map<String, Any?> = emptyMap(),   // -> metadata.<key> properties
 * )
 * ```
 *
 * `metadata = {"source": "wiki", "score": 3}` persists as `metadata.source = "wiki"`,
 * `metadata.score = 3` alongside `id` and `text`. Use [prefix] to decouple the graph namespace from
 * the field name, and [delimiter] to change the separator (e.g. `meta_source`). A fragment may carry
 * several `@PropertyBag` fields as long as no prefix is a delimiter-prefix of another's.
 *
 * **Value types:** each value must be a storable Neo4j primitive or **homogeneous array** (String,
 * Long, Double, Boolean, temporal, or arrays/lists thereof) — no nested maps/objects. A non-storable
 * value raises an `IllegalArgumentException` at save naming the offending key.
 *
 * **Declare a value type to keep it.** A bag declared with a concrete value type round-trips to that
 * type — Jackson resolves the field's declared generic when the bag is reassembled on read:
 *
 * ```kotlin
 * @PropertyBag val scores: Map<String, Int> = emptyMap()        // reads back Int, not Long
 * @PropertyBag val indexedAt: Map<String, Instant> = emptyMap() // reads back Instant
 * ```
 *
 * **Read asymmetry (untyped bags only, and permanent):** a `Map<String, Any?>` reads back
 * driver-mapped types, so an `Int` written comes back as `Long` (and a `Float` as `Double`). This is
 * inherent, not a pending gap: Neo4j, Memgraph, and FalkorDB each have exactly **one** integer type
 * (64-bit) and one float type, so the declared width is lost at *write* time and nothing on the read
 * side can recover it. An `Any?` declaration gives Jackson nothing to narrow to. Declare a typed bag
 * (above) when exact width matters, or coerce at the call site (`(v as Number).toInt()`). No value is
 * lost either way — only the declared width.
 *
 * @param prefix property-name prefix; empty (`""`) uses the field name.
 * @param delimiter separator between prefix and key (default `.`).
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PropertyBag(
    val prefix: String = "",
    val delimiter: String = ".",
)

/**
 * Alias for [PropertyBag], matching Spring Data Neo4j's `@CompositeProperty` name. Identical
 * semantics; recognized everywhere [PropertyBag] is.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class CompositeProperty(
    val prefix: String = "",
    val delimiter: String = ".",
)