package org.drivine.connection

/**
 * The engine a datasource speaks. Members name engines, not deployment
 * topologies: [buildableFromProperties] carries the one topology fact the
 * registry needs — whether host/port can describe a connection at all.
 */
enum class DatabaseType(
    val value: String,
    /**
     * Whether [ConnectionProviderBuilder] can build a provider for this engine
     * from connection properties. False for engines with no wire to describe;
     * the application registers a [ConnectionProvider] instead.
     */
    val buildableFromProperties: Boolean = true,
) {
    NEO4J("NEO4J"),
    POSTGRES("POSTGRES"),
    NEPTUNE("NEPTUNE"),
    FALKORDB("FALKORDB"),
    MEMGRAPH("MEMGRAPH"),

    /**
     * Embabel's Cypher engine — compiler, evaluator and store living inside the
     * application process, with no wire and no container. Connection properties
     * cannot build one, so the application supplies its own [ConnectionProvider]
     * (as a Spring bean with the starter, or via [DatabaseRegistry.register]).
     */
    EMBABEL("EMBABEL", buildableFromProperties = false),
    ;

    companion object {
        fun fromValue(value: String): DatabaseType? {
            return values().find { it.value == value }
        }
    }
}
