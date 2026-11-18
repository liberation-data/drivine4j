package org.drivine.schema

import org.drivine.model.FragmentModel
import org.slf4j.LoggerFactory
import java.util.Timer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule

/**
 * Warns, once per fragment and database, of a fragment whose `@NodeId` no index serves under any of its labels: each save
 * and each load by id then reads every node of the label (#828). With the range index a fragment read into a
 * [SchemaCatalog] is given by default (#827), this is the case where that default is off or never applied.
 *
 * Nothing here reads the store while a fragment is used, nor throws: a check that fails is passed over, and the save or
 * load goes on. What serves an id is what [SchemaManager]'s enforce listed of each database ([listed]), after it applied
 * the catalogs, outside any caller's transaction, and kept current by each ensure after it ([ensured]). A fragment used before an enforce is checked when it runs, and one
 * first used after it against what it listed. Where no [SchemaManager] with a catalog has been built, nothing applies one;
 * as one may yet be, as Spring builds its beans, a fragment is warned of only if none has been [UNMANAGED_DELAY_MILLIS] after
 * its first use.
 *
 * As [VectorAnnotationCheck], classes are held weakly, so a classloader dropped by a restart is not kept alive.
 */
internal object NodeIdIndexCheck {

    private val logger = LoggerFactory.getLogger(NodeIdIndexCheck::class.java)

    /** How long after a fragment's first use, with no [SchemaManager] built, it is warned of. */
    const val UNMANAGED_DELAY_MILLIS = 30_000L

    /** What [reset] last set: a class admitted under another is admitted again. */
    private val generation = AtomicInteger()

    /** The generation a class was admitted under: the lock-free check every [FragmentModel.from] makes. */
    private val admittedIn = object : ClassValue<AtomicInteger>() {
        override fun computeValue(type: Class<*>) = AtomicInteger(-1)
    }

    /** The fragments with a `@NodeId` admitted so far, for [listed] to check; guarded by the object's lock. */
    private val admitted = WeakHashMap<Class<*>, Unit>()

    /** What serves an id on each database an enforce listed: its range indexes and uniqueness constraints over one property, as (label, property). */
    private val served = mutableMapOf<String, Set<Pair<String, String>>>()

    /** The fragments warned of, by database (null for none: no [SchemaManager]), each once. */
    private val warned = mutableMapOf<String?, WeakHashMap<Class<*>, Unit>>()

    /** Whether a [SchemaManager] has been built, which applies catalogs and lists what serves an id. */
    @Volatile
    private var managed = false

    /** The timer an unmanaged fragment is warned of on, made at the first that needs it. */
    private val timer by lazy { Timer("drivine-node-id-index-check", true) }

    /** A [SchemaManager] with a catalog was built: fragments are checked against what its enforce lists. */
    fun managed() {
        managed = true
    }

    /** Checks a newly built [model] once per class: against each database an enforce listed, or, with no manager, later. */
    fun admit(model: FragmentModel) {
        val now = generation.get()
        val flag = admittedIn.get(model.clazz)
        if (flag.get() == now) return
        guarded {
            synchronized(this) {
                if (model.nodeIdProperty != null) {
                    admitted[model.clazz] = Unit
                    served.forEach { (database, items) -> check(database, model, items) }
                    if (!managed) unmanagedLater(model.clazz, now)
                }
                flag.set(now)
            }
        }
    }

    /**
     * What an enforce found on [database], [items] its indexes and constraints: each fragment admitted so far is checked
     * against it. An item serves a property where the store takes it for a range index or a uniqueness constraint over
     * that property alone ([identifies], the store's `matchesIdentity`): FalkorDB lists every range property of a label as
     * one item, each its own index.
     */
    fun listed(
        database: String,
        items: List<SchemaItemInfo>,
        identifies: (SchemaItemInfo, SchemaItemSpec) -> Boolean = { item, spec -> item.properties.toSet() == spec.properties.toSet() },
    ) {
        guarded {
            synchronized(this) {
                val serving = items.flatMap { item ->
                    item.properties.filter { property ->
                        when (item.kind) {
                            SchemaItemKind.RANGE_INDEX -> identifies(item, RangeIndexSpec(item.label, property))
                            SchemaItemKind.UNIQUENESS_CONSTRAINT -> identifies(item, UniquenessConstraintSpec(item.label, property))
                            else -> false
                        }
                    }.map { item.label to it }
                }.toSet()
                served[database] = serving
                admitted.keys.toList().forEach { check(database, FragmentModel.from(it), serving) }
            }
        }
    }

