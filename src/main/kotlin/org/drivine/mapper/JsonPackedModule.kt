package org.drivine.mapper

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.annotation.JsonPacked

/**
 * Jackson module that handles @JsonPacked annotation on collection properties.
 *
 * - Serialize: collection → JSON string (e.g., ["a","b"] → '["a","b"]')
 * - Deserialize: JSON string → collection (e.g., '["a","b"]' → ["a","b"])
 *
 * Registered on Neo4jObjectMapper so it works across all engines.
 * Only activates on fields annotated with @JsonPacked.
 */
class JsonPackedModule : SimpleModule("JsonPacked") {

    private val json = jacksonObjectMapper()

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)

        // Serialize: @JsonPacked collections → JSON strings
        context.addBeanSerializerModifier(object : BeanSerializerModifier() {
            override fun changeProperties(
                config: SerializationConfig,
                beanDesc: BeanDescription,
                beanProperties: MutableList<BeanPropertyWriter>
            ): MutableList<BeanPropertyWriter> {
                return beanProperties.map { writer ->
                    if (hasJsonPacked(writer)) {
                        JsonPackedPropertyWriter(writer, json)
                    } else {
                        writer
                    }
                }.toMutableList()
            }
        })
    }

    private fun hasJsonPacked(writer: BeanPropertyWriter): Boolean {
        return writer.getAnnotation(JsonPacked::class.java) != null ||
            writer.member?.getAnnotation(JsonPacked::class.java) != null
    }
}

/**
 * Property writer that serializes @JsonPacked collections as JSON strings.
 */
private class JsonPackedPropertyWriter(
    delegate: BeanPropertyWriter,
    private val json: ObjectMapper
) : BeanPropertyWriter(delegate) {

    override fun serializeAsField(bean: Any, gen: JsonGenerator, prov: SerializerProvider) {
        val value = get(bean)
        if (value == null) {
            if (_nullSerializer != null) {
                gen.writeFieldName(_name)
                _nullSerializer.serialize(null, gen, prov)
            }
        } else {
            gen.writeFieldName(_name)
            gen.writeString(json.writeValueAsString(value))
        }
    }
}