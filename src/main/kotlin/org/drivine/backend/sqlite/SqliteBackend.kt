package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.backend.GraphStatement
import org.drivine.backend.sql.SqlBackend
import org.drivine.backend.sql.TextQuery
import org.drivine.backend.sql.under
import org.drivine.manager.PersistenceManager
import org.drivine.query.FullTextIndexResolver
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.VectorIndexResolver
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GraphQuerySpec
import java.sql.SQLException

/**
 * The backend of SQLite. A graph is the two tables Oracle and Postgres keep (see [SqliteGraph]), read
 * with joins over them (see [SqliteSelect]) and written by the plan of steps the other stores carry
 * out, which a function Drivine registers on each connection carries out here (see
 * [SqliteWriteFunction]).
 *
 * A search measures every node it may find: SQLite has no vector index, and a full-text index is not
 * made yet. Everything an object manager asks of a store is here.
 */
internal class SqliteBackend(persistenceManager: PersistenceManager, objectMapper: ObjectMapper) : SqlBackend<SqliteSelect>(objectMapper) {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = SqliteGraph.ensured(persistenceManager)

    /** How a plan of steps is carried out: by the write function of the connection. */
    override val blocks = SqliteWriteBlocks(graph)

    /** A partial index over the nodes of a label is the planner's to choose, so [indexed] changes nothing here. */
    override fun select(graphClass: Class<*>, collectionSorts: List<CollectionSortSpec>, depthOverrides: Map<String, Int>, indexed: Boolean): SqliteSelect =
        SqliteSelect(graph, graphClass, collectionSorts, depthOverrides)

    override fun key(id: Any): String = SqliteSql.key(id)

    // ----- Searching -----

    /** What the search measures is the nearest searchK, every node of the root's labels being measured, and of what is left of them topK come back. */
    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan {
        val select = select(graphClass)
        val index = VectorIndexResolver.resolve(select.rootFragment, property, "_", "_", partitionLabel = partitionLabel)
        val candidates = select.nearestTo(index.property, index.similarity, partitionLabel, vector, searchK ?: topK)
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK.takeIf { searchK != null })
        return ScoredSearchPlan(statement.statement, statement.bindings, topK, searchK ?: topK)
    }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan {
        val select = select(graphClass)
        val index = FullTextIndexResolver.resolve(select.rootFragment, property, "_", "_")
        val candidates = select.matching(index.properties, TextQuery.parse(text))
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK)
        return ScoredSearchPlan(statement.statement, statement.bindings)
    }

    // ----- Writing -----

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${SqliteSql.property("n", root.idProperty)} = CAST(\$id AS TEXT)", SqliteSql.hasLabels("n", root.labelNames)).joinToString(" AND "),
        mapOf("id" to SqliteSql.key(root.id)),
    )

    /**
     * The file was locked by another connection for longer than the connection waits (`SQLITE_BUSY`), or
     * a table by another statement (`SQLITE_LOCKED`); or a statement outside a transaction read before
     * another connection wrote, and may not write over it. Either is told by the driver's result code,
     * or by the message SQLite gives, which is all that is left of it where the write function was
     * turned away: an error in a function reaches the statement as its message.
     */
    override fun contended(failure: Throwable): Boolean {
        if (failure !is SQLException) return false
        val code = (failure as? org.sqlite.SQLiteException)?.resultCode?.code?.and(0xff)
        if (code == SQLITE_BUSY || code == SQLITE_LOCKED) return true
        val message = failure.message.orEmpty()
        return CONTENTION.any { it in message }
    }

    // ----- Deleting -----

    /**
     * Deletes the roots [predicates] select, whether or not they have the relationships the view
     * requires, as the Cypher delete does, and counts them. SQLite has no delete that a query reads, so
     * the roots are selected first and deleted by the function [SqliteDeleteFunction] registers.
     */
    override fun deleting(select: SqliteSelect, predicates: List<String>): GraphStatement =
        GraphStatement(deleted(select.roots(predicates)), select.bindings())

    /**
     * Deletes the roots [predicates] select, and of the nodes [cascaded] reaches from them either every
     * one, or with [orphansOnly] those not kept, in one statement, and counts them. A node is kept when a
     * node outside the cascade is related to it, or when it is one step of a relationship the cascade
     * follows below a kept one: a kept message keeps its attachments, and its session is still deleted.
     * What is kept is read before anything is deleted, so a node's relationships to the others the
     * cascade takes do not keep it.
     */
    override fun cascading(select: SqliteSelect, predicates: List<String>, cascaded: String, orphansOnly: Boolean): GraphStatement {
        val roots = "cascade_roots AS (\n    ${select.roots(predicates).under()})"
        val everything = "cascade_all AS (\n    SELECT nid FROM cascade_roots\n    UNION\n    ${cascaded.under()})"
        val text = if (orphansOnly) {
            // The far end of the edge e from the kept node k.
            val far = "CASE WHEN e.src = k.nid THEN e.dst ELSE e.src END"
            val holds = select.cascadeSteps().joinToString("\n       OR ") { step ->
                val type = SqliteSql.literal(step.type)
                when (step.direction) {
                    Direction.OUTGOING -> "(e.type = $type AND e.src = k.nid)"
                    Direction.INCOMING -> "(e.type = $type AND e.dst = k.nid)"
                    Direction.UNDIRECTED -> "(e.type = $type AND (e.src = k.nid OR e.dst = k.nid))"
                }
            }
            listOf(
                "WITH RECURSIVE $roots,",
                "$everything,",
                "cascade_targets AS (SELECT nid FROM cascade_all WHERE nid NOT IN (SELECT nid FROM cascade_roots)),",
                "cascade_kept (nid) AS (",
                "    SELECT c.nid FROM cascade_targets c",
                "    WHERE EXISTS (",
                "        SELECT 1 FROM ${graph.edges} e",
                "        WHERE (e.src = c.nid AND e.dst NOT IN (SELECT nid FROM cascade_all))",
                "           OR (e.dst = c.nid AND e.src NOT IN (SELECT nid FROM cascade_all)))",
                "    UNION",
                "    SELECT $far",
                "    FROM cascade_kept k JOIN ${graph.edges} e ON ${holds.under()}",
                "    WHERE $far IN (SELECT nid FROM cascade_targets)",
                ")",
                deleted(
                    "SELECT nid FROM cascade_roots\n" +
                        "UNION\n" +
                        "SELECT nid FROM cascade_targets WHERE nid NOT IN (SELECT nid FROM cascade_kept)",
                ),
            )
        } else {
            listOf("WITH $roots,", "$everything", deleted("SELECT nid FROM cascade_all"))
        }.joinToString("\n")
        return GraphStatement(text, select.bindings())
    }

    /** The statement that deletes the nodes whose ids [nids] selects, and counts them. */
    private fun deleted(nids: String): String =
        "SELECT ${SqliteFunctions.DELETE}(${SqliteSql.literal(graph.name)}, (SELECT json_group_array(nid) FROM (\n    ${nids.under()})))"

    private companion object {
        /** SQLite's result codes for a locked file and a locked table. */
        const val SQLITE_BUSY = 5
        const val SQLITE_LOCKED = 6

        /** What SQLite says of a lock, in a message that has lost its code. */
        val CONTENTION = listOf("[SQLITE_BUSY", "[SQLITE_LOCKED", "database is locked", "database table is locked")
    }
}
