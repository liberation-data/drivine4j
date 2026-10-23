package org.drivine.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.JsonPacked
import org.drivine.model.FragmentModel
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * What each `@JsonPacked` property of a stamped fragment held when a load made the fragment, as the
 * load read it, by field name: kept beside the fragment, not on it, for as long as the fragment is.
 *
 * A checked save on Neo4j compares each field of the root as it was loaded ([org.drivine.query.FragmentMergeBuilder]).
 * A packed field's value packed again is not always the text the node held: another library, raw
 * Cypher or a migration may have packed the same JSON with other spacing, escaping or number
 * formatting, or stored text that is not JSON, or a list. Compared so, the node would differ on
 * every attempt though nothing changed. So the save compares the text the load read.
 *
 * The text is kept with the stamp the fragment was loaded with, and given only to a fragment that
 * still carries it: one stamped in place by a save since holds what the save wrote, not what was read.
 */
internal object PackedText {

    private val held = HashMap<Key, Held>()
    private val gone = ReferenceQueue<Any>()

    private class Held(val stamp: String, val text: Map<String, Any?>)

    /** A fragment, held weakly and found by identity: two equal objects may have been loaded from different text. */
    private class Key(fragment: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(fragment, queue) {
        private val hash = System.identityHashCode(fragment)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other === this || (other is Key && get().let { it != null && it === other.get() })
    }

    /** The packed fields of each class, by name: read once. */
    private val packed = ConcurrentHashMap<Class<*>, Set<String>>()

    /**
     * Keeps what each packed property of [fragment] held in [data], the load's map of the fragment by
     * field name, if the fragment carries a stamp: without one no save compares its fields.
     */
    fun record(fragment: Any, data: Map<*, *>, objectMapper: ObjectMapper) {
        val fields = packedFields(fragment.javaClass, objectMapper).ifEmpty { return }
        val stamp = FragmentModel.from(fragment.javaClass).stampField?.let { data[it] as? String } ?: return
        val text = fields.associateWith { data[it] }
        synchronized(held) {
            expunge()
            held[Key(fragment, gone)] = Held(stamp, text)
        }
    }

    /**
     * What each packed property of [fragment] held when a load made it, null for one it held nothing
     * for, if the fragment still carries [stamp], the stamp it was loaded with. Null for a fragment no
     * load made, such as a copy.
     */
    fun of(fragment: Any, stamp: String?): Map<String, Any?>? = synchronized(held) {
        expunge()
        held[Key(fragment, null)]?.takeIf { it.stamp == stamp }?.text
    }

    private fun expunge() {
        while (true) held.remove(gone.poll() ?: return)
    }

    private fun packedFields(type: Class<*>, objectMapper: ObjectMapper): Set<String> = packed.getOrPut(type) {
        if (FragmentModel.from(type).stampField == null) return@getOrPut emptySet()
        objectMapper.serializationConfig.introspect(objectMapper.constructType(type)).findProperties()
            .filter { property ->
                listOfNotNull(property.field, property.getter, property.constructorParameter).any { it.hasAnnotation(JsonPacked::class.java) }
            }
            .map { it.name }.toSet()
    }
}
