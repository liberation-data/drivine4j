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
    private val grammar: CypherGrammar?,
    private val storedKeys: StoredPropertyKeys,
    private val stamping: Stamping,
) {
    /**
     * Builds the statements for [items] (assumed non-empty), grouped by runtime class. Within a group
     * UNWIND-root statements precede the per-item relationship statements that MATCH those roots, so the
     * list is safe to execute in order.
     *
     * The rows of each `UNWIND` go in the order of their ids, chunk after chunk, so that two batches
     * over the same roots lock them in the same order (see [inLockOrder]). The classes, and the
     * statements of each item, keep the order [items] gives: a view's relationship statements can
     * write a node another item's write too, and the caller's order says which is written last.
     *
     * @param writeFields the only root fields to write of an item of the class it is asked of, by
     *   field name; null for all of them
     */
    fun buildBatchSpecs(
        items: List<Any>,
        cascade: CascadeType,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        writeFields: (Class<*>) -> Set<String>? = { null },
    ): List<QuerySpecification<*>> {
        val specs = mutableListOf<QuerySpecification<*>>()
        items.groupBy { it.javaClass }.forEach { (clazz, group) ->
            val (rootModel, rootFieldName) = rootMetadata(clazz)
            val idField = rootModel.nodeIdField
            val fields = writeFields(clazz)
            // A root an UNWIND cannot write is saved per item: see [savedByUnwind].
            if (idField != null && rootModel.savedByUnwind(grammar)) {
                appendUnwindGroup(specs, clazz, group, rootModel, rootFieldName, idField, cascade, nullPolicy, fields)
            } else {
                group.forEach { obj -> mergeStatements(clazz, obj, cascade, nullPolicy, fields).forEach { specs.add(it.toSpec()) } }
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
        writeFields: Set<String>?,
    ) {
        val rows = group.map { obj -> unwindRootRow(obj, rootModel, rootFieldName, idField, nullPolicy, writeFields) }
        rows.inIdOrder(clazz).chunked(chunkSize).forEach { chunk ->
            specs.add(
                QuerySpecification
                    .withStatement(stampedUnwind(rootModel))
                    .bind(mapOf(ROWS to chunk))
            )
        }
        // The UNWIND already upserted each root; the remaining statements MATCH it by id (statement[0]
        // is always the root upsert — see GraphViewMergeBuilder / FragmentMergeBuilderAdapter).
        group.forEach { obj -> mergeStatements(clazz, obj, cascade, nullPolicy, writeFields).drop(1).forEach { specs.add(it.toSpec()) } }
    }

    /**
     * The statements that save stamped fragments in batches and hand each one's stamp back: every
     * returned row is `index/found=stamp`, the index being the item's and `found` the stamp the node
     * had before the row wrote to it: empty for a node the row made, [Stamps.NEVER_STAMPED] for one
     * that was there without a stamp. Each of [items] is a fragment that
     * [savedByUnwind] allows.
     *
     * The classes go in the order of their names and the rows of each in the order of their ids,
     * chunk after chunk: see [inLockOrder].
     */
    fun buildStampedSpecs(
        items: List<IndexedValue<Any>>,
        nullPolicy: NullPolicy,
        writeFields: (Class<*>) -> Set<String>? = { null },
    ): List<QuerySpecification<String>> =
        byClassName(items).flatMap { (clazz, group) ->
            val model = FragmentModel.from(clazz)
            val statement = stampedUnwind(model, handsBack = true) + "\nRETURN $HANDED_BACK"
            unwindRows(model, group, handsBack = true, nullPolicy, writeFields(clazz))
                .inIdOrder(clazz)
                .chunked(chunkSize)
                .map { chunk -> QuerySpecification.withStatement(statement).bind(mapOf(ROWS to chunk)).transform(String::class.java) }
        }

    /**
     * The fragments of [plain] and [stamped] as one statement, so that they are written whole or not
     * at all on an engine that cannot roll back a statement it has run. Null where they are of one
     * class, and where they are more rows than one statement takes ([chunkSize]): then
     * [buildBatchSpecs] and [buildStampedSpecs] write them, by a statement or more for each class.
     * Each fragment is one an `UNWIND` can write (see [savedByUnwind]).
     *
     * Each class is written by the `UNWIND` it has on its own, one class after another, the rows of
     * each bound under a parameter of their own. Each `UNWIND` is folded to one row before the next
     * begins, so the next runs once: and every `UNWIND` has a row, since each class is there because
     * a fragment of it is. The statement returns a row for each fragment of [stamped], as
     * [buildStampedSpecs] does, and none for one of [plain].
     *
     * The classes go in the order of their names, those of [plain] first, and the rows of each in the
     * order of their ids, as in every other statement of a batch: see [inLockOrder].
     */
    fun buildTogetherSpec(
        plain: List<IndexedValue<Any>>,
        stamped: List<IndexedValue<Any>>,
        nullPolicy: NullPolicy,
        writeFields: (Class<*>) -> Set<String>? = { null },
    ): QuerySpecification<String>? {
        val groups = togetherGroups(plain, handsBack = false) + togetherGroups(stamped, handsBack = true)
        if (groups.size < 2 || plain.size + stamped.size > chunkSize) return null
        require(groups.all { it.model.savedByUnwind(grammar) }) { "Only fragments an UNWIND can write are saved together." }
        // Only the stamps handed back are carried on. FalkorDB 6 fails a statement that carries a
        // count through two folds after it ("Type mismatch: ... but was Integer"), and no count is
        // needed: https://github.com/FalkorDB/FalkorDB/issues/3369
        val stampedAt = groups.indices.filter { groups[it].handsBack }
        val segments = groups.mapIndexed { index, group -> togetherSegment(group, index, stampedAt.filter { it < index }.map { "${SAVED}$it" }) }
        val handedBack = stampedAt.joinToString(" + ") { "${SAVED}$it" }.ifEmpty { "[]" }
        val statement = (segments + "UNWIND $handedBack AS saved\nRETURN saved").joinToString("\n")
        val bindings = groups.mapIndexed { index, group ->
            "${ROWS}$index" to unwindRows(group.model, group.items, group.handsBack, nullPolicy, writeFields(group.clazz)).inIdOrder(group.clazz)
        }.toMap()
        return QuerySpecification.withStatement(statement).bind(bindings).transform(String::class.java)
    }

    private fun togetherGroups(items: List<IndexedValue<Any>>, handsBack: Boolean): List<TogetherGroup> =
        byClassName(items).map { TogetherGroup(it.key, it.value, handsBack) }

    /** [items] grouped by their classes, in the order of the classes' names. */
    private fun byClassName(items: List<IndexedValue<Any>>): List<Map.Entry<Class<*>, List<IndexedValue<Any>>>> =
        items.groupBy { it.value.javaClass }.entries.sortedBy { it.key.name }

    /** The rows of one class's `UNWIND` in lock order: see [inLockOrder]. */
    private fun List<Map<String, Any?>>.inIdOrder(clazz: Class<*>): List<Map<String, Any?>> = inLockOrder({ clazz }, { it["id"] })

    /** The fragments of one class in a statement of [buildTogetherSpec], and whether a stamp is handed back for each. */
    private class TogetherGroup(val clazz: Class<*>, val items: List<IndexedValue<Any>>, val handsBack: Boolean) {
        val model: FragmentModel = FragmentModel.from(clazz)
    }

    /**
     * The `UNWIND` that writes [group] as the [index]th class of a statement of [buildTogetherSpec],
     * folded to one row that carries the stamps the classes before it hand back, [carried], and its
     * own: the stamps it hands back, or for a class that hands none back the count of its rows, which
     * nothing after it carries.
     */
    private fun togetherSegment(group: TogetherGroup, index: Int, carried: List<String>): String {
        val unwind = stampedUnwind(group.model, group.handsBack, "${ROWS}$index", carried, afterFold = index > 0)
        val fold = if (group.handsBack) "collect($HANDED_BACK)" else "count(*)"
        return "$unwind\nWITH ${(carried + "$fold AS ${SAVED}$index").joinToString(", ")}"
    }

    /** The `UNWIND` rows of fragments of [model], each with its index (`i`) where a stamp is handed back for it. */
    private fun unwindRows(
        model: FragmentModel,
        items: List<IndexedValue<Any>>,
        handsBack: Boolean,
        nullPolicy: NullPolicy,
        writeFields: Set<String>?,
    ): List<Map<String, Any?>> {
        val idField = requireNotNull(model.nodeIdField)
        return items.map { (index, obj) ->
            val row = unwindRootRow(obj, model, null, idField, nullPolicy, writeFields)
            if (handsBack) row + ("i" to index.toString()) else row
        }
    }

    /**
     * The UNWIND upsert of nodes of [model] that gives a node a new stamp only when the row changes
     * it. It takes the node's lock before it reads the node to say so: see [Stamps.lock]. The `MERGE`
     * keys on the id field's stored property name, which differs from the field's under a
     * `@GraphProperty` id.
     *
     * @param rows the parameter the rows are bound under
     * @param carried the variables of the statement before it to carry through it
     * @param afterFold whether it follows a fold of the statement before it to one row, as in
     *   [buildTogetherSpec]. The rows are then projected before the `MERGE`: without that FalkorDB 6,
     *   given a `MERGE` after two folds of which the later follows a `WITH`, binds the node of the
     *   `MERGE` before it, and writes this row to that node
     *   (https://github.com/FalkorDB/FalkorDB/issues/3368).
     */
    private fun stampedUnwind(
        model: FragmentModel,
        handsBack: Boolean = false,
        rows: String = ROWS,
        carried: List<String> = emptyList(),
        afterFold: Boolean = false,
    ): String {
        val labels = model.labels.joinToString(":")
        val idProperty = model.nodeIdProperty ?: requireNotNull(model.nodeIdField)
        val found = if (handsBack) Stamps.foundOf("n") else "coalesce(n.${Stamps.QUOTED}, '')"
        val through = (carried + listOf("n", "row")).joinToString(", ")
        return listOfNotNull(
            "UNWIND \$$rows AS row",
            "WITH ${(carried + "row").joinToString(", ")}".takeIf { afterFold },
            "MERGE (n:$labels {$idProperty: row.id})",
            // A node the statement makes is told from one it finds, for the stamp that is handed back.
            Stamps.onCreate("n").takeIf { handsBack },
            Stamps.lock("n"),
            "WITH $through, $found AS ${Stamps.FOUND}, $ROW_CHANGES_NODE AS changed",
            "SET n += row.props, ${Stamps.restamp("n", "changed", "row.stamp")}",
            "REMOVE ${Stamps.unmark("n")}".takeIf { handsBack },
        ).joinToString("\n")
    }

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
     *
     * With [writeFields], `props` holds those fields alone, so the row neither writes nor clears any other.
     */
    private fun unwindRootRow(
        obj: Any, rootModel: FragmentModel, rootFieldName: String?, idField: String, nullPolicy: NullPolicy, writeFields: Set<String>?,
    ): Map<String, Any?> {
        val rootProps: Map<String, Any?> = if (rootFieldName != null) {
            @Suppress("UNCHECKED_CAST")
            objectMapper.toMap(obj)[rootFieldName] as? Map<String, Any?>
                ?: throw IllegalArgumentException("Root fragment '$rootFieldName' is null on ${obj.javaClass.simpleName}")
        } else {
            objectMapper.toMap(obj)
        }
        val id = rootProps[idField]
            ?: throw IllegalArgumentException("Cannot saveAll ${obj.javaClass.simpleName} with a null @GraphNodeId")
        // By field name, before the props are keyed by stored name: a field renamed with @GraphProperty is named as declared.
        val written = if (writeFields == null) rootProps else rootProps.filterKeys { it in writeFields }
        val props = rootModel.unwindProps(written, nullPolicy)
        return mapOf("id" to id, "props" to props, "stamp" to Stamps.fresh())
    }

    private fun mergeStatements(clazz: Class<*>, obj: Any, cascade: CascadeType, nullPolicy: NullPolicy, writeFields: Set<String>?) =
        GraphObjectMergeBuilder.forClass(clazz, objectMapper, sessionManager, grammar, storedKeys, stamping.unchecked(), writeFields)
            .buildMergeStatements(obj, cascade, nullPolicy)

    /** Root [FragmentModel] and (for views) the root field name; mirrors the manager's snapshot metadata. */
    private fun rootMetadata(clazz: Class<*>): Pair<FragmentModel, String?> =
        if (clazz.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(clazz)
            FragmentModel.from(viewModel.rootFragment.fragmentType) to viewModel.rootFragment.fieldName
        } else {
            FragmentModel.from(clazz) to null
        }

    companion object {
        // Max rows per UNWIND statement in saveAll — caps server-side batch size; each chunk is one
        // statement (and, when not in an ambient transaction, still within the single batch transaction).
        const val UNWIND_CHUNK_SIZE = 1000

        /** The parameter an `UNWIND` binds its rows under; in a statement of [buildTogetherSpec], with the class's place after it. */
        private const val ROWS = "rows"

        /** What a class of a statement of [buildTogetherSpec] is folded to, with its place after it. */
        private const val SAVED = "_saved"

        /** What a row of a stamped `UNWIND` hands back: `index/found=stamp`. */
        private const val HANDED_BACK = "row.i + '/' + ${Stamps.FOUND} + '=' + n.${Stamps.QUOTED}"
    }
}

/**
 * These items in the order a batch takes their nodes' locks in: by the name of each one's class,
 * then by its id as text, so that two batches over the same nodes take their locks in the same order
 * whatever order they were given in. Items of one class and id keep the order they were given in, so
 * the last of them is still the one written last.
 */
internal fun <T> List<T>.inLockOrder(classOf: (T) -> Class<*>, idOf: (T) -> Any?): List<T> =
    map { LockKey(classOf(it).name, idOf(it).toString()) to it }.sortedBy { it.first }.map { it.second }

/** Where an item comes in [inLockOrder]: each key read once, not at every comparison. */
private data class LockKey(val className: String, val id: String) : Comparable<LockKey> {
    override fun compareTo(other: LockKey): Int = compareValuesBy(this, other, { it.className }, { it.id })
}

private fun org.drivine.query.MergeStatement.toSpec(): QuerySpecification<*> =
    QuerySpecification.withStatement(statement).bind(bindings)