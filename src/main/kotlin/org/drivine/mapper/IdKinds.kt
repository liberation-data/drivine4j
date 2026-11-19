package org.drivine.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.DrivineException
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.ValueLayout
import org.drivine.model.FragmentModel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalAmount
import java.util.Date
import java.util.UUID

/**
 * Refuses, by name, a load of a node whose `@NodeId` a SQL store holds as a temporal tag that the class's declared id
 * can't save back (liberation-data/drivine4j#860, #861).
 *
 * A save finds a node by its id as the node holds it, kind and text, and writes the id so. A node whose id is a tag, read
 * into an id declared:
 * - a non-temporal scalar (a `String`, a number, a `Boolean`, a `UUID`, an enum), is given the tag's text, which a lookup
 *   misses, as it compares the kind too;
 * - a `java.time` type (or a `Date`) of another kind or precision, is given a value whose own tag is another: Jackson reads
 *   a `LocalDate` out of a datetime's text, keeping its date, and an `Instant` out of one at an offset, moving it to UTC.
 *
 * Either way a save of the loaded object made a second node, with no error. So each read that maps a node into a model
 * comes here first ([TransformPostProcessor]): a fragment, a view's root, related node and nested view, a relationship
 * fragment's node and a path's node, by every load, search, grouped key and update. A pair Jackson can't read is refused
 * here too, by name. An id declared `Any` (or another type a `String` is, such as `CharSequence`) is not refused: it is
 * loaded as the text, and a save binds it as the tag it was read from while it is unchanged ([HeldTemporals]).
 *
 * The messages are shared, in shape, with Drivine for TypeScript and drivine-python.
 */
internal object IdKinds {

    /** The `java.time` type that holds each kind, as a node holds it, to suggest. */
    private val HOLDERS: Map<Temporal, Class<*>> = mapOf(
        Temporal.DATE to LocalDate::class.java,
        Temporal.LOCAL_DATE_TIME to LocalDateTime::class.java,
        Temporal.DATE_TIME to OffsetDateTime::class.java,
        Temporal.TIME to OffsetTime::class.java,
        Temporal.LOCAL_TIME to LocalTime::class.java,
        Temporal.DURATION to Duration::class.java,
    )

    /** Refuses [data], a node's map a load gives, mapped into [type], where its `@NodeId` is held as a tag the id can't save back. */
    fun refuse(type: Class<*>, data: Map<*, *>, objectMapper: ObjectMapper) {
        if (!type.isAnnotationPresent(org.drivine.annotation.NodeFragment::class.java)) return
        val model = try {
            FragmentModel.from(type)
        } catch (e: Exception) {
            return
        }
        val field = model.nodeIdField?.let { name -> model.fields.firstOrNull { it.name == name } } ?: return
        val stored = if (data.containsKey(field.propertyName)) data[field.propertyName] else data[field.name]
        val held = HeldTemporals.of(stored) ?: return
        val declared = field.type.kotlin.javaObjectType
        val message = when {
            scalar(declared) -> "${type.simpleName}'s @NodeId ${field.name} is declared ${a(name(field.type))}, but the node whose id is " +
                "'${held.text}' holds it as ${a(held.word)}: declare ${field.name} as ${a(held.word)} to load it."
            temporal(declared) && !savesBack(declared, held, objectMapper) -> {
                val suggested = HOLDERS[held.kind]?.takeIf { savesBack(it, held, objectMapper) }?.let { a(it.simpleName) } ?: "Any"
                "${type.simpleName}'s @NodeId ${field.name} is declared ${a(name(field.type))}, but the node whose id is '${held.text}' " +
                    "holds it as ${a(described(held, declared))}, which ${a(name(field.type))} can't save back: " +
                    "declare ${field.name} as $suggested to load it."
            }
            else -> return
        }
        throw DrivineException(message)
    }

    /** Whether [type] is a concrete non-temporal scalar, which a tag's text can't find the node by. */
    private fun scalar(type: Class<*>): Boolean =
        type == String::class.java || type == Boolean::class.javaObjectType || type == Char::class.javaObjectType ||
            type == UUID::class.java || Number::class.java.isAssignableFrom(type) || type.isEnum

    /** Whether [type] is a temporal type, whose value a save writes as a tag of its own kind. */
    private fun temporal(type: Class<*>): Boolean =
        Date::class.java.isAssignableFrom(type) || TemporalAccessor::class.java.isAssignableFrom(type) ||
            TemporalAmount::class.java.isAssignableFrom(type)

    /**
     * Whether a value of [type], read out of [held]'s text as a load reads it, is written by a save as [held] again: of its
     * kind, and of its text. One Jackson can't read doesn't.
     */
    private fun savesBack(type: Class<*>, held: HeldTemporals.Held, objectMapper: ObjectMapper): Boolean {
        val value = try {
            objectMapper.convertValue(held.text, type)
        } catch (e: IllegalArgumentException) {
            return false
        } ?: return false
        val tag = objectMapper.toPropertyValue(value)?.let(CypherJson::temporalTag) as? ObjectNode ?: return false
        return tag[ValueLayout.KIND]?.textValue() == held.word && tag[ValueLayout.TEXT]?.textValue() == held.text
    }

    /**
     * [held]'s kind, as the message names it: a datetime at an offset an instant can't keep, or below the millisecond a
     * `Date` holds, is named so.
     */
    private fun described(held: HeldTemporals.Held, declared: Class<*>): String {
        if (held.kind != Temporal.DATE_TIME || !instant(declared)) return held.word
        val at = try {
            OffsetDateTime.parse(held.text)
        } catch (e: java.time.format.DateTimeParseException) {
            return held.word
        }
        return when {
            at.offset != ZoneOffset.UTC -> "${held.word} at the offset ${at.offset}"
            Date::class.java.isAssignableFrom(declared) && at.nano % 1_000_000 != 0 -> "${held.word} below the millisecond"
            else -> held.word
        }
    }

    /** Whether [type] is a datetime of its own: one a save writes as a datetime. */
    private fun instant(type: Class<*>): Boolean =
        Date::class.java.isAssignableFrom(type) || type == java.time.Instant::class.java || type == OffsetDateTime::class.java ||
            type == java.time.ZonedDateTime::class.java

    /** [type]'s name as Kotlin writes it: `Int`, not `Integer`; `Long`, not `long`. */
    private fun name(type: Class<*>): String = type.kotlin.simpleName ?: type.simpleName

    /** [word] after its article: `an` before a vowel's letter, `a` before any other (`a UUID`). */
    private fun a(word: String): String = if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o')) "an $word" else "a $word"
}
