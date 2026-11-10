package org.drivine.cli

import org.drivine.cypher.CypherReader
import org.drivine.cypher.ast.Clause

/**
 * A statement's columns, by name, and its rows cut into them.
 *
 * [org.drivine.manager.PersistenceManager.query] gives each row as one value where the statement returns one column,
 * and as the list of its values where it returns more, without the columns' names, and a result with no rows gives
 * nothing to name them by. So the names are read from the statement, with the reader the Cypher compiler parses it
 * with: a `RETURN`'s items, each named by its alias or by its text, as every store names them. This reads the statement
 * a second time: once the library gives a result's columns, they come from there.
 */
internal object Columns {

    /** What a column is called where the statement can't be read for its names, as one Neo4j runs and Drivine can't read. */
    const val UNNAMED = "?"

    /**
     * The columns of [cypher]'s last `RETURN`, an empty list for a statement that returns nothing, such as a write with
     * no `RETURN`, or null where they can't be read: a statement Drivine's reader can't read, `RETURN *`, or one that
     * ends in a call.
     */
    fun of(cypher: String): List<String>? {
        val query = try {
            CypherReader.read(cypher)
        } catch (e: Exception) {
            return null
        }
        return when (val last = query.clauses.lastOrNull()) {
            is Clause.Return -> if (last.projection.star) null else last.projection.items.map { it.name }
            is Clause.Call -> null
            null -> null
            else -> emptyList()
        }
    }

    /** The columns of rows whose names can't be read: one, named [UNNAMED], where there are rows. */
    fun guessed(rows: List<Any?>): List<String> = if (rows.isEmpty()) emptyList() else listOf(UNNAMED)

    /** [row], as the manager gives it, as the values of its [width] columns. */
    fun split(row: Any?, width: Int): List<Any?> = when {
        width == 1 -> listOf(row)
        row is List<*> && row.size == width -> row
        else -> listOf(row)
    }
}
