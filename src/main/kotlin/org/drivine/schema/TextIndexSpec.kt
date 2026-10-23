package org.drivine.schema

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
 */
data class TextIndexSpec @JvmOverloads constructor(
    override val label: String,
    val property: String,
    override val name: String? = null,
) : IndexSpec {

    init {
        requireNameNotWhitespace(name, "TextIndexSpec")
    }

    override val properties: List<String>
        get() = listOf(property)

    override val kind: SchemaItemKind
        get() = SchemaItemKind.TEXT_INDEX

    override fun defaultName(): String = "${label}_${property}_text"
}
