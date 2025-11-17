package org.drivine.mapper

import org.neo4j.driver.Record
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.NodeValue
import org.neo4j.driver.internal.value.PointValue
import org.neo4j.driver.internal.value.RelationshipValue
import java.util.*

open class Neo4jResultMapper : GraphResultMapper() {

    override fun keys(record: Any): List<String> {
        val rec = record as Record
        return rec.keys()
    }

    override fun itemAtIndex(record: Any, index: Int): Any {
        val rec = record as Record
        return rec.get(index)
    }

    override fun toNative(value: Any): Any {
        return when (value) {
            is NodeValue -> toNative(value.asMap())
            is RelationshipValue -> toNative(value.asMap())
            is PointValue -> value
            is org.neo4j.driver.types.IsoDuration -> value // If handling durations explicitly
            is Date -> value
            is List<*> -> value.map { it?.let { it1 -> toNative(it1) } }
            is Map<*, *> -> value.mapValues { it.value?.let { it1 -> toNative(it1) } }
            is Record -> toNative(recordToNative(value))
            is Value -> when {
                value.hasType(org.neo4j.driver.types.TypeSystem.getDefault().INTEGER()) -> value.asLong()
                else -> value
            }
            else -> value
        }
    }

    private fun recordToNative(rec: Record): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        rec.keys().forEachIndexed { index, key ->
            out[key] = rec.get(index).asObject() // Converts Neo4j Value to a native type
        }
        return out
    }

}
