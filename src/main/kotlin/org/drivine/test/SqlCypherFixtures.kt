package org.drivine.test

import org.drivine.manager.PersistenceManager
import org.drivine.query.CypherStatement
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import java.util.Optional

/**
 * Lets a test written for the graph engines run against a SQL store, Oracle, Postgres or SQLite, with its
 * fixtures as they are.
 *
 * Such a test seeds its graph, plays another writer and reads back what was stored in Cypher, through
 * a `PersistenceManager`, with statements of no language: a SQL store takes those for SQL. [over] gives
 * a manager that sends such a statement that is Cypher to the store as Cypher, which compiles it to its
 * SQL, as it compiles any Cypher statement; any other statement, an object manager's among them, goes
 * as it is. A statement is told for Cypher by what it opens with: a pattern (`MATCH`, `OPTIONAL MATCH`,
 * `CREATE`, `MERGE`), named as a path or not, `UNWIND`, `RETURN`, or a `//` comment, which SQL has none
 * of. One that opens with `WITH` is taken for a common table expression, and is SQL.
 */
object SqlCypherFixtures {

    /** [pm], with a statement of no language that is Cypher sent as Cypher, and every other statement as it is. */
    @JvmStatic
    fun over(pm: PersistenceManager): PersistenceManager = object : PersistenceManager by pm {
        override fun execute(spec: QuerySpecification<*>) {
            if (spec.statement!!.text.trim() == CLEAR) clear(pm) else pm.execute(cypherOf(spec))
        }

        override fun executeBatch(specs: List<QuerySpecification<*>>) = pm.executeBatch(specs.map(::cypherOf))

        override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> = pm.queryBatch(specs.map(::cypherOf))

        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = pm.query(asCypher(spec))

        override fun <T : Any> getOne(spec: QuerySpecification<T>): T = pm.getOne(asCypher(spec))

        override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = pm.maybeGetOne(asCypher(spec))

        override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): Optional<T> = pm.optionalGetOne(asCypher(spec))
    }

    /**
     * [pm] as a test that seeds and reads back in Cypher wants it, whatever store it speaks to: [over]
     * it where the store speaks SQL, and [pm] itself where it speaks Cypher. So one test, or one bean of
     * a test's context, serves the graph engines, Oracle, Postgres and SQLite alike.
     */
    @JvmStatic
    fun forStore(pm: PersistenceManager): PersistenceManager = if (pm.type.speaksCypher) pm else over(pm)

    /** Runs the Cypher statement [cypher] on the graph [pm] speaks to, and gives the rows it returns. */
    @JvmStatic
    @JvmOverloads
    fun run(pm: PersistenceManager, cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trim())).bind(parameters))

    /** Removes every node and relationship of the graph [pm] speaks to. */
    @JvmStatic
    fun clear(pm: PersistenceManager) {
        val graph = requireNotNull(pm.graphName) { "The manager names no graph." }
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_edge"))
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_node"))
    }

    /** [spec], of whatever it maps its rows to, with its statement sent as Cypher: see [asCypher]. */
    @Suppress("UNCHECKED_CAST")
    private fun cypherOf(spec: QuerySpecification<*>): QuerySpecification<*> = asCypher(spec as QuerySpecification<Any>)

    /** [spec] with its statement sent as Cypher, where it has no language and is Cypher. */
    internal fun <T : Any> asCypher(spec: QuerySpecification<T>): QuerySpecification<T> {
        val statement = spec.statement!!
        return if (statement.language == QueryLanguage.PLATFORM_DEFAULT && CYPHER.containsMatchIn(statement.text)) spec.finalizedCopy(QueryLanguage.CYPHER) else spec
    }

    /** The statement that clears the graph, which is cleared the quicker way. */
    private const val CLEAR = "MATCH (n) DETACH DELETE n"

    /**
     * A statement that opens with a pattern, named as a path (`MATCH p = (…)`) or not, `UNWIND`, `RETURN`, or
     * a `//` comment, which SQL has none of: `CREATE INDEX` is the store's own, and so is one that opens with
     * `WITH`, which SQL names a common table expression by.
     */
    private val CYPHER = Regex("^\\s*(?://|(?:OPTIONAL\\s+MATCH|MATCH|CREATE|MERGE)\\s*(?:\\w+\\s*=\\s*)?\\(|UNWIND\\s|RETURN\\s)", RegexOption.IGNORE_CASE)
}
