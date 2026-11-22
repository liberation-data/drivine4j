package org.drivine.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.toPropertyValue
import org.drivine.model.FragmentModel

/**
 * An id given to find a node by, as `load(id: String)`, a delete by id, a [org.drivine.manager.NodeRef] and a patch give it,
 * made the value a save writes for the `@NodeId` field. So it finds the node on every store. A SQL store goes on to write
 * it as the JSON the node holds, and gives a text it read from a temporal tag back as that tag first
 * (`org.drivine.backend.sql.NodeIds`). A graph engine binds it as it is, through the bind a
 * save's values go through, so `n.id = $id` compares it with what the save wrote. Text given for a `Long` id is then the
 * number, and text given for a `LocalDate` id is the date (liberation-data/drivine4j#806).
 */
internal object GivenIds {

    /**
     * [given] as a node of [model] holds its id: a value of the `@NodeId` field's type, as a save writes the field. A text
     * given for an id of another type is read as that type, as Jackson reads the field. A value of the type, or any value
     * for a `String` id, is written as it is. Null where [given] is no value of the type, `"abc"` for a `Long` id, so no
     * node is found by it, as no node's id is null. [given] itself where the class has no `@NodeId` field.
     */
    fun valueOf(objectMapper: ObjectMapper, model: FragmentModel, given: Any?): Any? {
        if (given == null) return null
        val type = model.fields.firstOrNull { it.name == model.nodeIdField }?.type?.kotlin?.javaObjectType ?: return given
        val typed = if (type == String::class.java || type.isInstance(given)) given else try {
            objectMapper.convertValue(given, type)
        } catch (e: IllegalArgumentException) {
            return null
        } ?: return null
        return objectMapper.toPropertyValue(typed)
    }
}
