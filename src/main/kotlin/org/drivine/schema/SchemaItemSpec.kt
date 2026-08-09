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

    /** The name used when creating the item, on engines that support names. */
    val effectiveName: String
        get() = name ?: defaultName()

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