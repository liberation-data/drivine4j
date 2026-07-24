package org.drivine.schema

/**
 * A full-text (inverted / tokenized) index over one or more properties.
 *
 * Unlike [VectorIndexSpec] (single property) this is multi-property like [RangeIndexSpec]: a
 * fulltext index over `[title, body]` searches both properties as one analyzed field.
 *
 * @param label node label, e.g. `"Chunk"`
 * @param properties one or more properties to index for text search
 * @param name explicit index name; null derives `"${label}_${properties.joinToString("_")}_fulltext"`.
 *   Honoured on Neo4j and Memgraph; ignored on FalkorDB, whose fulltext indexes are unnamed.
 * @param analyzer engine analyzer/tokenizer name. Only Neo4j both accepts and reports one back, so
 *   it is only ever emitted and drift-checked there — see the fulltext handoff doc.
 */
data class FullTextIndexSpec(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
    val analyzer: String? = null,
) : IndexSpec {

    /** Single-property convenience constructor. */
    constructor(label: String, property: String, name: String? = null, analyzer: String? = null) :
        this(label, listOf(property), name, analyzer)

    init {
        require(properties.isNotEmpty()) { "FullTextIndexSpec requires at least one property" }
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.FULLTEXT_INDEX

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_fulltext"
}