package org.drivine.connection

enum class DatabaseType(val value: String) {
    NEO4J("NEO4J"),
    POSTGRES("POSTGRES"),
    NEPTUNE("NEPTUNE"),
    FALKORDB("FALKORDB"),
    MEMGRAPH("MEMGRAPH"),

    /**
     * An engine living inside the application process — no wire, no container.
     * Connection properties cannot build one: the application supplies its own
     * [ConnectionProvider] (as a Spring bean with the starter, or via
     * [DatabaseRegistry.register]).
     */
    IN_PROCESS("IN_PROCESS");

    companion object {
        fun fromValue(value: String): DatabaseType? {
            return values().find { it.value == value }
        }
    }
}
