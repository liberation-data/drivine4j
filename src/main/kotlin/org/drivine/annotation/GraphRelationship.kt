package org.drivine.annotation

enum class Direction {
    OUTGOING,
    INCOMING,
    UNDIRECTED
}


@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphRelationship(
    val type: String,                 // Neo4j rel type, e.g. "HAS_HOLIDAY"
    val direction: Direction = Direction.OUTGOING,
    val maxDepth: Int = 1             // Max expansion depth for recursive relationships
)
