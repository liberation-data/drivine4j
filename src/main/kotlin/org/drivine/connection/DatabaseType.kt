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
    /** Whether the engine reads Cypher. A store that speaks SQL does not, and is sent none. */
    val speaksCypher: Boolean = true,
) {
    NEO4J("NEO4J"),
    /**
     * Postgres, 14 or later, over JDBC. It speaks SQL: a graph is two tables, read with joins over them
     * and written through a function the store keeps, with pgvector for vector search.
     */
    POSTGRES("POSTGRES", speaksCypher = false),
    NEPTUNE("NEPTUNE"),
    FALKORDB("FALKORDB"),
    MEMGRAPH("MEMGRAPH"),

    /**
     * Oracle Database, 23ai or later, over JDBC. It speaks SQL: a graph is a SQL property graph
     * over tables, read with `GRAPH_TABLE` and written as rows of those tables.
     */
    ORACLE("ORACLE", speaksCypher = false),

    /**
     * SQLite, 3.45 or later, over JDBC with xerial's driver: a graph is the same two tables Oracle and
     * Postgres keep, in a file, read with joins over them and written through functions Drivine
     * registers on each connection. Built from properties with the file's path as the database name.
     */
    SQLITE("SQLITE", speaksCypher = false),

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
