package org.drivine.connection

import org.drivine.query.grammar.CypherDialect

/**
 * What a provider of one database is built from (see [ConnectionProviderBuilder]). Its [toString] prints the password as
 * `***`, so a properties object an application logs, or a [DataSourceMap] of them, doesn't show it.
 */
data class ConnectionProperties(
    val type: DatabaseType,
    /** Empty for engines with no wire — see [DatabaseType.buildableFromProperties]. */
    val host: String = "",
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
    val region: String? = null,
    val neptuneAuth: NeptuneAuthMode? = null,
) {
    override fun toString(): String =
        "ConnectionProperties(type=$type, host=$host, port=$port, userName=$userName, password=${password?.let { "***" }}, " +
            "idleTimeout=$idleTimeout, connectionTimeout=$connectionTimeout, poolMax=$poolMax, databaseName=$databaseName, " +
            "defaultGraphPath=$defaultGraphPath, protocol=$protocol, cypherDialect=$cypherDialect, " +
            "falkorDbTransactionMode=$falkorDbTransactionMode, region=$region, neptuneAuth=$neptuneAuth)"
}
