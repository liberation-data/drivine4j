package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.ROW_CHANGES_NODE
import org.drivine.query.Stamping
import org.drivine.query.savedByUnwind
import org.drivine.query.unwindProps
import org.drivine.query.StoredPropertyKeys
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.session.SessionManager

/**
 * Builds the ordered, atomic statement batch for [GraphObjectManager.saveAll]. Extracted from the
 * manager to keep it lean; the manager owns cascade validation, execution, and snapshotting.
 *
 * **Strategy — "UNWIND the roots, pipeline the rest".** Within each homogeneous (same runtime class)
 * group the root-fragment upserts collapse into chunked `UNWIND $rows MERGE (n:…{id}) SET n += row.props`
 * statements (sub-linear round trips); relationship and cascade statements stay per-item, built by the
 * very same [GraphObjectMergeBuilder] the single-object [GraphObjectManager.save] uses — so per-item
 * cascade and change detection are identical. Roots carrying a `@PropertyBag` fall back to the full
 * per-item path (their clear-stale `REMOVE` needs per-object keys an UNWIND can't express), as do
 * roots carrying a `@NodeLabels` field (labels are written into the statement, per object); a root
 * with a `@VectorIndex` field falls back too on engines that wrap vector writes (FalkorDB), since
 * `SET n += row.props` cannot wrap one property in `vecf32(...)`. See [GraphObjectManager.saveAll]
 * for the full contract and caveats.
 */
