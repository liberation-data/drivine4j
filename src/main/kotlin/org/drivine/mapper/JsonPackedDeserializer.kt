package org.drivine.mapper

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Deserializer for @JsonPacked fields. Handles both:
 * - JSON string input: '["a","b"]' → List("a","b")
 * - Native array input: ["a","b"] → List("a","b")  (passthrough for Neo4j/FalkorDB)
 * - Null → null
 */
class JsonPackedDeserializer : StdDeserializer<Any>(Any::class.java) {

    private val json = jacksonObjectMapper()

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? {
        return when (p.currentToken) {
            JsonToken.VALUE_STRING -> {
                val text = p.text
                if (text.startsWith("[")) {
                    json.readValue(text, List::class.java)
                } else {
                    listOf(text)
                }
            }
            JsonToken.START_ARRAY -> {
                val items = mutableListOf<Any?>()
                p.nextToken()
                while (p.currentToken != JsonToken.END_ARRAY) {
                    items.add(p.readValueAs(Any::class.java))
                    p.nextToken()
                }
                items
            }
            JsonToken.VALUE_NULL -> null
            else -> ctxt.handleUnexpectedToken(List::class.java, p)
        }
    }
}