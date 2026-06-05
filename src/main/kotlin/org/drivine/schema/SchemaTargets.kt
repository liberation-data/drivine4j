package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseRegistry

/**
 * Resolves which registered databases a set of [SchemaCatalog]s applies to.
 *
 * The rules differ by target so that the "broadcast by default" model stays safe on a mixed fleet:
 *  - [DatabaseTarget.All] (the default) contributes every schema-capable registered database;
 *    engines without DDL support (Neptune, openCypher) are reported in [Resolution.skipped] rather
 *    than failing startup.
 *  - [DatabaseTarget.Named] is strict — an unknown or schema-incapable named target throws, because
 *    the consumer explicitly asked for schema on a database that can't provide it.
 */
object SchemaTargets {

    data class Resolution(
        /** Databases to initialize. */
        val databases: Set<String>,
        /** Schema-incapable databases skipped by a broadcast (All) catalog. */
        val skipped: Set<String>,
    )

    fun resolve(catalogs: List<SchemaCatalog>, registry: DatabaseRegistry): Resolution {
        val nonEmpty = catalogs.filter { !it.isEmpty() }
        val capableByName = registry.databaseNames.associateWith { name ->
            registry.connectionProvider(name)?.supportsSchemaManagement == true
        }

        val databases = linkedSetOf<String>()
        var skipped = emptySet<String>()

        if (nonEmpty.any { it.target is DatabaseTarget.All }) {
            databases += capableByName.filterValues { it }.keys
            skipped = capableByName.filterValues { !it }.keys
        }

        nonEmpty.forEach { catalog ->
            val target = catalog.target
            if (target !is DatabaseTarget.Named) return@forEach
            target.databases.forEach { requested ->
                val resolved = registry.resolveDatabaseName(requested)
                    ?: throw DrivineException(
                        "Schema catalog targets database '$requested', which is not registered. " +
                            "Registered databases: ${registry.databaseNames}"
                    )
                if (capableByName[resolved] != true) {
                    throw DrivineException(
                        "Schema catalog explicitly targets database '$requested' (resolved to '$resolved'), " +
                            "whose engine does not support schema management."
                    )
                }
                databases += resolved
            }
        }

        return Resolution(databases, skipped)
    }
}