internal class BatchSaveOperations(
    private val objectMapper: ObjectMapper,
    private val sessionManager: SessionManager,
    private val chunkSize: Int,
    private val grammar: CypherGrammar? = null,
    private val storedKeys: StoredPropertyKeys? = null,
    private val stamping: Stamping? = null,
) {
    /**
     * Builds the statements for [items] (assumed non-empty), grouped by runtime class. Within a group
     * UNWIND-root statements precede the per-item relationship statements that MATCH those roots, so the
     * list is safe to execute in order.
     */
    fun buildBatchSpecs(items: List<Any>, cascade: CascadeType, nullPolicy: NullPolicy = NullPolicy.IGNORE): List<QuerySpecification<*>> {
        val specs = mutableListOf<QuerySpecification<*>>()
        items.groupBy { it.javaClass }.forEach { (clazz, group) ->
            val (rootModel, rootFieldName) = rootMetadata(clazz)
            val idField = rootModel.nodeIdField
            // A root an UNWIND cannot write is saved per item: see [savedByUnwind].
            if (idField != null && rootModel.savedByUnwind(grammar)) {
                appendUnwindGroup(specs, clazz, group, rootModel, rootFieldName, idField, cascade, nullPolicy)
            } else {
                group.forEach { obj -> mergeStatements(clazz, obj, cascade, nullPolicy).forEach { specs.add(it.toSpec()) } }
            }
        }
        return specs
    }

    /** Chunked UNWIND root upserts, then each item's relationship statements (root statement dropped). */
    private fun appendUnwindGroup(
        specs: MutableList<QuerySpecification<*>>,
        clazz: Class<*>,
        group: List<Any>,
        rootModel: FragmentModel,
        rootFieldName: String?,
        idField: String,
        cascade: CascadeType,
        nullPolicy: NullPolicy,
    ) {
        val labels = rootModel.labels.joinToString(":")
        // MERGE keys on the id field's on-disk property name (only differs under a @GraphProperty id).
        val idProperty = rootModel.nodeIdProperty ?: idField
        val rows = group.map { obj -> unwindRootRow(obj, rootModel, rootFieldName, idField, nullPolicy) }
        rows.chunked(chunkSize).forEach { chunk ->
            specs.add(
                QuerySpecification
                    .withStatement(if (stamping == null) "UNWIND \$rows AS row\nMERGE (n:$labels {$idProperty: row.id})\nSET n += row.props" else stampedUnwind(labels, idProperty))
                    .bind(mapOf("rows" to chunk))
            )
        }
        // The UNWIND already upserted each root; the remaining statements MATCH it by id (statement[0]
        // is always the root upsert — see GraphViewMergeBuilder / FragmentMergeBuilderAdapter).
        group.forEach { obj -> mergeStatements(clazz, obj, cascade, nullPolicy).drop(1).forEach { specs.add(it.toSpec()) } }
    }

    /**
     * The statements that save stamped fragments in batches and hand each one's stamp back: every
     * returned row is `index/found=stamp`, the index being the item's and `found` the stamp the node
     * had before the batch wrote to it, empty when it had none. Each of [items] is a fragment that
     * [savedByUnwind] allows.
     */
    fun buildStampedSpecs(items: List<IndexedValue<Any>>, nullPolicy: NullPolicy): List<QuerySpecification<String>> =
        items.groupBy { it.value.javaClass }.flatMap { (clazz, group) ->
            val model = FragmentModel.from(clazz)
            val idField = requireNotNull(model.nodeIdField)
            val statement = stampedUnwind(model.labels.joinToString(":"), model.nodeIdProperty ?: idField) +
                "\nRETURN row.i + '/' + ${Stamps.FOUND} + '=' + n.${Stamps.QUOTED}"
            group.map { (index, obj) -> unwindRootRow(obj, model, null, idField, nullPolicy) + ("i" to index.toString()) }
                .chunked(chunkSize)
                .map { chunk -> QuerySpecification.withStatement(statement).bind(mapOf("rows" to chunk)).transform(String::class.java) }
        }

    /**
     * The UNWIND upsert that gives a node a new stamp only when the row changes it. It takes the
     * node's lock before it reads the node to say so: see [Stamps.lock].
     */
    private fun stampedUnwind(labels: String, idProperty: String): String = """
        UNWIND ${'$'}rows AS row
        MERGE (n:$labels {$idProperty: row.id})
        ${Stamps.lock("n")}
        WITH n, row, coalesce(n.${Stamps.QUOTED}, '') AS ${Stamps.FOUND}, $ROW_CHANGES_NODE AS changed
        SET n += row.props, ${Stamps.restamp("n", "changed", "row.stamp")}
    """.trimIndent()

    /**
     * One `{ id, props }` UNWIND row for an UNWIND-eligible root (id excluded). The `props` map is keyed
     * by **on-disk property name** so `SET n += row.props` writes the right properties for
     * `@GraphProperty`-overridden fields (a no-op remap when there are no overrides).
     *
     * Null handling mirrors the single-save builder and is uniform for all fields (embeddings included):
     * - under [NullPolicy.IGNORE] (default) nulls are dropped, so `+=` never touches them (merge-patch);
     * - under [NullPolicy.CLEAR] nulls are **kept**, so `SET n += {x: null}` clears the property (the
     *   engines on this path — Neo4j / Memgraph, and non-vector FalkorDB — remove a key whose `+=` value
     *   is null). A vector-bearing root on FalkorDB never reaches here (it takes the per-item fallback).
     */
    private fun unwindRootRow(obj: Any, rootModel: FragmentModel, rootFieldName: String?, idField: String, nullPolicy: NullPolicy): Map<String, Any?> {
        val rootProps: Map<String, Any?> = if (rootFieldName != null) {
            @Suppress("UNCHECKED_CAST")
            objectMapper.toMap(obj)[rootFieldName] as? Map<String, Any?>
                ?: throw IllegalArgumentException("Root fragment '$rootFieldName' is null on ${obj.javaClass.simpleName}")
        } else {
            objectMapper.toMap(obj)
        }
        val id = rootProps[idField]
            ?: throw IllegalArgumentException("Cannot saveAll ${obj.javaClass.simpleName} with a null @GraphNodeId")
        val props = rootModel.unwindProps(rootProps, nullPolicy)
        val stamp = if (stamping != null) mapOf("stamp" to Stamps.fresh()) else emptyMap()
        return mapOf("id" to id, "props" to props) + stamp
    }

    private fun mergeStatements(clazz: Class<*>, obj: Any, cascade: CascadeType, nullPolicy: NullPolicy) =
        GraphObjectMergeBuilder.forClass(clazz, objectMapper, sessionManager, grammar, storedKeys, stamping?.unchecked()).buildMergeStatements(obj, cascade, nullPolicy)

    /** Root [FragmentModel] and (for views) the root field name; mirrors the manager's snapshot metadata. */
    private fun rootMetadata(clazz: Class<*>): Pair<FragmentModel, String?> =
        if (clazz.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(clazz)
            FragmentModel.from(viewModel.rootFragment.fragmentType) to viewModel.rootFragment.fieldName
        } else {
            FragmentModel.from(clazz) to null
        }
}

private fun org.drivine.query.MergeStatement.toSpec(): QuerySpecification<*> =
    QuerySpecification.withStatement(statement).bind(bindings)