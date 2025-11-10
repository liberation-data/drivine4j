package org.drivine.connection

enum class DatabaseType(val value: String) {
    AGENS_GRAPH("AGENS_GRAPH"),
    NEO4J("NEO4J"),
    POSTGRES("POSTGRES"),
    NEPTUNE("NEPTUNE");

    companion object {
        fun fromValue(value: String): DatabaseType? {
            return values().find { it.value == value }
        }
    }
}
