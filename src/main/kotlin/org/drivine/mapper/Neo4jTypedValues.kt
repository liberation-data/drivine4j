package org.drivine.mapper

import org.drivine.query.GraphNode
import org.drivine.query.GraphPath
import org.drivine.query.GraphRelationship
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.NullValue
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Path
import org.neo4j.driver.types.Relationship
import org.neo4j.driver.types.TypeSystem

/**
 * A value the Neo4j driver gives, on Neo4j, Memgraph or Neptune, as [org.drivine.query.QueryResult] gives it: a node, a
 * relationship and a path, at any depth in a list or a map, as a [GraphNode], a [GraphRelationship] and a [GraphPath];
 * anything else, a node's properties among them, as [Neo4jResultMapper.toNative] gives it to `query`.
 */
internal object Neo4jTypedValues {

    private val native = Neo4jResultMapper()

    private val types = TypeSystem.getDefault()

    fun typed(value: Any?): Any? = when (value) {
        null, is NullValue -> null
        is Node -> node(value)
        is Relationship -> relationship(value)
        is Path -> path(value)
        is Value -> when {
            value.hasType(types.NODE()) -> node(value.asNode())
            value.hasType(types.RELATIONSHIP()) -> relationship(value.asRelationship())
            value.hasType(types.PATH()) -> path(value.asPath())
            value.hasType(types.LIST()) -> value.asList { typed(it) }
            value.hasType(types.MAP()) -> value.asMap({ typed(it) }, null)
            else -> native.toNative(value)
        }
        is List<*> -> value.map(::typed)
        is Map<*, *> -> value.mapValues { typed(it.value) }
        else -> native.toNative(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun properties(entity: org.neo4j.driver.types.Entity): Map<String, Any?> = native.toNative(entity.asMap()) as Map<String, Any?>

    private fun node(node: Node) = GraphNode(node.labels().toList(), properties(node))

    private fun relationship(relationship: Relationship) = GraphRelationship(relationship.type(), properties(relationship))

    /** Each segment walks its relationship from its start: along the relationship's direction where that is where it starts. */
    private fun path(path: Path): GraphPath {
        val segments = path.toList()
        return GraphPath(
            path.nodes().map(::node),
            path.relationships().map(::relationship),
            segments.map { it.relationship().startNodeElementId() == it.start().elementId() },
        )
    }
}
