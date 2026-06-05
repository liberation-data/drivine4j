package org.drivine.schema

import org.drivine.DrivineException

/**
 * Placeholder grammar for engines without schema management support (Neptune, generic openCypher).
 *
 * Every operation fails loudly — schema management never silently no-ops or fakes a result on an
 * unsupported engine.
 */
class UnsupportedSchemaGrammar(private val dialectName: String) : SchemaGrammar {

    override val engine = dialectName
    override val supportsIfNotExists = false
    override val supportsNamedItems = false

    private fun unsupported(): Nothing = throw DrivineException(
        "Schema management (indexes/constraints) is not supported for $dialectName. " +
            "Supported engines: Neo4j, Memgraph, FalkorDB."
    )

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = unsupported()

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = unsupported()

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = unsupported()

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = unsupported()

    override fun listIndexesQuery(kind: SchemaItemKind): String = unsupported()

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = unsupported()

    override fun listConstraintsQuery(): String = unsupported()

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = unsupported()

    override fun isConstraintViolation(e: Throwable): Boolean = false
}