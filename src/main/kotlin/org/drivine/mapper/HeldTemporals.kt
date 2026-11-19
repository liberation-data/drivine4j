package org.drivine.mapper

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.ValueLayout
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The temporal tag each text of a node's property was read from, on a SQL store: kept beside the text, not on it, for as
 * long as the text is.
 *
 * A SQL store holds a temporal value as its tag, `{"$t": kind, "v": text}` (see [ValueLayout]), and a read gives the tag
 * as its text `v`, as a graph engine's driver gives no tag either. The text alone can't say what the node held: a lookup
 * by it misses the node, which holds the tag (liberation-data/drivine4j#860, #861). So each text a node's property is read
 * as is kept here, by identity, with the tag it was read from. A load of a fragment finds there what the node held its id
 * as ([IdKinds]), and a save binds an id it was loaded as, while unchanged, as that tag again
 * ([org.drivine.backend.sql.NodeIds.held]).
 *
 * The text is told by identity, not by equality: the same text read from another node, given by the user, or the value of
 * an id changed since its load is another object, and is bound as given. A copy of a loaded object, as a Kotlin data
 * class's `copy` makes it, holds the same text, and is the same node.
 */
internal object HeldTemporals {

    /** What a node held a text as: a temporal value of [kind] whose canonical text is [text]. */
    data class Held(val kind: Temporal, val text: String) {
        /** The kind's word, as the tag's `$t` names it. */
        val word: String get() = ValueLayout.word(kind)

        /** The value a save writes as the tag the node held: the module writes it so ([org.drivine.cypher.json.CypherJson.temporalTag]). */
        fun value(): TemporalValue = TemporalValue(kind, text)
    }

    private val held = ConcurrentHashMap<Key, Held>()
    private val gone = ReferenceQueue<String>()

    /** A text, held weakly and found by identity: the same text read from another node is another key. */
    private class Key(text: String, queue: ReferenceQueue<String>?) : WeakReference<String>(text, queue) {
        private val hash = System.identityHashCode(text)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other === this || (other is Key && get().let { it != null && it === other.get() })
    }

    /** What the node held [value] as, where it is a text a node's property was read as from a tag; else null. */
    fun of(value: Any?): Held? {
        if (value !is String || held.isEmpty()) return null
        expunge()
        return held[Key(value, null)]
    }

    /**
     * [value], read from a SQL store's JSON, as [ValueLayout.readValue] reads it: each tag its text, and each map's keys
     * unescaped, at any depth. Each text of a tag that is a node's property, in a map that a load gives for a node (one
     * with its `labels`, see `SqliteSql.document`), is kept with its tag.
     */
    fun read(value: Any?): Any? = when (value) {
        is Map<*, *> -> tagOf(value)?.text ?: run {
            val node = value["labels"] is List<*>
            value.entries.associate { (key, item) ->
                val tag = if (node && item is Map<*, *>) tagOf(item) else null
                val read = tag?.text ?: read(item)
                if (tag != null) keep(tag)
                (key as? String)?.let(ValueLayout::unescape) to read
            }
        }
        is List<*> -> value.map(::read)
        else -> value
    }

    /** The tag [value] is, as [ValueLayout.readValue] tells one: a map whose `$t` names a temporal kind and whose `v` is a text. */
    private fun tagOf(value: Map<*, *>): Held? {
        val kind = (value[ValueLayout.KIND] as? String)?.let(ValueLayout::kindOf) ?: return null
        val text = value[ValueLayout.TEXT] as? String ?: return null
        return Held(kind, text)
    }

    /** Keeps [tag] by its text, weakly: what is held is given a copy of the text, so it doesn't keep the key alive. */
    private fun keep(tag: Held) {
        expunge()
        held[Key(tag.text, gone)] = tag.copy(text = StringBuilder(tag.text).toString())
    }

    private fun expunge() {
        while (true) held.remove(gone.poll() ?: return)
    }
}
