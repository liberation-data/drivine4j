package org.drivine.annotation

enum class Direction {
    OUTGOING,
    INCOMING,
    UNDIRECTED
}


@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphRelationship(
    val type: String,                 // Neo4j rel type, e.g. "HAS_HOLIDAY"
    val direction: Direction = Direction.OUTGOING
)
