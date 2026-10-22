package org.drivine.backend.oracle

import org.drivine.backend.sql.SqlBackend
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.backend.sql.TextQuery
import org.drivine.backend.sql.under
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.backend.GraphStatement
import org.drivine.manager.PersistenceManager
import org.drivine.query.FullTextIndexResolver
import org.drivine.query.QuerySpecification
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.VectorIndexResolver
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.schema.SchemaItemKind
import java.sql.SQLException
import java.util.concurrent.Executor

/**
 * The backend of Oracle Database. A graph is two tables and the SQL property graph over them (see
 * [OracleGraph]): a read is SQL/PGQ, through `GRAPH_TABLE`; a write is rows of the tables, since
 * Oracle refuses data manipulation through a property graph.
 */
internal class OracleBackend(
    private val persistenceManager: PersistenceManager,
    objectMapper: ObjectMapper,
    /** Where the labelled indexes of the graph are read: see [LabelledIndexCatalog]. */
    catalogReads: Executor? = null,
) : SqlBackend<OracleSelect>(objectMapper) {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = OracleGraph.ensured(persistenceManager)

    /** The labelled indexes of the graph, which a filter on the nodes of a label is read through. */
    private val labelled = catalogReads?.let { LabelledIndexCatalog(persistenceManager, graph, refresh = it) } ?: LabelledIndexCatalog(persistenceManager, graph)

    /** How a plan of steps is carried out: by a PL/SQL block written for the shapes it names. */
    override val blocks = OracleWriteBlocks(graph)

    /** A filter through the query DSL is read through the labelled indexes of the graph. */
    override fun select(graphClass: Class<*>, collectionSorts: List<CollectionSortSpec>, depthOverrides: Map<String, Int>, indexed: Boolean): OracleSelect =
        OracleSelect(graph, graphClass, collectionSorts, depthOverrides, if (indexed) labelled.current() else LabelledIndexes.NONE)

    override fun key(id: Any): String = OracleSql.key(id)

    // ----- Searching -----

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan {
        val select = select(graphClass)
        val index = VectorIndexResolver.resolve(select.rootFragment, property, "_", "_", partitionLabel = partitionLabel)
        // What the search measures is the nearest searchK, and of what is left of them topK come back.
        // Through the index for the label when one has been made; else by measuring every node.
        val embeddings = made(SchemaItemKind.VECTOR_INDEX, index.label, listOf(index.property))
        val candidates = if (embeddings != null) {
            select.nearestThrough(embeddings, index.similarity, vector, searchK ?: topK)
        } else {
            select.nearestTo(index.property, index.similarity, partitionLabel, vector, searchK ?: topK)
        }
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK.takeIf { searchK != null })
        return ScoredSearchPlan(statement.statement, statement.bindings, topK, searchK ?: topK)
    }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan {
        val select = select(graphClass)
        val index = FullTextIndexResolver.resolve(select.rootFragment, property, "_", "_")
        val words = TextQuery.parse(text)
        val texts = made(SchemaItemKind.FULLTEXT_INDEX, index.label, index.properties)
        val candidates = if (texts != null) select.matchingThrough(texts, index.properties, words) else select.matching(index.properties, words)
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK)
        return ScoredSearchPlan(statement.statement, statement.bindings)
    }

    /** The table a recorded index of [kind] on [label]'s [properties] is over; null when the index has not been made. */
    private fun made(kind: SchemaItemKind, label: String, properties: List<String>): String? = persistenceManager.query(
        QuerySpecification
            .withStatement("SELECT object_name FROM ${graph.catalog} WHERE kind = \$kind AND label = \$label AND properties = \$properties AND object_name IS NOT NULL")
            .bind(mapOf("kind" to kind.name, "label" to label, "properties" to OracleSchemaGrammar.json(properties)))
            .transform(String::class.java)
    ).firstOrNull()

    // ----- Deleting -----

    /**
     * Deletes the roots [predicates] select, whether or not they have the relationships the view
     * requires, as the Cypher delete does. A node's relationships go with it: the relationship table cascades.
     */
    override fun deleting(select: OracleSelect, predicates: List<String>): GraphStatement =
        GraphStatement(
            OracleSql.counted("DELETE FROM ${graph.nodes} ${select.root}\nWHERE ${select.selecting(predicates)}"),
            select.bindings(),
        )

    /**
     * Deletes the roots [predicates] select, and of the nodes [cascaded] reaches from them either every
     * one, or with [orphansOnly] those not kept. A node is kept when a node outside the cascade is
     * related to it, or when it is one step of a relationship the cascade follows below a kept one:
     * a kept message keeps its attachments, and its session is still deleted. What is kept is read
     * before anything is deleted, so a node's relationships to the others the cascade takes do not
     * keep it.
     */
    override fun cascading(select: OracleSelect, predicates: List<String>, cascaded: String, orphansOnly: Boolean): GraphStatement {
        val roots = "FROM ${graph.nodes} ${select.root}\nWHERE ${select.selecting(predicates)}"
        val everything = "SELECT ${select.root}.nid\n$roots\nUNION\n$cascaded"
        // Lines joined, not a template: what is put in has lines of its own, each laid out under the one that opens it.
        val body = if (orphansOnly) {
            // The far end of the edge e from the kept node k.
            val far = "CASE WHEN e.src = k.nid THEN e.dst ELSE e.src END"
            val holds = select.cascadeSteps().joinToString("\n       OR ") { step ->
                val type = OracleSql.literal(step.type)
                when (step.direction) {
                    Direction.OUTGOING -> "(e.type = $type AND e.src = k.nid)"
                    Direction.INCOMING -> "(e.type = $type AND e.dst = k.nid)"
                    Direction.UNDIRECTED -> "(e.type = $type AND (e.src = k.nid OR e.dst = k.nid))"
                }
            }
            val orphans = listOf(
                "WITH cascade_targets AS (",
                "    SELECT c.nid FROM (\n        ${cascaded.under().under()}) c",
                "    WHERE c.nid NOT IN (\n        ${"SELECT ${select.root}.nid\n$roots".under().under()})),",
                "cascade_all AS (\n    ${everything.under()}),",
                "cascade_kept (nid) AS (",
                "    SELECT c.nid FROM cascade_targets c",
                "    WHERE EXISTS (",
                "        SELECT 1 FROM ${graph.edges} e",
                "        WHERE (e.src = c.nid AND e.dst NOT IN (SELECT nid FROM cascade_all))",
                "           OR (e.dst = c.nid AND e.src NOT IN (SELECT nid FROM cascade_all)))",
                "    UNION ALL",
                "    SELECT $far",
                "    FROM cascade_kept k JOIN ${graph.edges} e ON ${holds.under()}",
                "    WHERE $far IN (SELECT nid FROM cascade_targets)",
                ") CYCLE nid SET looped TO 'Y' DEFAULT 'N'",
            ).joinToString("\n")
            listOf(
                "$orphans\nSELECT nid BULK COLLECT INTO v_ids FROM cascade_targets WHERE nid NOT IN (SELECT nid FROM cascade_kept);",
                "DELETE $roots;",
                "v_count := SQL%ROWCOUNT;",
                "FORALL i IN 1 .. v_ids.COUNT",
                "    DELETE FROM ${graph.nodes} WHERE nid = v_ids(i);",
                "v_count := v_count + v_ids.COUNT;",
            )
        } else {
            listOf(
                "SELECT nid BULK COLLECT INTO v_ids FROM (\n    ${everything.under()});",
                "FORALL i IN 1 .. v_ids.COUNT",
                "    DELETE FROM ${graph.nodes} WHERE nid = v_ids(i);",
                "v_count := v_ids.COUNT;",
            )
        }.joinToString("\n")
        return GraphStatement(OracleSql.counting(body, "TYPE t_ids IS TABLE OF NUMBER;\nv_ids t_ids;"), select.bindings())
    }

    /** The query a `patch` step asks has parameters of its own, bound beside the plan. */
    override fun patchedIfBindings(select: OracleSelect): Map<String, Any?> = select.bindings()

    // ----- Writing -----

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${OracleSql.property("n", root.idProperty)} = \$id", OracleSql.hasLabels("n", root.labelNames)).joinToString(" AND "),
        mapOf("id" to OracleSql.key(root.id)),
    )

    /**
     * A deadlock (`ORA-00060`) or a serialization failure (`ORA-08177`): another writer held a row
     * this statement wanted. Every lock a block takes waits, so a wait that ran out is not among them.
     */
    override fun contended(failure: Throwable): Boolean = failure is SQLException && failure.errorCode in CONTENTION

    private companion object {
        /** The error codes of a deadlock and of a serialization failure. */
        val CONTENTION = setOf(60, 8177)
    }
}
