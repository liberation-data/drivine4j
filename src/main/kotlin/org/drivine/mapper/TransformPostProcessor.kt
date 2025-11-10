package org.drivine.mapper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.neo4j.driver.internal.value.MapValue

class TransformPostProcessor<S, T>(private val type: Class<T>) : ResultPostProcessor<S, T> {

    private val objectMapper = jacksonObjectMapper()

    override fun apply(results: List<S>): List<T> {
        return results.map { result ->
            val convertValue = objectMapper.convertValue((result as MapValue).asMap(), type)
            convertValue
        }
    }
}
