package org.drivine.schema

/**
 * Which registered databases a [SchemaCatalog] applies to.
 *
 * The default is [All] — a catalog broadcasts to every schema-capable registered database until
 * it is narrowed to specific ones with [SchemaCatalog.forDatabase] / [SchemaCatalog.forDatabases].
 */
sealed interface DatabaseTarget {

    /** Apply to every schema-capable registered database. */
    object All : DatabaseTarget

    /** Apply only to the named databases. */
    data class Named(val databases: Set<String>) : DatabaseTarget {
        init {
            require(databases.isNotEmpty()) { "A Named database target requires at least one database" }
        }
    }

    fun includes(database: String): Boolean = when (this) {
        is All -> true
        is Named -> database in databases
    }
}
