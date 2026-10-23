package org.drivine.schema

import org.drivine.model.LowerCopies

/**
 * Neo4j's TEXT index over a single string property: `CREATE TEXT INDEX … FOR (n:Label) ON (n.prop)`.
 *
 * It serves `CONTAINS`, `STARTS WITH` and `ENDS WITH` on that property. It is not the analyzed,
 * multi-property index that [FullTextIndexSpec] declares, and it is not Memgraph's `TEXT INDEX`,
 * which is a full-text index and so is what [FullTextIndexSpec] makes there.
 *
 * Only Neo4j has one. On every other engine [IndexManager] refuses the spec with a
 * [org.drivine.DrivineException] rather than make some other index in its place (see
 * [SchemaGrammar.supportsTextIndex]).
 *
 * @param label node label, e.g. `"Chunk"`
 * @param property the string property, e.g. `"title"`
 * @param name explicit index name; null derives `"${label}_${property}_text"`
 * @param ignoreCase whether the index is over the lowercased copy that a `@CaseInsensitive` field
 *   keeps (see [LowerCopies]), so that it serves `containsIgnoreCase`; ensuring it fills the copies in,
 *   as a [RangeIndexSpec] that ignores case does
 */
data class TextIndexSpec @JvmOverloads constructor(
    override val label: String,
    val property: String,
    override val name: String? = null,
    val ignoreCase: Boolean = false,
) : IndexSpec {

    init {
        requireNameNotWhitespace(name, "TextIndexSpec")
    }

    override val properties: List<String>
        get() = listOf(property)

    override val kind: SchemaItemKind
        get() = SchemaItemKind.TEXT_INDEX

    override fun defaultName(): String = "${label}_${property}_${if (ignoreCase) "ci_" else ""}text"

    override val inventoryKey: String
        get() = if (ignoreCase) made().inventoryKey else super.inventoryKey

    /** The index as it is made: over the copy, when it ignores case, and named as this one is. */
    internal fun made(): TextIndexSpec = if (ignoreCase) TextIndexSpec(label, LowerCopies.of(property), effectiveName) else this
}
