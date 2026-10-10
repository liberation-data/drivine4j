package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.read
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions

/**
 * Gives a saved object the stamps its save left: on its root, and on each related node that declares a
 * `@NodeStamp` field.
 *
 * A Kotlin data class is copied, as its `copy` would. Any other class whose fields can be set is given
 * the stamps in place, as a Java object with setters expects. Only an immutable class that is not a data
 * class is rebuilt by the object mapper.
 */
internal class StampedCopy(private val objectMapper: ObjectMapper) {

    /** [obj] with [stamps] set, each on the fragment it is keyed by. Fragments are told apart by identity. */
    fun <T : Any> of(obj: T, stamps: IdentityHashMap<Any, String>): T {
        @Suppress("UNCHECKED_CAST")
        return stamped(obj, stamps) as T
    }

    /** The stamp [obj]'s root carries, or null when it has none or declares no stamp field. */
    fun stampOf(obj: Any): String? {
        val fragment = rootOf(obj) ?: return null
        val field = FragmentModel.from(fragment.javaClass).stampField ?: return null
        return objectMapper.toMap(fragment)[field] as? String
    }

    /** The root fragment of [obj]: of a view its root, of a fragment itself. */
    fun rootOf(obj: Any): Any? =
        if (obj.javaClass.isAnnotationPresent(GraphView::class.java)) {
            read(obj, GraphViewModel.from(obj.javaClass).rootFragment.fieldName)
        } else {
            obj
        }

    private fun stamped(obj: Any, stamps: IdentityHashMap<Any, String>): Any {
        if (!obj.javaClass.isAnnotationPresent(GraphView::class.java)) {
            val stamp = stamps[obj] ?: return obj
            val field = FragmentModel.from(obj.javaClass).stampField ?: return obj
            return with(obj, mapOf(field to stamp))
        }
        val model = GraphViewModel.from(obj.javaClass)
        val changes = linkedMapOf<String, Any?>()
        read(obj, model.rootFragment.fieldName)?.let { root ->
            stamped(root, stamps).takeIf { it !== root }?.let { changes[model.rootFragment.fieldName] = it }
        }
        model.relationships.filterNot { it.readOnly }.forEach { relationship ->
            val value = read(obj, relationship.fieldName) ?: return@forEach
            val now = if (relationship.isCollection) items(value as Collection<*>, relationship, stamps) else item(value, relationship, stamps)
            if (now !== value) changes[relationship.fieldName] = now
        }
        return if (changes.isEmpty()) obj else with(obj, changes)
    }

    private fun items(items: Collection<*>, relationship: RelationshipModel, stamps: IdentityHashMap<Any, String>): Collection<*> {
        val now = items.map { it?.let { item -> item(item, relationship, stamps) } }
        if (now.indices.all { now[it] === items.elementAt(it) }) return items
        return if (items is Set<*>) now.toCollection(LinkedHashSet()) else now
    }

    private fun item(item: Any, relationship: RelationshipModel, stamps: IdentityHashMap<Any, String>): Any {
        if (!relationship.isRelationshipFragment) return stamped(item, stamps)
        val targetField = requireNotNull(relationship.targetFieldName)
        val target = read(item, targetField) ?: return item
        val now = stamped(target, stamps)
        return if (now === target) item else with(item, mapOf(targetField to now))
    }

    private fun <T : Any> with(obj: T, changes: Map<String, Any?>): T {
        val kClass = obj::class
        val copy = kClass.memberFunctions.firstOrNull { it.name == "copy" }.takeIf { kClass.isData }
        val parameters = copy?.let { function -> changes.keys.map { name -> function.parameters.firstOrNull { it.name == name } } }
        if (copy != null && parameters != null && parameters.none { it == null }) {
            val arguments = mapOf(requireNotNull(copy.instanceParameter) to obj) + parameters.filterNotNull().associateWith { changes[it.name] }
            @Suppress("UNCHECKED_CAST")
            return copy.callBy(arguments) as T
        }
        val fields = changes.keys.map { name -> field(obj.javaClass, name) }
        if (fields.none { it == null || Modifier.isFinal(it.modifiers) }) {
            fields.filterNotNull().forEach { it.apply { isAccessible = true }.set(obj, changes[it.name]) }
            return obj
        }
        return try {
            objectMapper.convertValue(objectMapper.toMap(obj) + changes, obj.javaClass)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(
                "${obj.javaClass.simpleName} was saved, but a copy carrying its new stamp could not be made. " +
                    "Make it a Kotlin data class, or give it fields that can be set.",
                failure,
            )
        }
    }

    private fun field(type: Class<*>, name: String): java.lang.reflect.Field? =
        generateSequence<Class<*>>(type) { it.superclass }.firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == name } }
}
