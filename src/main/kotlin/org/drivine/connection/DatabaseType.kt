package org.drivine.connection

enum class DatabaseType(val value: String) {
    NEO4J("NEO4J"),
    POSTGRES("POSTGRES"),
    NEPTUNE("NEPTUNE"),
    FALKORDB("FALKORDB"),
    MEMGRAPH("MEMGRAPH");

    companion object {
        fun fromValue(value: String): DatabaseType? {
            return values().find { it.value == value }
        }
    }
}
