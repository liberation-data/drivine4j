package org.drivine.backend.sqlite

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * The statements a function runs on the connection it is registered on, each prepared the first time
 * it is run and kept: a save runs the same few for every node it writes. A statement's text is written
 * for a graph and a shape, so there are few of them, and they go with the connection. The query a
 * `patch` step asks has its values written into it, so it may be new for every patch: the statements
 * kept are the most recently run, and one that falls out is closed.
 */
internal class SqliteStatements(private val connection: Connection) {

    private val prepared = object : LinkedHashMap<String, PreparedStatement>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean =
            (size > MAX).also { if (it) eldest.value.close() }
    }

    /** [sql], prepared, with [values] bound in turn: a string, a number, or null. */
    fun prepared(sql: String, vararg values: Any?): PreparedStatement {
        val statement = prepared[sql]?.takeUnless { it.isClosed } ?: connection.prepareStatement(sql).also { prepared[sql] = it }
        statement.clearParameters()
        values.forEachIndexed { i, value ->
            when (value) {
                null -> statement.setNull(i + 1, java.sql.Types.VARCHAR)
                is Long -> statement.setLong(i + 1, value)
                is Int -> statement.setLong(i + 1, value.toLong())
                else -> statement.setString(i + 1, value.toString())
            }
        }
        return statement
    }

    /** Runs [sql] with [values], and gives how many rows it changed. */
    fun update(sql: String, vararg values: Any?): Int = prepared(sql, *values).executeUpdate()

    /** Runs [sql], a statement of no values, such as a savepoint's. */
    fun run(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** The rows [sql] with [values] reads, each as [read] makes it. */
    fun <T> rows(sql: String, vararg values: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        prepared(sql, *values).executeQuery().use { results ->
            val rows = mutableListOf<T>()
            while (results.next()) rows += read(results)
            rows
        }

    private companion object {
        /** The statements a connection keeps: more than any one plan runs. */
        const val MAX = 256
    }
}
