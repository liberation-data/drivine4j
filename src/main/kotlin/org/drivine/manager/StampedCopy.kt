package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions

/**
 * Gives a saved object the stamp its save wrote. The object is not changed: a copy carries the new
 * stamp, as a Kotlin data class's `copy` would make it, and otherwise as the object mapper rebuilds it.
 */
internal class StampedCopy(private val objectMapper: ObjectMapper) {

    /** [obj] with its root's `@NodeStamp` field set to [stamp]; [obj] itself when it declares none. */
    fun <T : Any> of(obj: T, stamp: String): T {
        val clazz = obj.javaClass
        if (!clazz.isAnnotationPresent(GraphView::class.java)) {
            val field = FragmentModel.from(clazz).stampField ?: return obj
            return with(obj, field, stamp)
        }
        val root = GraphViewModel.from(clazz).rootFragment
        val stampField = FragmentModel.from(root.fragmentType).stampField ?: return obj
        val rootFragment = clazz.getDeclaredField(root.fieldName).apply { isAccessible = true }.get(obj) ?: return obj
        return with(obj, root.fieldName, with(rootFragment, stampField, stamp))
    }

    /** The stamp [obj]'s root carries, or null when it has none or declares no stamp field. */
    fun stampOf(obj: Any): String? {
        val clazz = obj.javaClass
        val fragment = if (clazz.isAnnotationPresent(GraphView::class.java)) {
            val root = GraphViewModel.from(clazz).rootFragment
            clazz.getDeclaredField(root.fieldName).apply { isAccessible = true }.get(obj) ?: return null
        } else {
            obj
        }
        val field = FragmentModel.from(fragment.javaClass).stampField ?: return null
        return objectMapper.toMap(fragment)[field] as? String
    }

    private fun <T : Any> with(obj: T, field: String, value: Any): T {
        val kClass = obj::class
        val copy = kClass.memberFunctions.firstOrNull { it.name == "copy" }.takeIf { kClass.isData }
        val parameter = copy?.parameters?.firstOrNull { it.name == field }
        @Suppress("UNCHECKED_CAST")
        return if (copy != null && parameter != null) {
            copy.callBy(mapOf(requireNotNull(copy.instanceParameter) to obj, parameter to value)) as T
        } else {
            objectMapper.convertValue(objectMapper.toMap(obj) + (field to (value as? String ?: objectMapper.toMap(value))), obj.javaClass)
        }
    }
}
