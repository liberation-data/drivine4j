package org.drivine.mapper

import com.falkordb.Record
import com.falkordb.graph_entities.Edge
import com.falkordb.graph_entities.Node
import com.falkordb.graph_entities.Path
import com.falkordb.graph_entities.Point
import com.falkordb.graph_entities.Property

class FalkorDbResultMapper(
    subtypeRegistry: SubtypeRegistry? = null
) : GraphResultMapper(subtypeRegistry) {

    override fun keys(record: Any): List<String> {
        val rec = record as Record
        return rec.keys()
    }

    override fun itemAtIndex(record: Any, index: Int): Any {
        val rec = record as Record
        return rec.getValue<Any>(index)
    }

    override fun toNative(value: Any): Any? {
        return when (value) {
            null -> null
            is Node -> nodeToMap(value)
            is Edge -> edgeToMap(value)
            is Path -> pathToMap(value)
            is Point -> mapOf("latitude" to value.latitude, "longitude" to value.longitude)
            is Property<*> -> value.value?.let { toNative(it) }
            is List<*> -> value.map { it?.let { v -> toNative(v) } }
            is Map<*, *> -> value.mapValues { it.value?.let { v -> toNative(v) } }
            is ByteArray -> String(value)
            else -> value
        }
    }

    private fun nodeToMap(node: Node): Map<String, Any?> {
        val map = entityPropertiesToMap(node)
        val labels = (0 until node.numberOfLabels).map { node.getLabel(it) }
        if (labels.isNotEmpty()) {
            map["labels"] = labels
        }
        return map
    }

    private fun edgeToMap(edge: Edge): Map<String, Any?> {
        val map = entityPropertiesToMap(edge)
        map["_type"] = edge.relationshipType
        map["_source"] = edge.source
        map["_destination"] = edge.destination
        return map
    }

    private fun pathToMap(path: Path): Map<String, Any?> {
        return mapOf(
            "nodes" to path.nodes.map { nodeToMap(it) },
            "edges" to path.edges.map { edgeToMap(it) }
        )
    }

    private fun entityPropertiesToMap(entity: com.falkordb.graph_entities.GraphEntity): MutableMap<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        entity.entityPropertyNames.forEach { name ->
            val prop = entity.getProperty(name)
            map[name] = prop?.value?.let { toNative(it) }
        }
        return map
    }
}