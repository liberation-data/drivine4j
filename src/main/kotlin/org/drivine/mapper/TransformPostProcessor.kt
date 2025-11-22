package org.drivine.mapper

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.Value

class TransformPostProcessor<S, T>(private val type: Class<T>) : ResultPostProcessor<S, T> {

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    override fun apply(results: List<S>): List<T> {
        return results.map { result ->
            when (result) {
                is MapValue -> {
                    // Result is a map (e.g., properties(n) or a node)
                    objectMapper.convertValue(result.asMap(), type)
                }
                is Value -> {
                    // Result is a Neo4j Value but not a MapValue (e.g., scalar, list, etc.)
                    // Try to convert the underlying value
                    objectMapper.convertValue(result.asObject(), type)
                }
                else -> {
                    // Result is already a plain object
                    objectMapper.convertValue(result, type)
                }
            }
        }
    }
}
