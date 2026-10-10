package org.drivine.manager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.StaleObjectException
import org.drivine.annotation.GraphView
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.SaveStatement
import org.drivine.query.SaveStatementBuilder
import org.drivine.query.Stamping
import org.drivine.query.read
import org.drivine.session.SessionManager
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import kotlin.reflect.KProperty1

/**
 * An object manager that remembers nothing between calls. What a save writes is decided by the
 * object and the arguments, never by whether the object was loaded here before.
 *
 * - [save] writes every field the object has and adds the relationships it holds. It removes a
 *   relationship only when asked to, with [Replace].
 * - A save is one statement: the root, the relationships it drops, each related node and the
 *   relationship to it. It is applied whole or not at all, with or without a transaction.
 * - A fragment that declares a `@NodeStamp` field is checked: a save of an object that carries a
 *   stamp applies only if the node still has it, and otherwise throws [StaleObjectException]. In a
 *   view, the root is checked. A node reached through a relationship is written unchecked.
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
     * The root's stamp also changes when the save adds or removes one of its relationships, or
     * changes a relationship's properties.
     *
     * @param relationships [Add] (the default) adds the relationships the object holds and removes
     *   none. [Replace] names the relationship fields whose list is the whole list.
     * @param nullPolicy how a null field is treated; see [NullPolicy]
     * @param only the only fields of the object (for a view, of its root) to write. The relationships
     *   of a view are written whatever this names
     * @param except fields of the object (for a view, of its root) to leave unwritten
     */
    @JvmOverloads
    fun <T : Any> save(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<KProperty1<*, *>> = emptySet(),
        except: Set<KProperty1<*, *>> = emptySet(),
    ): T = saveFields(obj, relationships, nullPolicy, only.map { it.name }.toSet(), except.map { it.name }.toSet())

    /** [save] with the fields named as strings, for Java. A Java caller that names no field calls [save]. */
    @JvmOverloads
    fun <T : Any> saveFields(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<String> = emptySet(),
        except: Set<String> = emptySet(),
    ): T {
        // Everything is validated before anything is written.
        val statement = statementFor(obj, relationships, nullPolicy, writeFields(obj, only, except), checked = true)
        return stamped(obj, statement, executor.save(statement))
    }

    /**
     * Saves each object, and returns each carrying the stamps its save left, as [save] does. The saves
     * are not checked: an object that carries a stale stamp is written all the same.
     *
     * The whole call is atomic: inside a transaction it joins it, and otherwise it runs in one of its
     * own, on an engine that has transactions. A [Replace] is part of it.
     *
     * Fragments with no `@NodeStamp` field are saved in batches. A view, and a fragment that declares
     * a stamp, is saved by a statement of its own, which is what hands its stamps back.
     */
    @JvmOverloads
    fun <T : Any> saveAll(
        objs: Collection<T>,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): List<T> {
        val items = objs.toList()
        if (items.isEmpty()) return emptyList()
        val (single, batched) = items.partition { savedAlone(it) }
        require(relationships !is Replace || batched.isEmpty()) {
            "Replace applies to the relationship fields of a @GraphView, and ${batched.first().javaClass.simpleName} is not one."
        }
        val singles = single.map { statementFor(it, relationships, nullPolicy, null, checked = false) }
        val rows = persistenceManager.queryBatch(objects.batchSpecs(batched, nullPolicy) + singles.map { executor.spec(it) })
            .takeLast(singles.size)

        val saved = IdentityHashMap<Any, Any>()
        single.forEachIndexed { index, item -> saved[item] = stamped(item, singles[index], executor.stamps(singles[index], rows[index])) }
        @Suppress("UNCHECKED_CAST")
        return items.map { (saved[it] ?: it) as T }
    }

    /** Whether [obj] is saved by a statement of its own in a [saveAll]: it has relationships, or a stamp to hand back. */
    private fun savedAlone(obj: Any): Boolean =
        obj.javaClass.isAnnotationPresent(GraphView::class.java) || FragmentModel.from(obj.javaClass).stampField != null

    /**
     * Loads the [graphClass] object with [id], applies [change] to it, and writes what the change
     * altered: the fields that differ, a field the change set to null, and for a view the
     * relationships it added or dropped and the related nodes it altered. A relationship another
     * writer added in the meantime is kept. [change] may return a changed copy, or change the object
     * it is given and return that.
     *
     * If the node changed between the load and the save, the object is loaded again and [change] is
     * applied to the fresh one: [attempts] tries in all, and the last failure is thrown. That needs a
     * `@NodeStamp` field: without one a change by another writer is not noticed.
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
            try {
                return saveChanged(loadedClass, before, change(loaded))
            } catch (stale: StaleObjectException) {
                last = stale
                logger.debug("{} '{}' changed during update, attempt {} of {}", graphClass.simpleName, id, attempt + 1, attempts, stale)
            }
        }
        throw checkNotNull(last)
    }

    /** Saves what differs between an object as it was loaded, digested as [before], and as it is [changed]. */
    private fun <T : Any> saveChanged(loadedClass: Class<*>, before: JsonNode, changed: T): T {
        require(loadedClass == changed.javaClass) {
            "The change returned a ${changed.javaClass.simpleName} for a ${loadedClass.simpleName}."
        }
        val rootField = if (loadedClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(loadedClass).rootFragment.fieldName else null

        // Only the fields the change altered are written, and under CLEAR so that a field it set to
        // null is cleared. A field that was null and still is, is not touched.
        val rootBefore = rootField?.let { before.get(it) } ?: before
        val rootAfter = requireNotNull(stamps.rootOf(changed)) { "Root fragment $rootField is null" }
        val dirty = digests.computeDirtyFields(rootAfter, rootBefore)

        val statement = statements.build(
            changed, checked = true, NullPolicy.CLEAR, dirty, before = before, after = digests.digestOf(changed),
        )
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

    /** [obj] carrying the stamps its save returned: the root's first, then each stamped target's. */
    private fun <T : Any> stamped(obj: T, statement: SaveStatement, returned: List<String>): T {
        val bySaved = IdentityHashMap<Any, String>()
        stamps.rootOf(obj)?.let { bySaved[it] = returned.first() }
        statement.stamped.forEachIndexed { index, fragment -> returned.getOrNull(index + 1)?.let { bySaved[fragment] = it } }
        return stamps.of(obj, bySaved)
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
            return relationships.filterNot { it.readOnly }
        }
        return replace.fields.map { name ->
            val relationship = relationships.firstOrNull { it.fieldName == name }
                ?: throw IllegalArgumentException("${clazz.simpleName} has no relationship field '$name' to replace.")
            require(!relationship.readOnly) {
                "Field '$name' of ${clazz.simpleName} is read-only: it is loaded and never written, so it cannot be replaced."
            }
            // A list that was never set is not an empty list: replacing with it would remove everything.
            require(!relationship.isCollection || read(obj, name) != null) {
                "Field '$name' of this ${clazz.simpleName} is null. To remove every relationship of the field, give it an empty list."
            }
            relationship
        }
    }
}

/** Loads, changes and saves a graph object, with a reified type. See [StatelessGraphObjectManager.update]. */
inline fun <reified T : Any> StatelessGraphObjectManager.update(id: String, attempts: Int = 3, noinline change: (T) -> T): T? =
    update(id, T::class.java, attempts, change)
