package org.drivine.manager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.StaleObjectException
import org.drivine.annotation.GraphView
import org.drivine.mapper.SubtypeRegistry
import org.drivine.backend.FragmentBatch
import org.drivine.mapper.toMap
import org.drivine.annotation.NodeFragment
import org.drivine.mapper.ResultPostProcessor
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.FoundStamp
import org.drivine.query.PatchOutcomes
import org.drivine.query.PatchRow
import org.drivine.query.ReturnedStamps
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.Stamping
import org.drivine.query.dsl.PatchSpec
import org.drivine.query.read
import org.drivine.session.SessionManager
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import java.util.UUID
import java.util.function.Consumer
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * An object manager that remembers nothing between calls. What a save writes is decided by the
 * object and the arguments, never by whether the object was loaded here before.
 *
 * - [save] writes every field of the object that is not null and adds the relationships it holds. It removes a
 *   relationship only when asked to, with [Replace].
 * - A save is one statement: the root, the relationships it drops, each related node and the
 *   relationship to it. It is applied whole or not at all, with or without a transaction.
 * - A fragment that declares a `@NodeStamp` field is checked: a save of an object that carries a
 *   stamp applies only if the node's own data is as it was loaded, and with [Replace] its
 *   relationships too, and otherwise throws [StaleObjectException]. In a view, the root is checked.
 *   A node reached through a relationship is written unchecked.
 * - [update] loads an object, applies a change, and writes only what the change altered.
 * - [patch] writes named fields of one node by its id, if it meets a predicate, in one statement,
 *   and makes no node.
 * - [patchAll] writes named fields of many nodes that are there, and makes no node.
 */
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class StatelessGraphObjectManager private constructor(
    private val persistenceManager: PersistenceManager,
    private val objectMapper: ObjectMapper,
    /** Loads, queries and deletes: with a session that remembers nothing, each is stateless. */
    private val objects: GraphObjectManager,
) : GraphObjectOperations by objects {

    constructor(persistenceManager: PersistenceManager, objectMapper: ObjectMapper, subtypeRegistry: SubtypeRegistry) : this(
        persistenceManager,
        objectMapper,
        GraphObjectManager(persistenceManager, SessionManager.untracked(objectMapper), objectMapper, subtypeRegistry, Stamping(checked = false)),
    )

    private val logger = LoggerFactory.getLogger(StatelessGraphObjectManager::class.java)
    private val digests = objects.sessionManager
    private val backend = objects.backend
    private val executor = SaveExecutor(persistenceManager, backend)
    private val stamps = StampedCopy(objectMapper)

    /**
     * Saves [obj] and returns it carrying the stamps the save left: on its root, and on each related
     * node that declares a `@NodeStamp` field. A stamp is new if the save changed the node, and the one
     * it had otherwise. Use the returned object from then on. A Kotlin data class is returned as a
     * copy; a class whose fields can be set is given its stamps in place and returned itself.
     *
     * A relationship the save adds or removes, or whose properties it changes, gives the node at each
     * end a new relationship token in its stamp. Only a save with [Replace] compares that token.
     *
     * A stamp is handed back with the node's relationship token only if the node's relationships were
     * as the object's stamp says when the save began. Otherwise another writer added or removed one the
     * object does not hold, and the object keeps the token it had: a [Replace] of it is still refused.
     *
     * An object that carries no stamp is handed the whole stamp of a node its save made. Of a node
     * that was already there it is handed the token for the node's own data alone, on the root and
     * on a related node alike: its lists did not come from the store, so a [Replace] of the returned
     * object is refused until the object is loaded. A save of it that only adds is applied.
     *
     * An object that cannot be handed its stamps is refused before anything is written: a class that
     * is neither a Kotlin data class nor has fields that can be set, and that the object mapper
     * cannot rebuild.
     *
     * A node reached through a relationship is written whole and unchecked, a null field left alone
     * whatever [nullPolicy] says: the policy governs the root. [update] writes only what was altered.
     *
     * @param relationships [Add] (the default) adds the relationships the object holds and removes
     *   none. [Replace] names the relationship fields whose list is the whole list.
     * @param nullPolicy how a null field is treated; see [NullPolicy]
     * @param only the only fields of the object (for a view, of its root) to write. The relationships
     *   of a view are written whatever this names. A property of another class is refused
     * @param except fields of the object (for a view, of its root) to leave unwritten. Give [only]
     *   or [except], not both
     */
    @JvmOverloads
    fun <T : Any> save(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<KProperty1<*, *>> = emptySet(),
        except: Set<KProperty1<*, *>> = emptySet(),
    ): T {
        // First that each names a field at all, then that it is a field of the class that is saved.
        writeFields(obj, only.map { it.name }.toSet(), except.map { it.name }.toSet())
        (only + except).forEach { requireFieldOf(obj, it) }
        return saveFields(obj, relationships, nullPolicy, only.map { it.name }.toSet(), except.map { it.name }.toSet())
    }

    /**
     * [save] with the fields named as strings, for Java. A Java caller that names no field calls [save].
     *
     * @param relationships [Add] adds the relationships the object holds and removes none; [Replace]
     *   names the relationship fields whose list is the whole list
     * @param nullPolicy how a null field is treated; see [NullPolicy]
     * @param only the names of the only fields of the object (for a view, of its root) to write
     * @param except the names of fields of the object (for a view, of its root) to leave unwritten.
     *   Give [only] or [except], not both
     * @throws IllegalArgumentException when a name is no field of the object, and when the object
     *   could not be handed the stamps the save would leave; nothing is written then
     * @throws StaleObjectException when what the save would overwrite changed after the object was loaded
     */
    @JvmOverloads
    fun <T : Any> saveFields(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<String> = emptySet(),
        except: Set<String> = emptySet(),
    ): T {
        // Everything is validated before anything is written: the arguments, and that the object can
        // be handed its stamps.
        val statement = statementFor(obj, relationships, nullPolicy, writeFields(obj, only, except), checked = true)
        requireStampable(obj, statement)
        return stamped(obj, statement, executor.save(statement))
    }

    /**
     * Saves each object, and returns each carrying the stamps its save left, as [save] does. A save
     * that only adds is not checked: an object that carries a stale stamp is written all the same. As
     * with [save], a stamp is handed back with a token of the node's only if what the token speaks for
     * was as the object's stamp says: an object written over a change it never held keeps its own
     * stamp, and a later [save] of it is refused.
     *
     * With [Replace], each view that carries a stamp is checked as [save] checks it, since a replace
     * overwrites a relationship list: if the node or its relationships changed since the view was
     * loaded, [StaleObjectException] is thrown and the batch is not applied, on an engine that has
     * transactions. A batch that holds a fragment is refused with [Replace], which applies to views.
     *
     * Fragments are saved first, and then each view in the order given, and a view's save changes the
     * nodes it holds. So a
     * view with [Replace] whose root another object earlier in the batch holds can be refused by the
     * batch's own doing: that object's save changed the root, or a relationship of it, before the
     * view's stamp was compared. The exception then says so ([StaleObjectException.bySameBatch]).
     * Give such a view before the objects that hold its root, or save them one at a time, each from
     * what the save before it returned.
     *
     * The whole call is atomic: inside a transaction it joins it, and otherwise it runs in one of its
     * own, on an engine that has transactions. A [Replace] is part of it. FalkorDB has none: there
     * each statement is atomic, and what the batch saved before a failure or a refusal stays saved.
     * Inside a caller's transaction, a refusal undoes the batch only if the caller's transaction is
     * rolled back, as it is when the exception leaves it.
     * A batch the engine turns away because another writer was changing the same nodes is run again.
     *
     * Fragments are saved in batches. A view is saved by a statement of its own. An object the batch
     * holds more than once is handed the stamp its node is left with each time.
     *
     * A batch that only adds is not checked, and it makes any node it does not find. With [only] or
     * [except], a node that was deleted since the object was loaded comes back holding its id and the
     * written fields alone, and for a view its relationships. It has its labels and a stamp, as any
     * node a save makes. To write fields only where the node still exists, use [patchAll].
     *
     * A field is named by its name, and a property is a way to spell one: `Person::name` names the
     * field `name` of every class in the batch, whichever class declares the property.
     *
     * @param only the only fields of each object (for a view, of its root) to write. Every other field
     *   of the node is left as the store has it: neither written nor, under [NullPolicy.CLEAR], cleared.
     *   The relationships of a view are written whatever this names. Each name must be a field of every
     *   class in the batch, and not its id or its stamp, or the batch is refused before anything is
     *   written. Empty, the default, means every field
     * @param except fields of each object (for a view, of its root) to leave unwritten: the node keeps
     *   what the store has for them. Every other field is written. Each name must be a field of every
     *   class in the batch, as for [only]; the id and the stamp may be named, and mean nothing, since
     *   neither is written from the object. Give [only] or [except], not both
     */
    @JvmOverloads
    fun <T : Any> saveAll(
        objs: Collection<T>,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<KProperty1<*, *>> = emptySet(),
        except: Set<KProperty1<*, *>> = emptySet(),
    ): List<T> = saveAllFields(objs, relationships, nullPolicy, only.map { it.name }.toSet(), except.map { it.name }.toSet())

    /**
     * [saveAll] with the fields named as strings, for Java. A Java caller that names no field calls [saveAll].
     *
     * @param relationships [Add] adds the relationships each object holds and removes none; [Replace]
     *   names the relationship fields whose list is the whole list
     * @param nullPolicy how a null field is treated; see [NullPolicy]
     * @param only the names of the only fields of each object (for a view, of its root) to write
     * @param except the names of fields of each object (for a view, of its root) to leave unwritten.
     *   Give [only] or [except], not both
     * @throws IllegalArgumentException when both [only] and [except] are given, a name is no field of
     *   a class in the batch, or [only] names an id or a stamp, and for whatever [saveAll] refuses with
     *   no field named, among them a [Replace] that cannot be applied, an object with a null id, and
     *   one that could not be handed the stamps its save would leave. Nothing is written then
     * @throws StaleObjectException with [Replace], when a view that carries a stamp changed after it was loaded
     */
    @JvmOverloads
    fun <T : Any> saveAllFields(
        objs: Collection<T>,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<String> = emptySet(),
        except: Set<String> = emptySet(),
    ): List<T> {
        require(only.isEmpty() || except.isEmpty()) { "Give only or except, not both." }
        val items = objs.toList()
        // The names are checked against each class of the batch once, before anything is written.
        val fields: Map<Class<*>, Set<String>?> = items.map { it.javaClass }.distinct().associateWith { batchWriteFields(it, only, except) }
        if (items.isEmpty()) return emptyList()
        val views = items.filter { it.javaClass.isAnnotationPresent(GraphView::class.java) }
        val fragments = items.withIndex().filterNot { it.value.javaClass.isAnnotationPresent(GraphView::class.java) }
        require(relationships !is Replace || fragments.isEmpty()) {
            "Replace applies to the relationship fields of a @GraphView, and ${fragments.first().value.javaClass.simpleName} is not one."
        }
        // A stamp is handed back by a statement that returns it: one for a batch of fragments, and one
        // for each view, and each fragment the backend does not save with others.
        val batch = backend.saveTogether(fragments, nullPolicy, fields::getValue)
        val single = views + batch.alone.map { it.value }
        // A view whose list is replaced is checked, as a save of it is: a replace overwrites the
        // list, and must not remove a relationship the object never held.
        val singles = single.map { statementFor(it, relationships, nullPolicy, fields.getValue(it.javaClass), checked = relationships is Replace) }
        requireStampable(fragments, batch, single, singles)

        val rows = executor.batch(
            batch.unstamped + batch.stamped + singles.map { executor.spec(it, inBatch = true) },
            offered = singles.flatMap { it.offered },
        )
        val togetherRows = rows.subList(batch.unstamped.size, batch.unstamped.size + batch.stamped.size).flatten()
        val saved = IdentityHashMap<Any, Any>()
        saved.putAll(savedTogether(items, togetherRows.map { FoundStamp.of(it as String) }))
        saved.putAll(savedSingly(single, singles, rows.takeLast(singles.size)))
        @Suppress("UNCHECKED_CAST")
        return items.map { (saved[it] ?: it) as T }
    }

    /**
     * Refuses a batch before anything is written when one of its objects could not be handed the
     * stamps its save would leave: a stamped fragment saved with others, and each of [single] by the
     * statement of [singles] that saves it.
     */
    private fun requireStampable(fragments: List<IndexedValue<Any>>, batch: FragmentBatch, single: List<Any>, singles: List<SaveStatement>) {
        val alone = batch.alone.map { it.index }.toSet()
        fragments.filter { it.index !in alone && FragmentModel.from(it.value.javaClass).stampField != null }
            .forEach { stamps.requireStampable(it.value, listOf(it.value)) }
        single.forEachIndexed { index, item -> requireStampable(item, singles[index]) }
    }

    /**
     * The fragments of [items] that were saved together, each as it is handed the stamp its row in
     * [returned] says, keyed by the fragment as it was given: one given twice is handed it once.
     *
     * A node the batch holds more than once is written by more than one row, and a later row finds
     * what an earlier one wrote: what the node carried before the batch is what the first found, and
     * what it is left with is what the last wrote.
     */
    private fun savedTogether(items: List<Any>, returned: List<FoundStamp>): Map<Any, Any> {
        val found = linkedMapOf<Pair<Class<*>, Any?>, String>()
        val left = linkedMapOf<Pair<Class<*>, Any?>, String>()
        val nodes = returned.map { row ->
            val item = items[row.index]
            val node = item.javaClass to objectMapper.toMap(item)[FragmentModel.from(item.javaClass).nodeIdField]
            found.putIfAbsent(node, row.found)
            left[node] = row.stamp
            item to node
        }
        val saved = IdentityHashMap<Any, Any>()
        nodes.forEach { (item, node) ->
            // A batch is not checked, so the node may not have been as the object's stamp says. Then
            // the object keeps its own token: the stamp handed back does not vouch for data or
            // relationships that changed after the object was loaded.
            val stamp = Stamps.handedBack(stamps.stampOf(item), found.getValue(node), left.getValue(node))
            saved[item] = stamps.of(item, IdentityHashMap<Any, String>().apply { put(item, stamp) })
        }
        return saved
    }

    /** Each of [single] as the statement of [singles] that saved it hands its stamps back, from that statement's [rows]. */
    private fun savedSingly(single: List<Any>, singles: List<SaveStatement>, rows: List<List<Any?>>): Map<Any, Any> {
        val handed = singles.indices.map { executor.stamps(singles[it], rows[it]).toMutableList() }
        carryForward(singles, rows, handed)
        val saved = IdentityHashMap<Any, Any>()
        single.forEachIndexed { index, item -> saved[item] = stamped(item, singles[index], handed[index]) }
        return saved
    }

    /**
     * Brings the root stamp each statement of a batch handed back up to what the statements after it
     * left. A later statement that holds an earlier one's root as a related node may change that node
     * or a relationship of it: the earlier object is handed the token the later one left, when the
     * later one found the node carrying the token the earlier was handed. Otherwise another writer
     * came between, and the object keeps what it has.
     */
    private fun carryForward(statements: List<SaveStatement>, rows: List<List<Any?>>, handed: List<MutableList<String>>) {
        statements.forEachIndexed { later, statement ->
            val returned = (rows[later].firstOrNull() as? String)?.let { ReturnedStamps.of(it).related }.orEmpty()
            statement.keys.forEachIndexed { index, key ->
                val (found, now) = returned[index]?.let { it.found to it.stamp } ?: return@forEachIndexed
                if (key == null) return@forEachIndexed
                for (earlier in 0 until later) {
                    if (statements[earlier].root.fragmentClass.let { FragmentModel.from(it).stampField } == null) continue
                    if (!statements[earlier].rootKey.sameNodeAs(key)) continue
                    val has = handed[earlier][0]
                    val node = if (Stamps.nodeToken(has) == Stamps.nodeToken(found)) Stamps.nodeToken(now) else Stamps.nodeToken(has)
                    val links = Stamps.linksToken(has)?.let { if (it == Stamps.linksToken(found)) Stamps.linksToken(now) else it }
                    handed[earlier][0] = "$node:${links.orEmpty()}"
                }
            }
        }
    }

    /**
     * Writes the named fields of each object to its node, where the node is there, and makes no node:
     * the write of many nodes that must not bring back one deleted meanwhile. Returns a [PatchResult]
     * for each object, in the order given.
     *
     * The objects are where an id and values are read from, and no more. The id is each object's
     * `@NodeId` field, and for a view the fields are its root's; relationships are not written. A
     * stamp the objects carry is not compared: only the named fields are written, so there is
     * nothing else of the node to overwrite. The objects are not handed back, and the stamp of one
     * whose node changed is then behind the store's: to go on working with an object, load it.
     *
     * A node gets a new stamp only if a value written differed from what it held, and a node that
     * never carried a stamp is given one.
     *
     * The id, the stamp, a `@PropertyBag` and a `@NodeLabels` field cannot be named. The id is taken
     * to be unique: where two nodes of the class share one, the graph engines write both and Oracle
     * the one it finds first.
     *
     * The whole call is atomic, as [saveAll] is: inside a transaction it joins it, and otherwise it
     * runs in one of its own, on an engine that has transactions. FalkorDB has none: there each
     * statement is atomic, and what earlier statements wrote stays written. A call the engine turns
     * away because another writer was changing the same nodes is run again.
     *
     * @param nullPolicy under [NullPolicy.IGNORE], a named field that is null on an object is left as
     *   the store has it; under [NullPolicy.CLEAR] it is cleared
     * @param only the only fields to write
     * @param except fields to leave unwritten: every other field that can be named is written. Give
     *   [only] or [except], and not both: a patch of every field is not what this is for
     * @throws IllegalArgumentException before anything is written, when neither [only] nor [except]
     *   is given, a name is no field that can be patched, the objects are not all of one class, or
     *   an id is null or given twice
     */
    @JvmOverloads
    fun <T : Any> patchAll(
        objs: Collection<T>,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<KProperty1<*, *>> = emptySet(),
        except: Set<KProperty1<*, *>> = emptySet(),
    ): List<PatchResult> {
        objs.firstOrNull()?.let { first -> (only + except).forEach { requireFieldOf(first, it) } }
        // With no object to say the class, the properties do, where they are all of one fragment.
        if (objs.isEmpty()) {
            (only + except).map { it.javaField?.declaringClass ?: it.javaGetter?.declaringClass }.distinct().singleOrNull()
                ?.takeIf { it.isAnnotationPresent(NodeFragment::class.java) }
                ?.let { patchedFields(FragmentModel.from(it), only.map { it.name }.toSet(), except.map { it.name }.toSet()) }
        }
        return patchAllFields(objs, nullPolicy, only.map { it.name }.toSet(), except.map { it.name }.toSet())
    }

    /** [patchAll] with the fields named as strings, for Java. */
    @JvmOverloads
    fun <T : Any> patchAllFields(
        objs: Collection<T>,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<String> = emptySet(),
        except: Set<String> = emptySet(),
    ): List<PatchResult> {
        require(only.isNotEmpty() || except.isNotEmpty()) {
            "A patch names the fields it writes: give only or except. To write every field of an object, save it."
        }
        require(only.isEmpty() || except.isEmpty()) { "Give only or except, not both." }
        val items = objs.toList()
        if (items.isEmpty()) return emptyList()
        val classes = items.map { it.javaClass }.distinct()
        require(classes.size == 1) {
            "A patch writes nodes of one class, and these are of ${classes.joinToString { it.simpleName }}. Patch each class by a call of its own."
        }
        val model = rootModel(classes.single())
        val idField = requireNotNull(model.nodeIdField) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        // In the order the class declares them, so one patch is one text however its fields were named.
        val patched = patchedFields(model, only, except)
        val written = model.fields.filter { it.name in patched }

        val rows = items.mapIndexed { index, item ->
            val root = requireNotNull(stamps.rootOf(item)) { "The root of the ${item.javaClass.simpleName} at $index is null." }
            val values = objectMapper.toMap(root)
            val id = requireNotNull(values[idField]) { "The ${model.clazz.simpleName} at $index has a null id, so there is no node to patch." }
            // Every row holds every named property, a null one too: what a null does is the statement's to say.
            PatchRow(index, id, written.associate { it.propertyName to values[it.name] })
        }
        rows.groupBy { it.id }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw IllegalArgumentException(
                "The ${model.clazz.simpleName} '${same.first().id}' is given ${same.size} times, at ${same.joinToString { it.index.toString() }}. A patch writes each node once."
            )
        }

        // Every attempt's rows are kept: a batch run again may find its own earlier writes. See [PatchOutcomes].
        val outcomes = PatchOutcomes()
        val specs = backend.patchAll(model.clazz, written.map { it.propertyName }, PatchRow.inLockOrder(rows), clears = nullPolicy == NullPolicy.CLEAR)
            .map { spec -> spec.addPostProcessors(object : ResultPostProcessor<Any, Any> { override fun apply(results: List<Any>): List<Any> = outcomes.returned(results) }) }
        val changed = outcomes.changed(executor.batch(specs).flatten())
        return items.indices.map { index -> changed[index]?.let { PatchResult(found = true, applied = true, changed = it) } ?: PatchResult.ABSENT }
    }

    /**
     * Writes named fields of the one [graphClass] node whose id is [id], if the node meets the
     * predicate of [spec], in one statement, and makes no node. The node is not loaded: the store
     * evaluates the predicate, under the node's lock, so there is no loaded state to go stale and no
     * stamp is compared. The lock is the node's own: what a predicate reads of a related node is
     * not locked.
     *
     * ```kotlin
     * val result = graphObjectManager.patch<SessionData>(sessionId) {
     *     fields { query.lastActivityAt setTo activityAt }
     *     where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt activityAt } }
     * }
     * ```
     *
     * The generated `patch<T>(id) { }` passes [queryObject] for you. For a view the fields are its
     * root's: a field of a related node is refused, though the predicate may name a relationship, as
     * a load's does. On a view, [PatchResult.found] says the root node is there, and not that the
     * view would load.
     *
     * A node gets a new stamp only if a value written differed from what it held, so an object
     * loaded before the patch is then refused when it is saved. A node that never carried a stamp is
     * given one, whatever the patch did to it.
     *
     * The id, the stamp, a `@PropertyBag` and a `@NodeLabels` field cannot be written. A value is
     * stored as the object mapper stores one of its type, and a `@JsonPacked` field packed, which is
     * how a save stores the field unless the field says otherwise: one that carries `@JsonSerialize`
     * or `@JsonFormat` is refused. The id is taken to be unique: where two nodes of the class share
     * one, the graph engines write each that meets the predicate, and Oracle the one it finds first.
     *
     * The statement is applied whole or not at all, with or without a transaction, and is run again
     * when the engine turns it away because another writer was changing the node.
     *
     * @param id the node's id, as its `@NodeId` field holds it: a value of the field's own type, which
     *   is found as a save stores it
     * @param queryObject the generated query DSL object of [graphClass], which [spec] reads property
     *   references from
     * @throws IllegalArgumentException before anything is sent, when no field is named, one is named
     *   twice, or one is not a field of the node that a patch can write
     */
    @JvmSynthetic // Java has the overload below: with both in its sight, a lambda with no type given fits either.
    fun <T : Any, Q : Any> patch(id: Any, graphClass: Class<T>, queryObject: Q, spec: PatchSpec<Q>.() -> Unit): PatchResult {
        val patch = PatchSpec(queryObject).apply(spec)
        val values = PatchedValues(graphClass, objectMapper).of(patch.writes)
        // The id as a save stores it: a value class is its value, and a UUID its text.
        val storedId = requireNotNull(objectMapper.toMap(mapOf("id" to id))["id"]) { "The object mapper writes nothing for the id '$id'." }
        val statement = backend.patch(graphClass, storedId, values, patch.conditions)
        val flags = executor.whenNotContended { persistenceManager.query(statement.spec().transform(String::class.java)) }
            .map { FoundStamp.of(it).found }
        if (flags.isEmpty()) return PatchResult.ABSENT
        return PatchResult(found = true, applied = flags.any { it != PatchOutcomes.UNMET }, changed = flags.any { it == PatchOutcomes.CHANGED })
    }

    /** [patch] for Java, whose lambda need return nothing: `manager.patch(id, Claim.class, dsl, spec -> spec.set(dsl.getNote().assign("patched")))`. See [PatchSpec.set], [PatchSpec.onlyIf] and [PatchSpec.onlyIfAny]. */
    fun <T : Any, Q : Any> patch(id: Any, graphClass: Class<T>, queryObject: Q, spec: Consumer<PatchSpec<Q>>): PatchResult =
        patch(id, graphClass, queryObject) { spec.accept(this) }

    /**
     * The fields of [model] a patch writes. This is a rule of its own: a save counts a bag and the
     * labels field among a class's fields, and a patch writes neither, nor the id or the stamp.
     */
    private fun patchedFields(model: FragmentModel, only: Set<String>, except: Set<String>): Set<String> {
        val unpatchable = buildMap {
            model.nodeIdField?.let { put(it, "the id, which a node is found by") }
            model.stampField?.let { put(it, "the stamp, which only a write gives a node") }
            model.propertyBags.forEach { put(it.fieldName, "a @PropertyBag") }
            model.nodeLabels?.let { put(it.fieldName, "the @NodeLabels field") }
        }
        val patchable = model.fields.map { it.name }.toSet() - unpatchable.keys
        (only + except).firstOrNull { it !in patchable && it !in unpatchable }?.let {
            throw IllegalArgumentException("${model.clazz.simpleName} has no field '$it' to patch. Its fields are: ${patchable.sorted().joinToString()}.")
        }
        only.firstOrNull { it in unpatchable }?.let {
            throw IllegalArgumentException(
                "Field '$it' of ${model.clazz.simpleName} is ${unpatchable.getValue(it)}, and a patch does not write it. The fields it can write are: ${patchable.sorted().joinToString()}."
            )
        }
        return only.ifEmpty { patchable - except }.also {
            require(it.isNotEmpty()) { "The fields left out are every field of ${model.clazz.simpleName} a patch can write, so there is nothing to patch." }
        }
    }

    /**
     * Loads the [graphClass] object with [id], applies [change] to it, and writes what the change
     * altered: the fields that differ, a field the change set to null, and for a view the
     * relationships it added or dropped and the related nodes it altered. Of a related node that was
     * loaded, the fields that differ are written and one set to null is cleared; the rest are left, so
     * another writer's change to a field [change] did not touch stands. A relationship another
     * writer added in the meantime is kept. [change] may return a changed copy, or change the object
     * it is given and return that.
     *
     * The save is checked against the stamp that was loaded, whatever [change] does with the object's
     * stamp, and a change that gives the object another id is refused.
     *
     * If the node changed between the load and the save, the object is loaded again and [change] is
     * applied to the fresh one: [attempts] tries in all, and the last failure is thrown. That needs a
     * `@NodeStamp` field: without one a change by another writer is not noticed.
     *
     * An update writes to the node it loaded and never makes one. If the node is deleted between the
     * load and the save, nothing is written and null is returned, as when it was not there to load.
     *
     * A [StaleObjectException] that [change] itself throws, from a save of its own, is thrown on: only
     * the update's own save is tried again.
     *
     * @return the saved object, or null when there is no such node
     */
    @JvmOverloads
    fun <T : Any> update(id: String, graphClass: Class<T>, attempts: Int = 3, change: (T) -> T): T? {
        require(attempts > 0) { "attempts must be positive, was $attempts" }
        var last: StaleObjectException? = null
        repeat(attempts) { attempt ->
            val loaded = load(id, graphClass) ?: return null
            // Digested before the change runs: a change may alter the loaded object itself.
            val loadedClass = loaded.javaClass
            val before = digests.digestOf(loaded)
            val loadedStamp = stamps.stampOf(loaded)
            // Outside the try: a stale save the change makes itself is not this update's to try again.
            val changed = change(loaded)
            try {
                return saveChanged(loadedClass, before, loadedStamp, changed)
            } catch (stale: StaleObjectException) {
                // A stamped node that was deleted is found stale, not gone: there is no node to try again on.
                if (stale.deleted) {
                    logger.debug("{} '{}' was deleted during update", graphClass.simpleName, id, stale)
                    return null
                }
                last = stale
                logger.debug("{} '{}' changed during update, attempt {} of {}", graphClass.simpleName, id, attempt + 1, attempts, stale)
            } catch (gone: SaveExecutor.RootGone) {
                logger.debug("{} '{}' was deleted during update", graphClass.simpleName, id, gone)
                return null
            }
        }
        throw checkNotNull(last)
    }

    /** [update] of the object whose id is [id] as a string. */
    @JvmOverloads
    fun <T : Any> update(id: UUID, graphClass: Class<T>, attempts: Int = 3, change: (T) -> T): T? =
        update(id.toString(), graphClass, attempts, change)

    /**
     * Saves what differs between an object as it was loaded, digested as [before] and carrying
     * [loadedStamp], and as the change returned it.
     */
    private fun <T : Any> saveChanged(loadedClass: Class<*>, before: JsonNode, loadedStamp: String?, returned: T): T {
        require(loadedClass == returned.javaClass) {
            "The change returned a ${returned.javaClass.simpleName} for a ${loadedClass.simpleName}."
        }
        val rootField = if (loadedClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(loadedClass).rootFragment.fieldName else null
        val rootBefore = rootField?.let { before.get(it) } ?: before
        val idField = requireNotNull(rootModel(loadedClass).nodeIdField)
        val loadedId = rootBefore.get(idField)?.asText()
        val returnedId = objectMapper.toMap(requireNotNull(stamps.rootOf(returned)) { "Root fragment $rootField is null" })[idField]?.toString()
        require(returnedId == loadedId) {
            "The change gave the ${loadedClass.simpleName} loaded as '$loadedId' the id '$returnedId'. An update writes to the node it loaded: save an object with another id as a new one."
        }
        // The save is checked against the stamp that was loaded, whatever the change did with the
        // object's: a change that builds the object anew, and leaves the stamp out, is checked all the same.
        val changed = if (loadedStamp != null && stamps.stampOf(returned) != loadedStamp) {
            stamps.of(returned, IdentityHashMap<Any, String>().apply { put(stamps.rootOf(returned), loadedStamp) })
        } else {
            returned
        }

        // Only the fields the change altered are written, and under CLEAR so that a field it set to
        // null is cleared. A field that was null and still is, is not touched.
        val rootAfter = requireNotNull(stamps.rootOf(changed)) { "Root fragment $rootField is null" }
        val dirty = digests.computeDirtyFields(rootAfter, rootBefore)

        // The node was loaded, so it is written only if it is still there: an update makes no node.
        val statement = backend.save(SaveRequest.changed(changed, before, digests.digestOf(changed), dirty))
        requireStampable(changed, statement)
        return stamped(changed, statement, executor.save(statement))
    }

    private fun statementFor(obj: Any, relationships: RelationshipWrite, nullPolicy: NullPolicy, writeFields: Set<String>?, checked: Boolean): SaveStatement {
        val replace = relationships as? Replace
        val replaced = replace?.let { replacedFields(obj, it) }.orEmpty().map { it.fieldName }.toSet()
        return backend.save(
            SaveRequest(obj, checked, nullPolicy, writeFields, replaced = { relationship -> replace?.removedTargets?.takeIf { relationship.fieldName in replaced } }),
        )
    }

    /** [obj] carrying the stamps its save returned: the root's first, then each stamped target's, then each repeat's. */
    private fun <T : Any> stamped(obj: T, statement: SaveStatement, returned: List<String>): T {
        val bySaved = IdentityHashMap<Any, String>()
        handedStamps(statement).forEachIndexed { index, fragment -> returned.getOrNull(index + 1)?.let { bySaved[fragment] = it } }
        // The root last: where the object holds its own root in a list too, both are handed the same.
        stamps.rootOf(obj)?.let { bySaved[it] = returned.first() }
        return stamps.of(obj, bySaved)
    }

    /** The fragments [statement] hands a stamp back to, the root aside, in the order [SaveExecutor.stamps] gives them. */
    private fun handedStamps(statement: SaveStatement): List<Any> = statement.stamped + statement.repeats.map { it.fragment }

    /** Refuses [obj] before its save when it could not be handed the stamps the save would leave. */
    private fun requireStampable(obj: Any, statement: SaveStatement) =
        stamps.requireStampable(obj, handedStamps(statement) + listOfNotNull(stamps.rootOf(obj)))

    /** A property names a field of the object that is saved: of a view, of its root. */
    private fun requireFieldOf(obj: Any, property: KProperty1<*, *>) {
        val owner = property.javaField?.declaringClass ?: property.javaGetter?.declaringClass ?: return
        val root = stamps.rootOf(obj)?.javaClass ?: return
        require(owner.isAssignableFrom(root)) {
            "${owner.simpleName}::${property.name} is not a field of ${root.simpleName}, which is what is saved. Name fields of ${root.simpleName}."
        }
    }

    private fun rootModel(clazz: Class<*>): FragmentModel =
        if (clazz.isAnnotationPresent(GraphView::class.java)) {
            FragmentModel.from(GraphViewModel.from(clazz).rootFragment.fragmentType)
        } else {
            FragmentModel.from(clazz)
        }

    /** The root fields a save may touch, or null for all of them. */
    private fun writeFields(obj: Any, only: Set<String>, except: Set<String>): Set<String>? = writeFields(obj.javaClass, only, except)

    /**
     * The root fields a batch may touch of an object of [clazz], or null for all of them. The id and
     * the stamp are refused in [only]: neither is written from the object, so a batch that named them
     * alone would make a node of each object and write none of its fields.
     */
    private fun batchWriteFields(clazz: Class<*>, only: Set<String>, except: Set<String>): Set<String>? {
        val fields = writeFields(clazz, only, except)
        val model = rootModel(clazz)
        only.firstOrNull { it == model.nodeIdField || it == model.stampField }?.let {
            val what = if (it == model.nodeIdField) "the id, which a node is found by" else "the stamp, which only a write gives a node"
            throw IllegalArgumentException("'$it' of ${model.clazz.simpleName} is $what, and cannot be named in only. Name the fields to write.")
        }
        return fields
    }

    private fun writeFields(clazz: Class<*>, only: Set<String>, except: Set<String>): Set<String>? {
        if (only.isEmpty() && except.isEmpty()) return null
        require(only.isEmpty() || except.isEmpty()) { "Give only or except, not both." }
        val model = rootModel(clazz)
        val all = (model.fields.map { it.name } + model.propertyBags.map { it.fieldName } + listOfNotNull(model.nodeLabels?.fieldName)).toSet()
        (only + except).firstOrNull { it !in all }?.let {
            throw IllegalArgumentException("${model.clazz.simpleName} has no field '$it' to save. Its fields are: ${all.sorted().joinToString()}.")
        }
        return only.ifEmpty { all - except }
    }

    /** The relationship fields a [Replace] covers, or an error that says why it cannot be applied. */
    private fun replacedFields(obj: Any, replace: Replace): List<RelationshipModel> {
        val clazz = obj.javaClass
        require(clazz.isAnnotationPresent(GraphView::class.java)) {
            "Replace applies to the relationship fields of a @GraphView, and ${clazz.simpleName} is not one."
        }
        val relationships = GraphViewModel.from(clazz).relationships
        if (replace.everyField) {
            require(rootModel(clazz).stampField != null) {
                """
                Replace.all() needs a root with a @NodeStamp field, and the root of ${clazz.simpleName} declares none.
                A stamp is what shows that an object's lists came from the store.
                Add a @NodeStamp field to the root, or name the fields to replace: Replace(${clazz.simpleName}::field).
                """.trimIndent()
            }
            require(stamps.stampOf(obj) != null) {
                """
                Replace.all() needs an object that carries a stamp, and this ${clazz.simpleName} has none.
                Without one its lists may be the declared defaults and not what the store holds, so replacing with them could remove every relationship.
                Load the object first, or name the fields to replace: Replace(${clazz.simpleName}::field).
                """.trimIndent()
            }
            return relationships.filterNot { it.readOnly }.onEach { requireList(obj, it) }
        }
        return replace.fields.map { name ->
            val relationship = relationships.firstOrNull { it.fieldName == name }
                ?: throw IllegalArgumentException("${clazz.simpleName} has no relationship field '$name' to replace.")
            require(!relationship.readOnly) {
                "Field '$name' of ${clazz.simpleName} is read-only: it is loaded and never written, so it cannot be replaced."
            }
            requireList(obj, relationship)
            relationship
        }
    }

    /** A list that was never set is not an empty list: replacing with it would remove everything. */
    private fun requireList(obj: Any, relationship: RelationshipModel) =
        require(!relationship.isCollection || read(obj, relationship.fieldName) != null) {
            "Field '${relationship.fieldName}' of this ${obj.javaClass.simpleName} is null. To remove every relationship of the field, give it an empty list."
        }
}

/** Loads, changes and saves a graph object, with a reified type. See [StatelessGraphObjectManager.update]. */
inline fun <reified T : Any> StatelessGraphObjectManager.update(id: String, attempts: Int = 3, noinline change: (T) -> T): T? =
    update(id, T::class.java, attempts, change)

/** [update] of the object whose id is [id] as a string, with a reified type. */
inline fun <reified T : Any> StatelessGraphObjectManager.update(id: UUID, attempts: Int = 3, noinline change: (T) -> T): T? =
    update(id, T::class.java, attempts, change)
