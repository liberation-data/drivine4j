package org.drivine.backend.sql

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ValueLayout
import org.drivine.mapper.toPropertyValue
import org.drivine.model.FragmentModel
import org.drivine.query.JdbcParameterCoercer

/**
 * A node's id as a SQL store holds it, in the store layout 2 (liberation-data/drivine-cypher-spec#161), and an id given to
 * find a node by, made into that.
 *
 * A node holds its id as JSON: a number as a number, a text as a text, a temporal value as its tag. A range index or a
 * uniqueness constraint is over that JSON (Postgres) or over its class and key (Oracle and SQLite), so a statement that
 * finds a node by its id compares the property with the id's JSON, [json], and the index answers it.
 *
 * An id given as text, as `load(id: String)` and a delete by id give it, is first made a value of the `@NodeId` field's own
 * type ([of]), as Jackson reads that field, then written as a save writes the field. Text that is no value of the type,
 * `"abc"` for a `Long` id, finds no node, as on Neo4j, where it is never equal to a number.
 */
internal class NodeIds(private val objectMapper: ObjectMapper) {

    /**
     * [given] as a node of [model] holds its id: a value of the `@NodeId` field's type, as a save writes the field. A text
     * given for an id of another type is read as that type; a value of the type, or any value for a `String` id, is
     * written as it is. Null where [given] is no value of the type, or where the class has no `@NodeId` field: no node is
     * found by it.
     */
    fun of(model: FragmentModel, given: Any?): Any? {
        if (given == null) return null
        val type = model.fields.firstOrNull { it.name == model.nodeIdField }?.type?.kotlin?.javaObjectType ?: return given
        val typed = if (type == String::class.java || type.isInstance(given)) given else try {
            objectMapper.convertValue(given, type)
        } catch (e: IllegalArgumentException) {
            return null
        } ?: return null
        return objectMapper.toPropertyValue(typed)
    }

    /** [given] as the JSON text a node of [model] holds its id as ([of], then [json]); null where it finds no node. */
    fun json(model: FragmentModel, given: Any?): String? = of(model, given)?.let(::json)

    companion object {
        /**
         * [id], a value as a save writes it, as the JSON a node holds it as: written by the module as a save's plan writes it
         * ([JdbcParameterCoercer.json]), a temporal value as its tag.
         */
        fun json(id: Any): String = JdbcParameterCoercer.json(id)

        /**
         * The text a temporal [id] is held by in its tag, its canonical text `v` ([ValueLayout.TEXT]), as the module writes
         * it ([CypherJson.temporalTag]); its text where the module keeps it as one; null for an id that is no temporal value.
         * A write plan keys such a node by it, and reads a stored id back as it, so the two agree.
         */
        fun temporalText(id: Any): String? = when (val tag = CypherJson.temporalTag(id)) {
            null -> null
            is ObjectNode -> tag[ValueLayout.TEXT]?.textValue()
            else -> tag.textValue()
        }
    }
}
