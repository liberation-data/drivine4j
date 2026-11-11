package org.drivine.mapper

import com.falkordb.graph_entities.Edge
import com.falkordb.graph_entities.GraphEntity
import com.falkordb.graph_entities.Node
import com.falkordb.graph_entities.Path
import org.drivine.query.GraphNode
import org.drivine.query.GraphPath
import org.drivine.query.GraphRelationship

/**
 * A value FalkorDB's client gives as [org.drivine.query.QueryResult] gives it: a node, an edge and a path, at any depth in a
 * list or a map, as a [GraphNode], a [GraphRelationship] and a [GraphPath]; anything else, a node's properties among them, as
 * [FalkorDbResultMapper.toNative] gives it to `query`. Unlike `query`, a node's labels and an edge's type and ends are not
 * among its properties.
 */
internal object FalkorDbTypedValues {

    private val native = FalkorDbResultMapper()

    fun typed(value: Any?): Any? = when (value) {
        null -> null
        is Node -> node(value)
        is Edge -> relationship(value)
        is Path -> path(value)
        is List<*> -> value.map(::typed)
        is Map<*, *> -> value.mapValues { typed(it.value) }
        else -> native.toNative(value)
    }

    private fun properties(entity: GraphEntity): Map<String, Any?> =
        entity.entityPropertyNames.associateWith { name -> entity.getProperty(name)?.value?.let(native::toNative) }

    private fun node(node: Node) = GraphNode((0 until node.numberOfLabels).mapNotNull(node::getLabel), properties(node))

    private fun relationship(edge: Edge) = GraphRelationship(edge.relationshipType.orEmpty(), properties(edge))

    /** An edge is walked along its direction where it leaves the node before it. */
    private fun path(path: Path): GraphPath = GraphPath(
        path.nodes.map(::node),
        path.edges.map(::relationship),
        path.edges.mapIndexed { i, edge -> edge.source == path.nodes[i].id },
    )
}
