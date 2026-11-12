package org.drivine.store

import org.drivine.connection.StoreLayoutException
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sql.SqlGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The store layout's verdict on a graph, on each SQL store (liberation-data/drivine-cypher-spec#161), as a manager's first
 * connection takes it. A clear, `MATCH (n) DETACH DELETE n`, deletes Drivine's own nodes too, the store stamp among them, as
 * on a graph engine: a graph of this layout cleared and loaded again is stamped again on the next manager's first
 * connection, and used, not refused as one an earlier Drivine wrote.
 */
abstract class StoreLayoutContract {

    /** A new manager of [graph], over a provider of its own, so its first connection asks the verdict. */
    abstract fun manager(graph: String): NonTransactionalPersistenceManager

    /** The store's graph of [name], for its stamp's statements. */
    internal abstract fun graph(name: String): SqlGraph

    /** Drops [graph]'s tables, so a test starts from none. */
    abstract fun drop(graph: String)

    private fun cypher(pm: NonTransactionalPersistenceManager, statement: String): List<Any> =
        pm.query(QuerySpecification.withStatement(CypherStatement(statement)))

    private fun stamps(pm: NonTransactionalPersistenceManager, graph: String): List<Any> =
        pm.query(QuerySpecification.withStatement(StoreStampStatements.of(graph(graph).store).readStamps))

    @Test
    fun `a graph cleared and loaded again is stamped again on the next first connection, and used`() {
        val name = "cy_clear_reload"
        drop(name)
        val first = manager(name)
        cypher(first, "CREATE (:Person {name: 'Ada'})")
        // The clear deletes the stamp with every node, and the values written after it have none.
        cypher(first, "MATCH (n) DETACH DELETE n")
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        assertEquals(0, stamps(first, name).size, "the clear deleted the stamp")
        val next = manager(name)
        assertEquals(listOf<Any>("Grace"), cypher(next, "MATCH (p:Person) RETURN p.name"))
        val stamped = stamps(next, name).single() as Map<*, *>
        assertEquals(2L, (stamped["layout"] as Number).toLong(), "stamped again in this layout: $stamped")
    }

    @Test
    fun `a graph that holds values and no stamp, and none of this layout's objects, is refused`() {
        val name = "cy_layout_old"
        drop(name)
        val first = manager(name)
        cypher(first, "CREATE (:Person {name: 'Ada'})")
        cypher(first, "MATCH (n) DETACH DELETE n")
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        // As an earlier Drivine leaves a graph: no lock table, which only this layout makes, and no stamp.
        first.execute(QuerySpecification.withStatement("DROP TABLE ${graph(name).store.lock}"))
        val refused = assertFailsWith<StoreLayoutException> { cypher(manager(name), "MATCH (p:Person) RETURN p.name") }
        assertEquals(name, refused.graph)
    }
}

@PostgresTests
class PostgresStoreLayoutTest : StoreLayoutContract() {
    override fun manager(graph: String) = PostgresTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = PostgresGraph(name)
    override fun drop(graph: String) {
        val store = PostgresGraph(graph).store
        PostgresTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}, ${store.nodes}, ${store.catalog}, ${store.lock} CASCADE")
    }
}

@OracleTests
class OracleStoreLayoutTest : StoreLayoutContract() {
    override fun manager(graph: String) = OracleTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = OracleGraph(name)
    override fun drop(graph: String) {
        val store = OracleGraph(graph).store
        OracleTestDatabase.drop("TABLE ${store.edges}", "TABLE ${store.nodes}", "TABLE ${store.catalog}", "TABLE ${store.lock}")
    }
}

class SqliteStoreLayoutTest : StoreLayoutContract() {
    override fun manager(graph: String) = SqliteTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = SqliteGraph(name)
    override fun drop(graph: String) {
        val store = SqliteGraph(graph).store
        SqliteTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}", "DROP TABLE IF EXISTS ${store.nodes}", "DROP TABLE IF EXISTS ${store.catalog}", "DROP TABLE IF EXISTS ${store.lock}")
    }
}
