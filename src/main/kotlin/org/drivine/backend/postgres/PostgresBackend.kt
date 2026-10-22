package org.drivine.backend.postgres

import org.drivine.backend.sql.SqlBackend
import org.drivine.query.dsl.CollectionSortSpec
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.backend.GraphStatement
import org.drivine.backend.sql.under
import org.drivine.manager.PersistenceManager
import org.drivine.backend.sql.TextQuery
import org.drivine.query.FullTextIndexResolver
import org.drivine.query.VectorIndexResolver
import org.drivine.schema.SchemaItemKind
import org.drivine.query.QuerySpecification
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.dsl.GraphQuerySpec
import java.sql.SQLException

/**
 * The backend of Postgres. A graph is two tables (see [PostgresGraph]), read with joins over them (see
 * [PostgresSelect]) and written by a plan of steps that a function the store keeps carries out (see
 * [PostgresWriteFunctions]): the plan is the one Oracle's block carries out.
 *
 * Everything an object manager asks of a store is here.
 */
internal class PostgresBackend(private val persistenceManager: PersistenceManager, objectMapper: ObjectMapper) : SqlBackend<PostgresSelect>(objectMapper) {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = PostgresGraph.ensured(persistenceManager)

    /** How a plan of steps is carried out: by the graph's write function. */
    override val blocks = PostgresWriteBlocks(graph.writes)

    /** A partial index over the nodes of a label is the planner's to choose, so [indexed] changes nothing here. */
    override fun select(graphClass: Class<*>, collectionSorts: List<CollectionSortSpec>, depthOverrides: Map<String, Int>, indexed: Boolean): PostgresSelect =
        PostgresSelect(graph, graphClass, collectionSorts, depthOverrides)

    override fun key(id: Any): String = PostgresSql.key(id)

    // ----- Searching -----

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan {
        val select = select(graphClass)
        val index = VectorIndexResolver.resolve(select.rootFragment, property, "_", "_", partitionLabel = partitionLabel)
        // What the search measures is the nearest searchK, and of what is left of them topK come back.
        // Through the index for the label when one has been made for embeddings of the vector's size; else by measuring every node.
        val dimensions = made(SchemaItemKind.VECTOR_INDEX, index.label, listOf(index.property))?.get("dimensions")?.let { (it as Number).toInt() }
        val candidates = if (dimensions == vector.size) {
            select.nearestThrough(index.label, index.property, dimensions, index.similarity, vector, searchK ?: topK)
        } else {
            select.nearestTo(index.property, index.similarity, partitionLabel, vector, searchK ?: topK)
        }
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK.takeIf { searchK != null })
        return ScoredSearchPlan(statement.statement, statement.bindings, topK, searchK ?: topK)
    }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan {
        val select = select(graphClass)
        val index = FullTextIndexResolver.resolve(select.rootFragment, property, "_", "_")
        val indexed = made(SchemaItemKind.FULLTEXT_INDEX, index.label, index.properties) != null
        val candidates = select.matching(index.properties, TextQuery.parse(text), indexed)
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK)
        return ScoredSearchPlan(statement.statement, statement.bindings)
    }

    /** The details of a recorded index of [kind] on [label]'s [properties] that is there; null when it has not been made. */
    private fun made(kind: SchemaItemKind, label: String, properties: List<String>): Map<*, *>? = persistenceManager.query(
        QuerySpecification
            .withStatement(
                "SELECT details FROM ${graph.catalog} WHERE kind = \$kind AND label = \$label AND properties = \$properties " +
                    "AND object_name IS NOT NULL AND to_regclass(object_name) IS NOT NULL"
            )
            .bind(mapOf("kind" to kind.name, "label" to label, "properties" to PostgresSchemaGrammar.json(properties)))
            .transform(Map::class.java)
    ).firstOrNull()

    // ----- Writing -----

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${PostgresSql.property("n", root.idProperty)} = CAST(\$id AS text)", PostgresSql.hasLabels("n", root.labelNames)).joinToString(" AND "),
        mapOf("id" to PostgresSql.key(root.id)),
    )

    /**
     * A serialization failure (`40001`) or a deadlock (`40P01`): another writer held a row this statement
     * wanted. A lock that waits does not time out unless the session says so, so a wait is not among them.
     * The failure itself is asked, as on Oracle: the save's executor walks its causes.
     */
    override fun contended(failure: Throwable): Boolean = failure is SQLException && failure.sqlState in CONTENTION

    // ----- Deleting -----

    /**
     * Deletes the roots [predicates] select, whether or not they have the relationships the view
     * requires, as the Cypher delete does, and counts them. A node's relationships go with it: the
     * relationship table cascades.
     */
    override fun deleting(select: PostgresSelect, predicates: List<String>): GraphStatement =
        GraphStatement(
            "WITH deleted AS (\n    DELETE FROM ${graph.nodes} ${select.root}\n    WHERE ${select.selecting(predicates).replace("\n", "\n    ")}\n    RETURNING 1)\nSELECT COUNT(*) FROM deleted",
            select.bindings(),
        )

    /**
     * Deletes the roots [predicates] select, and of the nodes [cascaded] reaches from them either every
     * one, or with [orphansOnly] those not kept, in one statement, and counts them. A node is kept when a
     * node outside the cascade is related to it, or when it is one step of a relationship the cascade
     * follows below a kept one: a kept message keeps its attachments, and its session is still deleted.
     * What is kept is read before anything is deleted, so a node's relationships to the others the
     * cascade takes do not keep it.
     */
    override fun cascading(select: PostgresSelect, predicates: List<String>, cascaded: String, orphansOnly: Boolean): GraphStatement {
        val roots = "cascade_roots AS (\n    ${select.roots(predicates).under()})"
        val everything = "cascade_all AS (\n    SELECT nid FROM cascade_roots\n    UNION\n    ${cascaded.under()})"
        val text = if (orphansOnly) {
            // The far end of the edge e from the kept node k.
            val far = "CASE WHEN e.src = k.nid THEN e.dst ELSE e.src END"
            val holds = select.cascadeSteps().joinToString("\n       OR ") { step ->
                val type = PostgresSql.literal(step.type)
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
                "),",
                "deleted AS (",
                "    DELETE FROM ${graph.nodes}",
                "    WHERE nid IN (SELECT nid FROM cascade_roots)",
                "       OR nid IN (SELECT nid FROM cascade_targets WHERE nid NOT IN (SELECT nid FROM cascade_kept))",
                "    RETURNING 1)",
                "SELECT COUNT(*) FROM deleted",
            )
        } else {
            listOf(
                "WITH $roots,",
                "$everything,",
                "deleted AS (DELETE FROM ${graph.nodes} WHERE nid IN (SELECT nid FROM cascade_all) RETURNING 1)",
                "SELECT COUNT(*) FROM deleted",
            )
        }.joinToString("\n")
        return GraphStatement(text, select.bindings())
    }

    private companion object {
        /** The states of a serialization failure and of a deadlock. */
        val CONTENTION = setOf("40001", "40P01")
    }
}
