package org.drivine.test

import org.drivine.manager.PersistenceManager
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.JdbcRow
import org.drivine.query.QuerySpecification

/**
 * Lets a test written for the graph engines run against Oracle with its fixtures as they are.
 *
 * Such a test seeds its graph, plays another writer and reads back what was stored in Cypher, through
 * a `PersistenceManager`. Oracle does not speak Cypher. [over] gives a manager that works a Cypher
 * statement out over the graph and writes back what it changed, and hands any other statement, an
 * object manager's among them, to the store.
 *
 * It reads the Cypher such fixtures use, the subset [MiniCypher] lists: `CREATE`, `MATCH` and `MERGE`
 * patterns with literal or bound properties, `WHERE`, `SET`, `REMOVE`, `DETACH DELETE`, `UNWIND`,
 * `WITH`, and a `RETURN` of an expression or a `count`; anything else is refused. It reads the whole
 * graph into memory for each statement, and is for tests alone.
 */
object OracleCypherFixtures {

    /** [pm], with a Cypher statement worked out as a fixture and every other statement left to the store. */
    @JvmStatic
    fun over(pm: PersistenceManager): PersistenceManager = object : PersistenceManager by pm {
        override fun execute(spec: QuerySpecification<*>) {
            val text = spec.statement!!.text.trim()
            when {
                text == "MATCH (n) DETACH DELETE n" -> clear(pm)
                MiniCypher.reads(text) -> MiniCypher(pm, text, spec.parameters).run()
                else -> pm.execute(spec)
            }
        }

        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            val text = spec.statement!!.text.trim()
            if (!MiniCypher.reads(text)) return pm.query(spec)
            // Each row is one value, mapped as the row of a statement the store ran would be.
            val rows = MiniCypher(pm, text, spec.parameters).run().map { JdbcRow(listOf("value"), listOf(it)) }
            return JdbcResultMapper().mapQueryResults(rows, spec)
        }

        override fun <T : Any> getOne(spec: QuerySpecification<T>): T = query(spec).single()

        override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = query(spec).singleOrNull()
    }

    /** Works [cypher] out over the graph [pm] speaks to, and gives the rows it returns. */
    @JvmStatic
    @JvmOverloads
    fun run(pm: PersistenceManager, cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        MiniCypher(pm, cypher.trim(), parameters).run()

    /** Removes every node and relationship of the graph [pm] speaks to. */
    @JvmStatic
    fun clear(pm: PersistenceManager) {
        val graph = requireNotNull(pm.graphName) { "The manager names no graph." }
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_edge"))
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_node"))
    }
}
