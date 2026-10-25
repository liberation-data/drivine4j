package org.drivine.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.JsonPacked
import org.drivine.model.FragmentModel
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * What each `@JsonPacked` property and each `Set` property of a stamped fragment held when a load
 * made the fragment, as the load read it, by field name: kept beside the fragment, not on it, for as
 * long as the fragment is.
 *
 * A checked save on Neo4j compares each field of the root as it was loaded ([org.drivine.query.FragmentMergeBuilder]).
 * Some fields written again are not what the node held:
 * - A packed field's value packed again is not always the text the node held: another library, raw
 *   Cypher or a migration may have packed the same JSON with other spacing, escaping or number
 *   formatting, or stored text that is not JSON, or a list.
 * - A set is stored as a list, and Neo4j compares lists item by item. The list may hold an item
 *   twice, which the set does not, and the set may iterate in another order than the list, as a
 *   `HashSet` does.
 *
 * Compared so, the node would differ on every attempt though nothing changed. So the save compares
 * what the load read.
 *
 * What was read is kept with the stamp the fragment was loaded with, and given only to a fragment
 * that still carries it: one stamped in place by a save since holds what the save wrote, not what was read.
 */
internal object AsStored {

    private val held = HashMap<Key, Held>()
    private val gone = ReferenceQueue<Any>()

    private class Held(val stamp: String, val stored: Map<String, Any?>)

    /** A fragment, held weakly and found by identity: two equal objects may have been loaded from different values. */
    private class Key(fragment: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(fragment, queue) {
        private val hash = System.identityHashCode(fragment)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other === this || (other is Key && get().let { it != null && it === other.get() })
    }

    /** The packed and set fields of each class, by name: read once. */
    private val kept = ConcurrentHashMap<Class<*>, Set<String>>()

    /**
     * Keeps what each packed or set property of [fragment] held in [data], the load's map of the
     * fragment by field name, if the fragment carries a stamp: without one no save compares its fields.
     */
    fun record(fragment: Any, data: Map<*, *>, objectMapper: ObjectMapper) {
        val fields = keptFields(fragment.javaClass, objectMapper).ifEmpty { return }
        val stamp = FragmentModel.from(fragment.javaClass).stampField?.let { data[it] as? String } ?: return
        keep(fragment, stamp, fields.associateWith { data[it] })
    }

    /**
     * Keeps, for [fragment], what an update returned carrying [stamp], what [asLoaded], the fields the
     * update compared, says each packed or set property held that the update did not write ([written]):
     * the node holds that still, so a save from the object the update returned compares it so too.
     */
    fun carry(fragment: Any, stamp: String, asLoaded: Map<String, Any?>, written: Set<String>, objectMapper: ObjectMapper) {
        val fields = (keptFields(fragment.javaClass, objectMapper) - written).ifEmpty { return }
        keep(fragment, stamp, fields.associateWith { asLoaded[it] })
    }

    private fun keep(fragment: Any, stamp: String, stored: Map<String, Any?>) = synchronized(held) {
        expunge()
        held[Key(fragment, gone)] = Held(stamp, stored)
    }

    /**
     * What each packed or set property of [fragment] held when a load made it, null for one it held
     * nothing for, if the fragment still carries [stamp], the stamp it was loaded with. Null for a
     * fragment no load made, such as a copy.
     */
    fun of(fragment: Any, stamp: String?): Map<String, Any?>? = synchronized(held) {
        expunge()
        held[Key(fragment, null)]?.takeIf { it.stamp == stamp }?.stored
    }

    private fun expunge() {
        while (true) held.remove(gone.poll() ?: return)
    }

    /** The fields of [type] kept: those `@JsonPacked`, and those declared a `Set`. */
    private fun keptFields(type: Class<*>, objectMapper: ObjectMapper): Set<String> = kept.getOrPut(type) {
        if (FragmentModel.from(type).stampField == null) return@getOrPut emptySet()
        objectMapper.serializationConfig.introspect(objectMapper.constructType(type)).findProperties()
            .filter { property ->
                listOfNotNull(property.field, property.getter, property.constructorParameter).any { it.hasAnnotation(JsonPacked::class.java) } ||
                    property.primaryType.isTypeOrSubTypeOf(Set::class.java)
            }
            .map { it.name }.toSet()
    }
}
