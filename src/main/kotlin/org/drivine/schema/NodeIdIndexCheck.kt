package org.drivine.schema

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.slf4j.LoggerFactory
import java.util.Timer
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule

/**
 * Warns, once per fragment and database, of a fragment whose `@NodeId` no index serves under any of its labels: each save
 * and each load by id then reads every node of the label (#828). With the range index a fragment read into a
 * [SchemaCatalog] is given by default (#827), this is the case where that default is off or never applied.
 *
 * Nothing here reads the store while a fragment is used, nor throws: a check that fails is passed over, and the save or
 * load goes on. What serves an id is what [SchemaManager]'s enforce listed of each database ([listed]), after it applied
 * the catalogs, outside any caller's transaction, and kept current by each ensure after it ([ensured]). A fragment used before an enforce is checked when it runs, and one
 * first used after it against what it listed, each only on the databases where it belongs: those it is [used] on (#858),
 * and those whose catalogs name its labels ([catalogued], #848). Where no [SchemaManager] with a catalog has been built, nothing applies one;
 * as one may yet be, as Spring builds its beans, a fragment is warned of only if none has been [UNMANAGED_DELAY_MILLIS] after
 * its first use, and no index or constraint ensured by hand by then serves its id ([ensured], #886).
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

    /**
     * What serves an id that was ensured on a database no enforce has listed, as (label, property): with no [SchemaManager],
     * the index the warning names, ensured by hand, serves the fragment, which [unmanaged] then doesn't warn of (#886).
     */
    private val ensuredUnlisted = mutableMapOf<String, Set<Pair<String, String>>>()

    /** The fragments warned of, by database (null for none: no [SchemaManager]), each once. */
    private val warned = mutableMapOf<String?, WeakHashMap<Class<*>, Unit>>()

    /**
     * The databases a class was [used] on under a generation, the lock-free check each use makes: a set safe to read
     * while another thread adds to it.
     */
    private class Uses(val generation: Int) {
        val databases: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val usesOf = object : ClassValue<AtomicReference<Uses>>() {
        override fun computeValue(type: Class<*>) = AtomicReference(Uses(-1))
    }

    /** The fragments used on each database, by [used], which belong there whatever the catalogs name; guarded by the object's lock. */
    private val usedOn = mutableMapOf<String, WeakHashMap<Class<*>, Unit>>()

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

    /**
     * The labels the catalogs a [SchemaManager] applies declare items of, by database, given before its enforce lists
     * any: a fragment whose labels some catalog names is checked only on the databases where one does, as it belongs
     * there, and not warned of on another it is never used on (#848). A fragment no catalog names is checked on every
     * database listed.
     */
    private var catalogued: Map<String, Set<String>> = emptyMap()

    /** What the catalogs on each database name, as [catalogued] keeps it: given by [SchemaManager] before it lists. */
    fun catalogued(labels: Map<String, Set<String>>) {
        guarded { synchronized(this) { catalogued = labels } }
    }

    /** [labels] are named on [database] too, by a catalog [SchemaManager.ensure] gave after the enforce: before it lists [database] again. */
    fun catalogued(database: String, labels: Collection<String>) {
        guarded { synchronized(this) { catalogued = catalogued + (database to catalogued[database].orEmpty() + labels) } }
    }

    /**
     * [type], a fragment or a view, is saved, loaded, counted, searched, deleted, related or grouped through a manager on [database], the name it is registered
     * under: each fragment it reads or writes belongs on [database] (#858), and is checked there now if an enforce has
     * listed it, or when one does. After the first use on a database, a use costs a lookup in a set, without a lock.
     */
    fun used(database: String, type: Class<*>) {
        val now = generation.get()
        val held = usesOf.get(type)
        var uses = held.get()
        if (uses.generation == now && database in uses.databases) return
        if (uses.generation != now) {
            held.compareAndSet(uses, Uses(now))
            uses = held.get()
        }
        guarded {
            val models = fragmentsOf(type).map(FragmentModel::from).filter { it.nodeIdProperty != null }
            synchronized(this) {
                models.forEach { model ->
                    usedOn.getOrPut(database) { WeakHashMap() }[model.clazz] = Unit
                    served[database]?.let { check(database, model, it) }
                }
            }
        }
        // Once, even where the check failed: a use never pays for it again.
        uses.databases.add(database)
    }

    /** The fragments [type] reads and writes: itself if a fragment; a view's root and the fragments of its relationships, views nested in it too. */
    private fun fragmentsOf(type: Class<*>): Set<Class<*>> {
        val found = linkedSetOf<Class<*>>()
        val views = mutableSetOf<Class<*>>()
        fun visit(clazz: Class<*>?) {
            when {
                clazz == null -> return
                clazz.isAnnotationPresent(NodeFragment::class.java) -> found += clazz
                clazz.isAnnotationPresent(GraphView::class.java) && views.add(clazz) -> {
                    val view = GraphViewModel.from(clazz)
                    visit(view.rootFragment.fragmentType)
                    view.relationships.forEach { visit(if (it.isRelationshipFragment) it.targetNodeType else it.elementType) }
                }
            }
        }
        visit(type)
        return found
    }

    /**
     * Whether [model] is checked on [database]: it was [used] there, or no catalog names its labels, or one on [database]
     * does. The union keeps each warning the catalogs give, and adds the databases a fragment is used on, where its saves
     * scan the label all the same.
     */
    private fun belongs(database: String, model: FragmentModel): Boolean {
        if (usedOn[database]?.containsKey(model.clazz) == true) return true
        if (model.labels.none { label -> catalogued.values.any { label in it } }) return true
        return model.labels.any { it in catalogued[database].orEmpty() }
    }

    /** Whether an enforce has listed [database], whose listing a runtime ensure is to keep current. */
    fun listedOn(database: String): Boolean = synchronized(this) { database in served }

    /**
     * [spec] was ensured or recreated on [database] with [result], outside a [SchemaManager]: an id it serves is served from
     * then on, so a fragment first used after it isn't warned of, on a database an enforce listed or, with no enforce, by
     * [unmanaged] (#886). Nothing is read of the store.
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
            synchronized(this) {
                val item = spec.label to property
                val listing = served[database]
                if (listing != null) {
                    served[database] = listing + item
                } else {
                    ensuredUnlisted[database] = ensuredUnlisted[database].orEmpty() + item
                }
            }
        }
    }

    /** Warns of [clazz] after [UNMANAGED_DELAY_MILLIS] if no [SchemaManager] has been built by then, and nothing was [reset]. */
    private fun unmanagedLater(clazz: Class<*>, admittedUnder: Int) {
        val held = java.lang.ref.WeakReference(clazz)
        timer.schedule(UNMANAGED_DELAY_MILLIS) { unmanaged(held.get() ?: return@schedule, admittedUnder) }
    }

    /**
     * Warns of [clazz], admitted under [admittedUnder], where no [SchemaManager] has been built since, unless an index or a
     * constraint ensured by hand on some database serves its id: what the warning would advise.
     */
    internal fun unmanaged(clazz: Class<*>, admittedUnder: Int = generation.get()) {
        guarded {
            synchronized(this) {
                if (managed || admittedUnder != generation.get()) return@synchronized
                val model = FragmentModel.from(clazz)
                val property = model.nodeIdProperty ?: return@synchronized
                val servedByHand = ensuredUnlisted.values.any { items -> model.labels.any { it to property in items } }
                if (!servedByHand) warn(null, model)
            }
        }
    }

    /**
     * Warns of [model] where no index [serving] is over its `@NodeId` under any of its labels: a save and a load by id match
     * every label of the fragment, so an index under any of them answers it, as #828 has it.
     */
    private fun check(database: String, model: FragmentModel, serving: Set<Pair<String, String>>) {
        val property = model.nodeIdProperty ?: return
        if (!belongs(database, model)) return
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
                "or indexes.ensure(RangeIndexSpec(\"{}\", listOf(\"{}\"))).{}",
            property, fragment, label, label, fragment, label, property,
            // Where there is more than one database, the one it is said of (#848).
            database?.takeIf { catalogued.size > 1 || served.size > 1 }?.let { " On database '$it'." }.orEmpty(),
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
            ensuredUnlisted.clear()
            usedOn.clear()
            warned.clear()
            catalogued = emptyMap()
            managed = false
        }
    }
}
