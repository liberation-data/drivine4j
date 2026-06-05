package org.drivine.schema

/**
 * Normalized description of a schema item that exists on a database, as discovered by
 * introspection ([SchemaGrammar.parseIndexRow] / [SchemaGrammar.parseConstraintRow]).
 *
 * @param kind what kind of item this is
 * @param label the node label the item applies to
 * @param properties the properties the item covers
 * @param name the item's name, or null on engines without named items (FalkorDB)
 * @param dimensions vector indexes only: dimensionality
 * @param similarity vector indexes only: similarity function (normalized from engine vocabulary)
 * @param status engine-reported status where applicable (e.g. FalkorDB constraints:
 *   `OPERATIONAL` / `UNDER CONSTRUCTION` / `FAILED`)
 */
data class SchemaItemInfo(
    val kind: SchemaItemKind,
    val label: String,
    val properties: List<String>,
    val name: String? = null,
    val dimensions: Int? = null,
    val similarity: SimilarityFunction? = null,
    val status: String? = null,
) {

    companion object {

        /**
         * Synthesizes info from a spec, for engines/timing windows where an item was just created
         * but cannot yet be (or doesn't need to be) re-read via introspection.
         */
        fun fromSpec(spec: SchemaItemSpec): SchemaItemInfo = when (spec) {
            is VectorIndexSpec -> SchemaItemInfo(
                kind = spec.kind,
                label = spec.label,
                properties = spec.properties,
                name = spec.name,
                dimensions = spec.dimensions,
                similarity = spec.similarity,
            )

            is RangeIndexSpec, is UniquenessConstraintSpec -> SchemaItemInfo(
                kind = spec.kind,
                label = spec.label,
                properties = spec.properties,
                name = spec.name,
            )
        }
    }
}