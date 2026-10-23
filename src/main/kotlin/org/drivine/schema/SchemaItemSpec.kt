package org.drivine.schema

/**
 * A declarative description of a schema item (index or constraint) that Drivine should ensure
 * exists on a graph database.
 *
 * Specs are pure data — they carry no engine-specific syntax. The per-engine [SchemaGrammar]
 * translates a spec into DDL, and [SchemaItemInfo] describes what actually exists.
 *
 * ### Adding an option
 *
 * Specs, their [EngineVectorOptions] and [SchemaPolicy] are constructed by consumers' compiled code,
 * often a library built against another Drivine, so a new option must leave in place every
 * constructor that code links to:
 *
 * - Add the option as the last parameter, with a default. Never reorder, retype or remove a parameter.
 * - Keep `@JvmOverloads` on every constructor, convenience ones included. Each arity is then its own
 *   constructor, so a Java caller, or a Kotlin one that passed every argument, still links.
 *   `JavaSchemaSpecConstructorsTest` builds each arity from Java; add the new one there.
 * - A Kotlin caller that left a default out links instead to the synthetic
 *   `<init>(…, int mask, DefaultConstructorMarker)`, whose descriptor grows with every parameter and
 *   which `@JvmOverloads` does not keep. Keep it with a hidden constructor of the previous synthetic
 *   signature that forwards to the new one, one per released shape (bit `i` of the mask is set when
 *   parameter `i` was left out):
 *
 * ```kotlin
 * @Deprecated("Binary compatibility with callers compiled before newOption", level = DeprecationLevel.HIDDEN)
 * constructor(label: String, properties: List<String>, name: String?, mask: Int, marker: DefaultConstructorMarker?) :
 *     this(label, properties, if (mask and 4 != 0) null else name)
 * ```
 *
 * The generated `copy` has the same synthetic shape and no such remedy, so a Kotlin caller of `copy`
 * still needs the Drivine it was compiled against.
 *
 * @see IndexSpec
 * @see ConstraintSpec
 */
sealed interface SchemaItemSpec {

    /** The node label the item applies to. */
    val label: String

    /** The properties the item covers, in declaration order. */
    val properties: List<String>

    /**
     * Explicit name for the item. When null, a name is derived from the label and properties.
     * Ignored on engines that do not support user-supplied names (FalkorDB).
     */
    val name: String?

    val kind: SchemaItemKind

    /**
     * The name used when creating the item, on engines that support names.
     *
     * An empty explicit name is treated as absent (derive [defaultName]). Annotation defaults cannot be
     * null, so `@VectorIndex(name = "")` means "unnamed", and emitting it would make the engine reject the
     * schema ("name cannot be the empty string"). Whitespace-only names are rejected at construction
     * ([requireNameNotWhitespace]) rather than normalized here, so this and the query-side
     * [org.drivine.query.VectorIndexResolver] cannot disagree about what a given declaration resolves to.
     */
    val effectiveName: String
        get() = name?.takeIf { it.isNotBlank() } ?: defaultName()

    fun defaultName(): String

    /**
     * A stable, engine-independent identity for this item, used to record what a catalog owner
     * applied (in the `_DrivineSchema` marker's inventory) and to detect co-ownership and orphans.
     *
     * Includes [effectiveName] so a rename is a distinct key — the old name shows up as an orphan
     * against the marker rather than silently coexisting unattributed. Kept purely derived (no engine
     * state) so the same declaration produces the same key on every run and every backend.
     */
    val inventoryKey: String
        get() = "$kind:$label:${properties.joinToString(",")}:$effectiveName"
}

/**
 * Rejects a whitespace-only explicit schema-item name.
 *
 * The empty string is a legitimate "unset" sentinel — annotation attributes cannot default to null, so
 * [FragmentSchemaScanner] passes `""` through to mean "derive a name". Whitespace carries no such meaning
 * and is always a mistake, but it fails silently rather than loudly: Cypher's backtick quoting will happily
 * create an index literally named `` ` ` ``, which then never matches the derived name the query-side
 * resolvers search for. Rejecting it at construction keeps the create side and the query side from
 * disagreeing, instead of requiring two normalization rules to stay in sync.
 */
internal fun requireNameNotWhitespace(name: String?, specType: String) {
    require(name == null || name.isEmpty() || name.isNotBlank()) {
        "$specType name must not be whitespace-only (got \"$name\"). " +
            "Use null or \"\" to derive a name from the label and properties."
    }
}

/**
 * The item as it is made: one that ignores case is made over the lowercased copies of its properties
 * (see [org.drivine.model.LowerCopies]), under its own name; any other as it is declared.
 */
internal fun SchemaItemSpec.made(): SchemaItemSpec = when (this) {
    is RangeIndexSpec -> made()
    is TextIndexSpec -> made()
    is UniquenessConstraintSpec -> made()
    else -> this
}

/** [made], for an index. */
internal fun IndexSpec.made(): IndexSpec = (this as SchemaItemSpec).made() as IndexSpec

/** [made], for a constraint. */
internal fun ConstraintSpec.made(): ConstraintSpec = (this as SchemaItemSpec).made() as ConstraintSpec

/** The properties whose lowercased copies the item is made over; none for one that does not ignore case. */
internal val SchemaItemSpec.lowercased: List<String>
    get() = when (this) {
        is RangeIndexSpec -> properties.takeIf { ignoreCase }
        is TextIndexSpec -> properties.takeIf { ignoreCase }
        is UniquenessConstraintSpec -> properties.takeIf { ignoreCase }
        else -> null
    }.orEmpty()
