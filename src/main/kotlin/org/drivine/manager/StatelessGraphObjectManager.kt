package org.drivine.manager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.StaleObjectException
import org.drivine.annotation.GraphView
import org.drivine.mapper.SubtypeRegistry
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.SaveStatement
import org.drivine.query.SaveStatementBuilder
import org.drivine.query.Stamping
import org.drivine.query.read
import org.drivine.query.savedByUnwind
import org.drivine.session.SessionManager
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import java.util.UUID
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
    private val executor = SaveExecutor(persistenceManager)
    private val stamps = StampedCopy(objectMapper)
    private val statements = SaveStatementBuilder(objectMapper, objects.grammar, objects.storedKeys)

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
     * @param except fields of the object (for a view, of its root) to leave unwritten
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

    /** [save] with the fields named as strings, for Java. A Java caller that names no field calls [save]. */
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
     * loaded, [StaleObjectException] is thrown and the batch is not applied.
     *
     * The objects are saved in the order given, and a view's save changes the nodes it holds. So a
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
     */
    @JvmOverloads
    fun <T : Any> saveAll(
        objs: Collection<T>,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): List<T> {
        val items = objs.toList()
        if (items.isEmpty()) return emptyList()
        val views = items.filter { it.javaClass.isAnnotationPresent(GraphView::class.java) }
        val fragments = items.withIndex().filterNot { it.value.javaClass.isAnnotationPresent(GraphView::class.java) }
        require(relationships !is Replace || fragments.isEmpty()) {
            "Replace applies to the relationship fields of a @GraphView, and ${fragments.first().value.javaClass.simpleName} is not one."
        }
        // A stamp is handed back by a statement that returns it: one for a batch of fragments, and one
        // for each view, and each fragment a batch cannot write.
        val (stamped, plain) = fragments.partition { FragmentModel.from(it.value.javaClass).stampField != null }
        val (batched, alone) = stamped.partition { FragmentModel.from(it.value.javaClass).savedByUnwind(objects.grammar) }
        val single = views + alone.map { it.value }
        // A view whose list is replaced is checked, as a save of it is: a replace overwrites the
        // list, and must not remove a relationship the object never held.
        val singles = single.map { statementFor(it, relationships, nullPolicy, null, checked = relationships is Replace) }
        // That each object can be handed its stamps is known before anything is written.
        batched.forEach { stamps.requireStampable(it.value, listOf(it.value)) }
        single.forEachIndexed { index, item -> requireStampable(item, singles[index]) }
        val plainSpecs = objects.batchSpecs(plain.map { it.value }, nullPolicy)
        val batchedSpecs = objects.stampedBatchSpecs(batched, nullPolicy)
        val rows = executor.batch(
            plainSpecs + batchedSpecs + singles.map { executor.spec(it, inBatch = true) },
            offered = singles.flatMap { it.offered },
        )

        // A node the batch holds more than once is written by more than one row, and a later row
        // finds what an earlier one wrote: what the node carried before the batch is what the first
        // found, and what it is left with is what the last wrote.
        val batchedRows = rows.subList(plainSpecs.size, plainSpecs.size + batchedSpecs.size).flatten().map { row ->
            val (key, stamp) = (row as String).split('=', limit = 2)
            Triple(items[key.substringBefore('/').toInt()], key.substringAfter('/'), stamp)
        }
        val found = linkedMapOf<Pair<Class<*>, Any?>, String>()
        val left = linkedMapOf<Pair<Class<*>, Any?>, String>()
        fun nodeOf(item: Any) = item.javaClass to objectMapper.toMap(item)[FragmentModel.from(item.javaClass).nodeIdField]
        batchedRows.forEach { (item, itemFound, stamp) ->
            found.putIfAbsent(nodeOf(item), itemFound)
            left[nodeOf(item)] = stamp
        }
        // The stamp each object is handed, keyed by the object: one given twice is handed it once.
        val handed = IdentityHashMap<Any, String>()
        batchedRows.forEach { (item, _, _) ->
            // A batch is not checked, so the node may not have been as the object's stamp says. Then
            // the object keeps its own token: the stamp handed back does not vouch for data or
            // relationships that changed after the object was loaded.
            handed[item] = Stamps.handedBack(stamps.stampOf(item), found.getValue(nodeOf(item)), left.getValue(nodeOf(item)))
        }
        val saved = IdentityHashMap<Any, Any>()
        handed.forEach { (item, stamp) -> saved[item] = stamps.of(item, IdentityHashMap<Any, String>().apply { put(item, stamp) }) }

        val singleRows = rows.takeLast(singles.size)
        val singleStamps = singles.indices.map { executor.stamps(singles[it], singleRows[it]).toMutableList() }
        carryForward(singles, singleRows, singleStamps)
        single.forEachIndexed { index, item -> saved[item] = stamped(item, singles[index], singleStamps[index]) }
        @Suppress("UNCHECKED_CAST")
        return items.map { (saved[it] ?: it) as T }
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
            val returned = (rows[later].firstOrNull() as? String)?.split(',')?.drop(1).orEmpty().associate { entry ->
                val (key, stamp) = entry.split('=', limit = 2)
                key.substringBefore('/').toInt() to (key.substringAfter('/') to stamp)
            }
            statement.keys.forEachIndexed { index, key ->
                val (found, now) = returned[index] ?: return@forEachIndexed
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
        val statement = statements.build(
            changed, checked = true, NullPolicy.CLEAR, dirty, before = before, after = digests.digestOf(changed), createsRoot = false,
        )
        requireStampable(changed, statement)
        return stamped(changed, statement, executor.save(statement))
    }

    private fun statementFor(obj: Any, relationships: RelationshipWrite, nullPolicy: NullPolicy, writeFields: Set<String>?, checked: Boolean): SaveStatement {
        val replace = relationships as? Replace
        val replaced = replace?.let { replacedFields(obj, it) }.orEmpty().map { it.fieldName }.toSet()
        return statements.build(
            obj, checked, nullPolicy, writeFields,
            replaced = { relationship -> replace?.removedTargets?.takeIf { relationship.fieldName in replaced } },
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
    private fun writeFields(obj: Any, only: Set<String>, except: Set<String>): Set<String>? {
        if (only.isEmpty() && except.isEmpty()) return null
        require(only.isEmpty() || except.isEmpty()) { "Give only or except, not both." }
        val model = rootModel(obj.javaClass)
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
