package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import java.util.UUID

/**
 * The ids of a search among ids in the `@NodeId` type's own order, as the shared module reads their places: which ids are
 * after an id ([AmongNeighbours.AFTER]), and of two neighbours that score exactly alike, which comes first
 * (liberation-data/drivine-cypher-spec#412). A text is ordered by its code points, not its UTF-16 units, a number by its
 * value, a temporal by its time, and a `UUID` or an enum by its text, as a graph engine holds it; so every library orders a
 * set alike. An id that is no value of the type finds no node, and goes last, in the order it was given.
 */
internal object AmongOrder {

    fun of(graphClass: Class<*>, ids: List<String>, objectMapper: ObjectMapper): List<String> {
        val fragment = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass).rootFragment.fragmentType else graphClass
        val model = FragmentModel.from(fragment)
        val type = model.fields.firstOrNull { it.name == model.nodeIdField }?.type?.kotlin?.javaObjectType ?: return ids
        val keyed = ids.map { it to key(it, type, objectMapper) }
        val ordered = keyed.filter { it.second != null }.sortedWith { a, b -> compare(a.second!!, b.second!!) }
        return ordered.map { it.first } + keyed.filter { it.second == null }.map { it.first }
    }

    /** [id] read as [type], as a lookup by it reads it; a text, a `UUID` or an enum as its text. Null where it is no value of it. */
    private fun key(id: String, type: Class<*>, objectMapper: ObjectMapper): Any? {
        if (type == String::class.java) return id
        val typed = try { objectMapper.convertValue(id, type) } catch (e: IllegalArgumentException) { return null } ?: return null
        return when (typed) {
            is UUID -> typed.toString()
            is Enum<*> -> typed.name
            else -> typed
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(a: Any, b: Any): Int = when {
        a is String && b is String -> byCodePoints(a, b)
        a is Comparable<*> && a.javaClass == b.javaClass -> (a as Comparable<Any>).compareTo(b)
        else -> byCodePoints(a.toString(), b.toString())
    }

    /** [a] and [b] compared by their code points, as the module and the other libraries order a text. */
    fun byCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
