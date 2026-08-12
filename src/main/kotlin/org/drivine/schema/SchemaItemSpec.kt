package org.drivine.schema

/**
 * A declarative description of a schema item (index or constraint) that Drivine should ensure
 * exists on a graph database.
 *
 * Specs are pure data — they carry no engine-specific syntax. The per-engine [SchemaGrammar]
 * translates a spec into DDL, and [SchemaItemInfo] describes what actually exists.
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