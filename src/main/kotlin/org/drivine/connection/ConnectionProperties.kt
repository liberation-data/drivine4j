package org.drivine.connection

import org.drivine.query.grammar.CypherDialect

data class ConnectionProperties(
    val type: DatabaseType,
    val host: String,
    val port: Int? = null,
    val userName: String? = null,
    val password: String? = null,
    val idleTimeout: Int? = null,
    val connectionTimeout: Int? = null,
    val poolMax: Int? = null,
    val databaseName: String? = null,
    val defaultGraphPath: String? = null,
    val protocol: String? = null,
    val cypherDialect: CypherDialect? = null,
    val falkorDbTransactionMode: FalkorDbTransactionMode? = null,
) {}
