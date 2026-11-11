package org.drivine.mapper

import org.drivine.cypher.sql.ColumnKind
import org.drivine.cypher.sql.ResultColumn
import org.drivine.query.GraphNode
import org.drivine.query.GraphRelationship
import org.drivine.query.QueryResult

/**
 * The rows of a Cypher statement compiled typed for a SQL store, as [QueryResult] gives them. The compiler gives a row as
 * one value, a list of the columns' values where there are several, and names the columns and their kinds itself
 * ([ResultColumn]). In a column of a node, a relationship or a list of them ([ColumnKind.tagged]), each is tagged
 * `{"$t": "node", "labels": […], "properties": {…}}` or `{"$t": "relationship", "type": …, "properties": {…}}`, and read
 * here as a [GraphNode] or a [GraphRelationship]. Every other column is read as `query` reads it: a map there with a `$t`
 * key of its own is a map.
 */
internal object JdbcTypedValues {

    /** The result of a statement whose columns are [columns] and whose rows' values, as JDBC reads them, are [values]. */
    fun result(columns: List<ResultColumn>, values: List<Any?>): QueryResult {
        // A statement that returns nothing names no column, and gives no row of its own.
        if (columns.isEmpty()) return QueryResult(emptyList(), emptyList())
        val rows = values.map { value ->
            val items = if (columns.size == 1) listOf(value) else value as List<*>
            check(items.size == columns.size) { "A compiled row holds ${items.size} values for ${columns.size} columns." }
            columns.zip(items).map { (column, item) -> if (column.kind.tagged) tagged(item) else item }
        }
        return QueryResult(columns.map { it.name }, rows)
    }

    /** [value], a node, a relationship or a list of them as the compiler tags them, or null. */
    private fun tagged(value: Any?): Any? = when (value) {
        null -> null
        is List<*> -> value.map(::tagged)
        is Map<*, *> -> when (value[TAG]) {
            "node" -> GraphNode((value["labels"] as List<*>).map { it as String }, properties(value))
            "relationship" -> GraphRelationship(value["type"] as String, properties(value))
            else -> throw IllegalStateException("A compiled node or relationship is tagged ${value[TAG]}.")
        }
        else -> throw IllegalStateException("A compiled node or relationship is a ${value::class.simpleName}.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun properties(tagged: Map<*, *>): Map<String, Any?> = (tagged["properties"] as Map<String, Any?>?).orEmpty()

    private const val TAG = "\$t"
}
