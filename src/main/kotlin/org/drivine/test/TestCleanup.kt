package org.drivine.test

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSql
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.quotedIdentifier

/**
 * Test cleanup utility that adapts to the database backend.
 *
 * - **Neo4j**: deletes only nodes matching the `createdBy` tag (relies on `@Rollback` for full isolation)
 * - **FalkorDB**: nukes all nodes (`@Rollback` is a no-op, so full cleanup is needed)
 * - **Oracle**: deletes the nodes of the manager's graph that carry the tag, in SQL; their
 *   relationships go with them
 *
 * [resetGraph] empties a whole graph, its schema too, for a class that shares a database with others.
 */
object TestCleanup {

    /**
     * Removes what an earlier run of a test left: the nodes whose `createdBy` property is [createdBy],
     * or on FalkorDB every node. The value is written into the statement as it is, so it is a test's
     * own constant and never input.
     */
    fun beforeEach(persistenceManager: PersistenceManager, createdBy: String) = beforeEach(persistenceManager, "createdBy", createdBy)

    /** As the other [beforeEach], for tests that tag their nodes under [markerField] with [markerValue]. Both are written into the statement as they are. */
    fun beforeEach(persistenceManager: PersistenceManager, markerField: String, markerValue: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, markerField, markerValue)
            DatabaseType.POSTGRES -> postgres(persistenceManager, markerField, markerValue)
            DatabaseType.SQLITE -> sqlite(persistenceManager, markerField, markerValue)
            else -> "MATCH (n) WHERE n.$markerField = '$markerValue' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    /**
     * Empties the graph [persistenceManager] speaks to of everything a test can leave in it: every
     * node and relationship, every constraint, and every index but Neo4j's own token lookups. A test
     * class that shares one database with others calls it before it starts, so no index or
     * constraint another class made changes what it sees.
     *
     * - **Neo4j**: constraints go first, since a uniqueness constraint owns its index, which can't be
     *   dropped while the constraint holds it. Then each index by name, then the nodes.
     *   `apoc.schema.assert` is not used: it leaves vector indexes, and APOC need not be installed.
     * - **Memgraph**: `DROP GRAPH` takes the data and every kind of index, but runs only in analytical
     *   storage mode, which Memgraph won't enter while a constraint exists. So each constraint
     *   `SHOW CONSTRAINT INFO` lists is dropped first. `schema.assert({}, {}, {}, true)` is not used:
     *   on Memgraph 3.12 one call drops one of several uniqueness constraints (memgraph/memgraph#5185),
     *   and never a composite or nested-property index (memgraph/memgraph#2462).
     *
     * Give it a manager that commits each statement on its own, not one inside a transaction: Neo4j
     * refuses a data write in a transaction that has changed the schema, and Memgraph refuses the
     * storage mode switch while a transaction is open.
     *
     * @throws UnsupportedOperationException on any other engine
     */
    fun resetGraph(persistenceManager: PersistenceManager) {
        fun run(statement: String) = persistenceManager.execute(QuerySpecification.withStatement(statement))
        when (persistenceManager.type) {
            DatabaseType.NEO4J -> {
                fun names(statement: String): List<String> =
                    persistenceManager.query(QuerySpecification.withStatement(statement).transform(String::class.java))
                names("SHOW CONSTRAINTS YIELD name RETURN name").forEach { run("DROP CONSTRAINT ${quotedIdentifier(it)}") }
                names("SHOW INDEXES YIELD name, type WHERE type <> 'LOOKUP' RETURN name").forEach { run("DROP INDEX ${quotedIdentifier(it)}") }
                run("MATCH (n) DETACH DELETE n")
            }
            DatabaseType.MEMGRAPH -> {
                persistenceManager.query(QuerySpecification.withStatement("SHOW CONSTRAINT INFO"))
                    .forEach { run(memgraphConstraintDrop(it as List<*>)) }
                run("STORAGE MODE IN_MEMORY_ANALYTICAL")
                run("DROP GRAPH")
                run("STORAGE MODE IN_MEMORY_TRANSACTIONAL")
            }
            else -> throw UnsupportedOperationException("resetGraph supports Neo4j and Memgraph, not ${persistenceManager.type}")
        }
    }

    /**
     * The drop of the constraint a `SHOW CONSTRAINT INFO` row describes: its type, label, properties
     * (a list for a uniqueness constraint, one name for the others) and, for a type constraint, the type.
     */
    private fun memgraphConstraintDrop(row: List<*>): String {
        val label = quotedIdentifier((row[1] as String).removePrefix(":"))
        val properties = when (val p = row[2]) {
            is List<*> -> p.map { "n.${quotedIdentifier(it as String)}" }
            else -> listOf("n.${quotedIdentifier(p as String)}")
        }
        val assertion = when (row[0]) {
            "unique" -> "${properties.joinToString(", ")} IS UNIQUE"
            "exists" -> "EXISTS (${properties.single()})"
            "data_type" -> "${properties.single()} IS TYPED ${row[3]}"
            else -> throw UnsupportedOperationException("A Memgraph constraint of type ${row[0]} can't be dropped by resetGraph")
        }
        return "DROP CONSTRAINT ON (n:$label) ASSERT $assertion"
    }

    /** The graph is made where it is not yet: a cleanup may run before anything else has spoken to it. */
    private fun oracle(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = OracleGraph.ensured(persistenceManager)
        return "DELETE FROM ${graph.nodes} n WHERE ${OracleSql.property("n", field)} = ${OracleSql.literal(value)}"
    }

    /** As on Oracle: the graph is made where it is not yet. */
    private fun postgres(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = PostgresGraph.ensured(persistenceManager)
        return "DELETE FROM ${graph.nodes} n WHERE ${PostgresSql.property("n", field)} = ${PostgresSql.literal(value)}"
    }

    /** As on Oracle: the graph is made where it is not yet. */
    private fun sqlite(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = SqliteGraph.ensured(persistenceManager)
        return "DELETE FROM ${graph.nodes} WHERE ${SqliteSql.property("", field)} = ${SqliteSql.literal(value)}"
    }
}