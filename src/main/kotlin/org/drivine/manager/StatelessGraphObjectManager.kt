package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.StaleObjectException
import org.drivine.annotation.GraphView
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.MergeStatement
import org.drivine.query.Stamping
import org.drivine.session.SessionManager
import org.slf4j.LoggerFactory
import kotlin.reflect.KProperty1

/**
 * An object manager that remembers nothing between calls. What a save writes is decided by the
 * object and the arguments, never by whether the object was loaded here before.
 *
 * - [save] writes every field the object has and adds the relationships it holds. It removes a
 *   relationship only when asked to, with [Replace].
 * - A fragment that declares a `@NodeStamp` field is checked: a save of an object that carries a
 *   stamp applies only if the node still has it, and otherwise throws [StaleObjectException]. In a
 *   view, the root is checked. A node reached through a relationship is written unchecked.
 * - [update] loads an object, applies a change, and writes only what the change altered.
 */
class StatelessGraphObjectManager private constructor(
    private val persistenceManager: PersistenceManager,
    private val objectMapper: ObjectMapper,
    /** Loads, queries, deletes and batch saves: with a session that remembers nothing, each is stateless. */
    private val objects: GraphObjectManager,
) : GraphObjectOperations by objects {

    constructor(persistenceManager: PersistenceManager, objectMapper: ObjectMapper, subtypeRegistry: SubtypeRegistry) : this(
        persistenceManager,
        objectMapper,
        GraphObjectManager(persistenceManager, SessionManager.untracked(objectMapper), objectMapper, subtypeRegistry, Stamping(checked = false)),
    )

    private val logger = LoggerFactory.getLogger(StatelessGraphObjectManager::class.java)
    private val untracked = objects.sessionManager
    private val executor = SaveExecutor(persistenceManager)
    private val stamps = StampedCopy(objectMapper)
    private val replacer = RelationshipReplacer(persistenceManager, objectMapper)

    /**
     * Saves [obj] and returns it carrying its new stamp. Use the returned object from then on: the
     * one passed in still carries the stamp the node no longer has.
     *
     * @param relationships [Add] (the default) adds the relationships the object holds and removes
     *   none. [Replace] names the relationship fields whose list is the whole list.
     * @param nullPolicy how a null field is treated; see [NullPolicy]
     * @param only the only fields of the object (for a view, of its root) to write
     * @param except fields of the object (for a view, of its root) to leave unwritten
     */
    fun <T : Any> save(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<KProperty1<*, *>> = emptySet(),
        except: Set<KProperty1<*, *>> = emptySet(),
    ): T = saveFields(obj, relationships, nullPolicy, only.map { it.name }.toSet(), except.map { it.name }.toSet())

    /** [save] with the fields named as strings, for Java. */
    @JvmOverloads
    fun <T : Any> saveFields(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        only: Set<String> = emptySet(),
        except: Set<String> = emptySet(),
    ): T {
        // Everything is validated before anything is written.
        val replaced = (relationships as? Replace)?.let { replacedFields(obj, it) }.orEmpty()
        val statements = GraphObjectMergeBuilder.forClass(
            obj.javaClass, objectMapper, untracked, objects.grammar, objects.storedKeys,
            Stamping(checked = true), writeFields(obj, only, except),
        ).buildMergeStatements(obj, CascadeType.NONE, nullPolicy)

        // The root first: if it is stale, nothing else is written.
        executor.execute(statements.first())
        if (relationships is Replace) {
            val viewModel = GraphViewModel.from(obj.javaClass)
            replaced.forEach { replacer.replace(obj, viewModel, it, relationships.removedTargets) }
        }
        executor.execute(statements.drop(1))
        return stamped(obj, statements)
    }

    /**
     * Saves each object, in batches. Every node gets a new stamp. The saves are not checked: a batch
     * cannot say which of its rows found the stamp it expected.
     */
    @JvmOverloads
    fun <T : Any> saveAll(objs: Collection<T>, nullPolicy: NullPolicy = NullPolicy.IGNORE): List<T> =
        objects.saveAll(objs, CascadeType.NONE, nullPolicy)

    /**
     * Loads the [graphClass] object with [id], applies [change] to it, and writes what the change
     * altered: the fields that differ, a field the change set to null, and for a view the
     * relationships it added or dropped. A relationship another writer added in the meantime is kept.
     *
     * If the node changed between the load and the save, the object is loaded again and [change] is
     * applied to the fresh one, up to [attempts] times; the last failure is thrown. That needs a
     * `@NodeStamp` field: without one a change by another writer is not noticed.
     *
     * @return the saved object, or null when there is no such node
     */
    @JvmOverloads
    fun <T : Any> update(id: String, graphClass: Class<T>, attempts: Int = 3, change: (T) -> T): T? {
        require(attempts > 0) { "attempts must be positive, was $attempts" }
        repeat(attempts - 1) { attempt ->
            val loaded = load(id, graphClass) ?: return null
            try {
                return saveChanged(loaded, change(loaded))
            } catch (stale: StaleObjectException) {
                logger.debug("{} '{}' changed during update, attempt {} of {}: loading it again", graphClass.simpleName, id, attempt + 1, attempts, stale)
            }
        }
        val loaded = load(id, graphClass) ?: return null
        return saveChanged(loaded, change(loaded))
    }

    /** Saves what differs between [loaded] and [changed], through a snapshot that lives for this call only. */
    private fun <T : Any> saveChanged(loaded: T, changed: T): T {
        require(loaded.javaClass == changed.javaClass) {
            "The change returned a ${changed.javaClass.simpleName} for a ${loaded.javaClass.simpleName}."
        }
        val clazz = loaded.javaClass
        val rootField = if (clazz.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(clazz).rootFragment.fieldName else null
        val rootModel = rootModel(clazz)
        val session = SessionManager(objectMapper)
        session.snapshot(loaded, rootModel, rootField)

        // Only the fields the change altered are written, and under CLEAR so that a field it set to
        // null is cleared. A field that was null and still is, is not touched.
        val before = session.digestOf(loaded).let { digest -> rootField?.let { digest.get(it) } ?: digest }
        val rootAfter = rootField?.let { clazz.getDeclaredField(it).apply { isAccessible = true }.get(changed) } ?: changed
        val dirty = session.computeDirtyFields(rootAfter, before)

        val statements = GraphObjectMergeBuilder.forClass(
            clazz, objectMapper, session, objects.grammar, objects.storedKeys, Stamping(checked = true), dirty,
        ).buildMergeStatements(changed, CascadeType.NONE, NullPolicy.CLEAR)
        executor.execute(statements)
        return stamped(changed, statements)
    }

    private fun <T : Any> stamped(obj: T, statements: List<MergeStatement>): T =
        statements.first().stamp?.let { stamps.of(obj, it.written) } ?: obj

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
                "Field '$name' of ${clazz.simpleName} is @ReadOnly: it is loaded and never written, so it cannot be replaced."
            }
            relationship
        }
    }
}

/** Loads, changes and saves a graph object, with a reified type. See [StatelessGraphObjectManager.update]. */
inline fun <reified T : Any> StatelessGraphObjectManager.update(id: String, attempts: Int = 3, noinline change: (T) -> T): T? =
    update(id, T::class.java, attempts, change)