    /** Whether an enforce has listed [database], whose listing a runtime ensure is to keep current. */
    fun listedOn(database: String): Boolean = synchronized(this) { database in served }

    /**
     * [spec] was ensured or recreated on [database] with [result], outside a [SchemaManager]: where an enforce listed the database, an id
     * it serves is served from then on, so a fragment first used after it isn't warned of. Nothing is read of the store.
     */
    fun ensured(database: String?, spec: SchemaItemSpec, result: EnsureResult) {
        if (result !is EnsureResult.Created && result !is EnsureResult.AlreadyMatching && result !is EnsureResult.Recreated) return
        val property = spec.properties.singleOrNull() ?: return
        val serves = when (spec) {
            is RangeIndexSpec -> !spec.ignoreCase
            is UniquenessConstraintSpec -> !spec.ignoreCase
            else -> false
        }
        if (!serves || database == null) return
        guarded {
            synchronized(this) { served[database]?.let { served[database] = it + (spec.label to property) } }
        }
    }

    /** Warns of [clazz] after [UNMANAGED_DELAY_MILLIS] if no [SchemaManager] has been built by then, and nothing was [reset]. */
    private fun unmanagedLater(clazz: Class<*>, admittedUnder: Int) {
        val held = java.lang.ref.WeakReference(clazz)
        timer.schedule(UNMANAGED_DELAY_MILLIS) { unmanaged(held.get() ?: return@schedule, admittedUnder) }
    }

    /** Warns of [clazz], admitted under [admittedUnder], where no [SchemaManager] has been built since. */
    internal fun unmanaged(clazz: Class<*>, admittedUnder: Int = generation.get()) {
        guarded {
            synchronized(this) {
                if (!managed && admittedUnder == generation.get()) warn(null, FragmentModel.from(clazz))
            }
        }
    }

    /**
     * Warns of [model] where no index [serving] is over its `@NodeId` under any of its labels: a save and a load by id match
     * every label of the fragment, so an index under any of them answers it, as #828 has it.
     */
    private fun check(database: String, model: FragmentModel, serving: Set<Pair<String, String>>) {
        val property = model.nodeIdProperty ?: return
        if (model.labels.none { it to property in serving }) warn(database, model)
    }

    private fun warn(database: String?, model: FragmentModel) {
        val label = model.labels.firstOrNull() ?: return
        val property = model.nodeIdProperty ?: return
        if (warned.getOrPut(database) { WeakHashMap() }.put(model.clazz, Unit) != null) return
        val fragment = model.clazz.simpleName
        logger.warn(
            "@NodeId '{}' of {} has no index under :{}, so each save scans every :{} node. Its default index is off " +
                "(nodeIdIndex = false / drivine.schema.index-node-ids=false, or {} isn't in a SchemaCatalog): declare @RangeIndex or @Unique on it, " +
                "or indexes.ensure(RangeIndexSpec(\"{}\", listOf(\"{}\"))).",
            property, fragment, label, label, fragment, label, property,
        )
    }

    /** Runs [check], passing over anything it throws: a warning must never fail the save or load it is made in. */
    private inline fun guarded(check: () -> Unit) {
        try {
            check()
        } catch (e: Exception) {
            logger.debug("The check for an unindexed @NodeId could not be made: {}", e.toString())
        }
    }

    /** Forgets everything, for tests: each class is admitted again at its next use. */
    internal fun reset() {
        synchronized(this) {
            generation.incrementAndGet()
            admitted.clear()
            served.clear()
            warned.clear()
            managed = false
        }
    }
}
