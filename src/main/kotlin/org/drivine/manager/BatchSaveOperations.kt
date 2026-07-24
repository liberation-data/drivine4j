package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.GraphObjectMergeBuilder
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
 * per-item path (their clear-stale `REMOVE` needs per-object keys an UNWIND can't express); a root
 * with a `@VectorIndex` field falls back too on engines that wrap vector writes (FalkorDB), since
 * `SET n += row.props` cannot wrap one property in `vecf32(...)`. See [GraphObjectManager.saveAll]
 * for the full contract and caveats.
 */
internal class BatchSaveOperations(
    private val objectMapper: ObjectMapper,
    private val sessionManager: SessionManager,
    private val chunkSize: Int,
    private val grammar: CypherGrammar? = null,
) {
    /**
     * Builds the statements for [items] (assumed non-empty), grouped by runtime class. Within a group
     * UNWIND-root statements precede the per-item relationship statements that MATCH those roots, so the
     * list is safe to execute in order.
     */
    fun buildBatchSpecs(items: List<Any>, cascade: CascadeType): List<QuerySpecification<*>> {
        val specs = mutableListOf<QuerySpecification<*>>()
        items.groupBy { it.javaClass }.forEach { (clazz, group) ->
            val (rootModel, rootFieldName) = rootMetadata(clazz)
            val idField = rootModel.nodeIdField
            // A vector-bearing root needs per-item saves on engines that wrap vector writes (FalkorDB):
            // `SET n += row.props` can't wrap a single property in vecf32(...). Elsewhere (Neo4j /
            // Memgraph store a plain array) the UNWIND path is fine, exactly as for a plain fragment.
            val vectorNeedsPerItem = rootModel.vectorFieldNames.isNotEmpty() && grammar?.wrapsVectorLiteral == true
            if (idField != null && rootModel.propertyBags.isEmpty() && !vectorNeedsPerItem) {
                appendUnwindGroup(specs, clazz, group, rootModel.labels.joinToString(":"), rootFieldName, idField, cascade)
            } else {
                group.forEach { obj -> mergeStatements(clazz, obj, cascade).forEach { specs.add(it.toSpec()) } }
            }
        }
        return specs
    }

    /** Chunked UNWIND root upserts, then each item's relationship statements (root statement dropped). */
    private fun appendUnwindGroup(
        specs: MutableList<QuerySpecification<*>>,
        clazz: Class<*>,
        group: List<Any>,
        labels: String,
        rootFieldName: String?,
        idField: String,
        cascade: CascadeType,
    ) {
        val rows = group.map { obj -> unwindRootRow(obj, rootFieldName, idField) }
        rows.chunked(chunkSize).forEach { chunk ->
            specs.add(
                QuerySpecification
                    .withStatement("UNWIND \$rows AS row\nMERGE (n:$labels {$idField: row.id})\nSET n += row.props")
                    .bind(mapOf("rows" to chunk))
            )
        }
        // The UNWIND already upserted each root; the remaining statements MATCH it by id (statement[0]
        // is always the root upsert — see GraphViewMergeBuilder / FragmentMergeBuilderAdapter).
        group.forEach { obj -> mergeStatements(clazz, obj, cascade).drop(1).forEach { specs.add(it.toSpec()) } }
    }

    /** One `{ id, props }` UNWIND row for an UNWIND-eligible root (id excluded, nulls dropped). */
    private fun unwindRootRow(obj: Any, rootFieldName: String?, idField: String): Map<String, Any?> {
        val rootProps: Map<String, Any?> = if (rootFieldName != null) {
            @Suppress("UNCHECKED_CAST")
            objectMapper.toMap(obj)[rootFieldName] as? Map<String, Any?>
                ?: throw IllegalArgumentException("Root fragment '$rootFieldName' is null on ${obj.javaClass.simpleName}")
        } else {
            objectMapper.toMap(obj)
        }
        val id = rootProps[idField]
            ?: throw IllegalArgumentException("Cannot saveAll ${obj.javaClass.simpleName} with a null @GraphNodeId")
        val props = rootProps.filterKeys { it != idField }.filterValues { it != null }
        return mapOf("id" to id, "props" to props)
    }

    private fun mergeStatements(clazz: Class<*>, obj: Any, cascade: CascadeType) =
        GraphObjectMergeBuilder.forClass(clazz, objectMapper, sessionManager, grammar).buildMergeStatements(obj, cascade)

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