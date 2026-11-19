package org.drivine.backend.sql

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.connection.CypherCompile
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.store.ValueLayout
import org.drivine.mapper.HeldTemporals
import org.drivine.mapper.toPropertyValue
import org.drivine.model.FragmentModel
import org.drivine.query.StampWrite

/**
 * A node's id as a SQL store holds it, in the store layout 2 (liberation-data/drivine-cypher-spec#161), and an id given to
 * find a node by, made into that.
 *
 * A node holds its id as JSON: a number as a number, a text as a text, a temporal value as its tag. A range index or a
 * uniqueness constraint is over that JSON (Postgres) or over its class and key (Oracle and SQLite), so a statement that
 * finds a node by its id compares the property with the id's JSON, [json], and the index answers it. The JSON is the
 * [store]'s, as a save's plan writes the id (`WritePlan.bindings`): on Oracle a float is extended JSON's double, which a
 * statement reads `JSON(… EXTENDED)`, as the node holds a binary double.
 *
 * An id given as text, as `load(id: String)` and a delete by id give it, is first made a value of the `@NodeId` field's own
 * type ([of]), as Jackson reads that field, then written as a save writes the field. Text that is no value of the type,
 * `"abc"` for a `Long` id, finds no node, as on Neo4j, where it is never equal to a number.
 */
internal class NodeIds(private val objectMapper: ObjectMapper, private val store: Store) {

    /**
     * [given] as a node of [model] holds its id: a value of the `@NodeId` field's type, as a save writes the field. A text
     * given for an id of another type is read as that type; a value of the type, or any value for a `String` id, is
     * written as it is, except a text a load read from a temporal tag, given for an id declared `Any`: that is the tag
     * ([held]). Null where [given] is no value of the type, or where the class has no `@NodeId` field: no node is found by
     * it.
     */
    fun of(model: FragmentModel, given: Any?): Any? {
        if (given == null) return null
        heldTag(model, given)?.let { return it }
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

    /**
     * [id], a value as a save writes it, as the JSON a node holds it as on the [store]: written by the module as a save's plan
     * writes it (`StoreValues.text`), a temporal value as its tag. An id the store holds as no number, NaN on Postgres, is
     * refused as a save of it is.
     */
    fun json(id: Any): String = CypherCompile.storeText(id, store)

    /** The id of the save's [root] as the JSON its node holds it as, as the save bound it ([held]): what finds it to read its stamp. */
    fun rootJson(root: StampWrite): String = json(held(FragmentModel.from(root.fragmentClass), root.id))

    companion object {
        /**
         * [id], a `@NodeId` value of a node of [model], as a save binds it: the tag the node held it as, where [id] is the
         * very text a load read that tag as and the id is declared a type that takes the text as it is, such as `Any`
         * ([HeldTemporals]); else [id]. So a node whose id is a temporal, loaded into such an id and saved while the id is
         * unchanged, is found, and written, by its tag, as it is held: the text alone finds no node (drivine4j#861). An id
         * declared a scalar or a temporal type never holds such a text: its load is refused, or read as the type
         * ([org.drivine.mapper.IdKinds]).
         */
        fun held(model: FragmentModel, id: Any): Any = heldTag(model, id) ?: id

        private fun heldTag(model: FragmentModel, id: Any): Any? {
            val held = HeldTemporals.of(id) ?: return null
            val type = model.fields.firstOrNull { it.name == model.nodeIdField }?.type ?: return null
            return held.value().takeIf { type.isAssignableFrom(String::class.java) && type != String::class.java }
        }

        /**
         * The text a temporal [id] is held by in its tag, its canonical text `v` ([ValueLayout.TEXT]), as the module writes
         * it ([CypherJson.temporalTag]); null for an id that is no temporal value. A write plan keys such a node by it, and
         * reads a stored id back as it, so the two agree. One the module refuses, as a time at an offset with seconds, is
         * refused as a save of it is ([CypherCompile.refusing]), not with the module's own exception (drivine4j#840).
         */
        fun temporalText(id: Any): String? = CypherCompile.refusing { CypherJson.temporalTag(id) }?.get(ValueLayout.TEXT)?.textValue()
    }
}
