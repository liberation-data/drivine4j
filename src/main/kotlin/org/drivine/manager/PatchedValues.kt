package org.drivine.manager

import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.introspect.AnnotatedMember
import org.drivine.annotation.GraphView
import org.drivine.annotation.JsonPacked
import org.drivine.mapper.packedJson
import org.drivine.mapper.toMap
import org.drivine.model.FragmentField
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.dsl.FieldWrite

/**
 * The fields a patch of one [graphClass] node names, as what its statement writes: each checked to
 * be a field of the node that a patch can write, and its value as a save stores that field.
 *
 * A save reads a value off an object, through the object mapper. A patch has the value and no
 * object, so it gives the mapper the value alone, and does itself what the mapper does for the
 * field and not for the type: a `@JsonPacked` field is packed. A field with a serializer of its own
 * is refused, since what that serializer writes cannot be told from here.
 */
internal class PatchedValues(private val graphClass: Class<*>, private val objectMapper: ObjectMapper) {
    private val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null
    private val model = FragmentModel.from(viewModel?.rootFragment?.fragmentType ?: graphClass)

    /** What a property reference calls the node: `n` for a fragment, the root field's name for a view. */
    private val alias = viewModel?.rootFragment?.fieldName ?: FRAGMENT_ALIAS

    private val unpatchable: Map<String, String> = buildMap {
        model.nodeIdField?.let { put(it, "the id, which a node is found by") }
        model.stampField?.let { put(it, "the stamp, which only a write gives a node") }
    }

    private val patchable = model.fields.filter { it.name !in unpatchable && !it.stamp }

    /**
     * What to write, by stored property name, in the order the class declares the fields: so one
     * patch is one statement text however its fields were named. A null removes the property.
     *
     * @throws IllegalArgumentException when no field is named, one is named twice, or one is not a
     *   field of the node that a patch can write
     */
    fun of(writes: List<FieldWrite>): Map<String, Any?> {
        require(writes.isNotEmpty()) { "A patch names the fields it writes: fields { property setTo value }. This one names none." }
        val named = writes.map { fieldOf(it) to it }
        named.groupBy { it.first.name }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw IllegalArgumentException("Field '${same.first().first.name}' of ${model.clazz.simpleName} is named ${same.size} times. A patch writes each field once.")
        }
        val values = named.associate { (field, write) -> field.name to write.value?.let { stored(field, it) } }
        return model.fields.filter { it.name in values }.associate { it.propertyName to values[it.name] }
    }

    /** The field [write] names, which must be one of the node's own that a patch can write. */
    private fun fieldOf(write: FieldWrite): FragmentField {
        require(write.alias == alias) {
            val related = viewModel?.relationships?.any { write.alias == it.fieldName || write.alias.startsWith("${it.fieldName}_") } == true
            "A patch of ${graphClass.simpleName} writes fields of its ${if (viewModel != null) "root, '$alias'" else "node"}, and '${write.alias}.${write.propertyName}' is " +
                (if (related) "a field of a related node" else "not one") + ". Patch that node by its own id."
        }
        // A reference to the stamp names the stamp's own property, whatever the field is stored as in the model.
        val field = model.fields.firstOrNull { if (write.propertyName == Stamps.PROPERTY) it.name == model.stampField else it.propertyName == write.propertyName }
        unpatchable[field?.name]?.let { why ->
            throw IllegalArgumentException("Field '${field?.name}' of ${model.clazz.simpleName} is $why, and a patch does not write it. ${canWrite()}")
        }
        require(field != null && field in patchable) {
            "${model.clazz.simpleName} has no field stored as '${write.propertyName}' for a patch to write: a patch writes neither a key of a @PropertyBag nor a @NodeLabels field. ${canWrite()}"
        }
        return field
    }

    private fun canWrite(): String = "The fields it can write are: ${patchable.map { it.name }.sorted().joinToString()}."

    /** [value] as a save stores it in [field]. */
    private fun stored(field: FragmentField, value: Any): Any? {
        val members = membersOf(field)
        require(members.none { it.getAnnotation(JsonSerialize::class.java)?.using?.let { using -> using != JsonSerializer.None::class } == true }) {
            "Field '${field.name}' of ${model.clazz.simpleName} has a serializer of its own, and a patch cannot tell what that stores. Load the object and save it."
        }
        if (members.any { it.hasAnnotation(JsonPacked::class.java) }) return packedJson(value)
        return objectMapper.toMap(mapOf(VALUE to value))[VALUE]
    }

    /** The members the object mapper reads [field] and its annotations from. */
    private fun membersOf(field: FragmentField): List<AnnotatedMember> =
        objectMapper.serializationConfig.introspect(objectMapper.constructType(model.clazz)).findProperties()
            .firstOrNull { it.name == field.name }
            ?.let { listOfNotNull(it.field, it.getter, it.constructorParameter) }
            .orEmpty()

    private companion object {
        /** What a predicate calls the node of a fragment: `FragmentQueryBuilder.nodeAlias`. */
        const val FRAGMENT_ALIAS = "n"

        const val VALUE = "v"
    }
}
