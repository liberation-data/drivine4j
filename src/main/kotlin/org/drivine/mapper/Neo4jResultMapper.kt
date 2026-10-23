package org.drivine.mapper

import org.neo4j.driver.Record
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.NodeValue
import org.neo4j.driver.internal.value.NullValue
import org.neo4j.driver.internal.value.PathValue
import org.neo4j.driver.internal.value.PointValue
import org.neo4j.driver.internal.value.RelationshipValue
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Path
import org.neo4j.driver.types.Relationship
import java.util.*

open class Neo4jResultMapper(
    subtypeRegistry: SubtypeRegistry? = null,
) : GraphResultMapper(subtypeRegistry) {

    override fun keys(record: Any): List<String> {
        val rec = record as Record
        return rec.keys()
    }

    override fun itemAtIndex(record: Any, index: Int): Any {
        val rec = record as Record
        return rec.get(index)
    }

    override fun toNative(value: Any?): Any? {
        return when (value) {
            is NullValue -> null
            is NodeValue -> toNative(value.asMap())
            is RelationshipValue -> toNative(value.asMap())
            // A path that is a whole column stays the driver's Path, as it always has.
            is PathValue -> value.asPath()
            is PointValue -> value
            is org.neo4j.driver.types.IsoDuration -> value
            is Date -> value
            // What asObject() gives for a list or a map holds the driver's own Node, Relationship and
            // Path, which become maps here as they do at the top level and as on FalkorDB.
            is Node -> toNative(value.asMap())
            is Relationship -> toNative(value.asMap())
            is Path -> pathToMap(value)
            is List<*> -> value.map { it?.let { it1 -> toNative(it1) } }
            is Map<*, *> -> value.mapValues { it.value?.let { it1 -> toNative(it1) } }
            is Record -> recordToNative(value)
            is Value -> when {
                value.hasType(org.neo4j.driver.types.TypeSystem.getDefault().INTEGER()) -> value.asLong()
                else -> toNative(value.asObject())
            }
            else -> value
        }
    }

    /** A path inside a list or a map, in FalkorDB's shape: its nodes and its relationships, in order. */
    private fun pathToMap(path: Path): Map<String, Any?> {
        return mapOf(
            "nodes" to path.nodes().map { toNative(it) },
            "edges" to path.relationships().map { toNative(it) },
        )
    }

    private fun recordToNative(rec: Record): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        rec.keys().forEachIndexed { index, key ->
            out[key] = toNative(rec.get(index))
        }
        return out
    }

}
