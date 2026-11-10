package org.drivine.cli

import java.sql.SQLException

/**
 * Tells a failure to reach a store, or a store's refusal of the user and password, from a statement's failure, so the
 * CLI exits with 3 for the one and 1 for the other. Drivine wraps what a driver throws, so each cause is looked at.
 *
 * The drivers' own exceptions are known by their class names: the Neo4j driver and FalkorDB's client are Drivine's to
 * depend on, not the CLI's to compile against.
 */
internal object Failures {

    /**
     * What the Neo4j driver (Neo4j and Memgraph), FalkorDB's client (Jedis) and the JDK throw for a store not reached or a
     * user refused. Not the Neo4j driver's `SecurityException` as a whole: it also throws one for a statement the user
     * may not run (`Neo.ClientError.Security.Forbidden`), which is the statement's failure. A TLS handshake that fails is
     * a `SecurityException` caused by an [javax.net.ssl.SSLException], which is named here.
     */
    private val UNREACHABLE = setOf(
        "org.neo4j.driver.exceptions.ServiceUnavailableException",
        "org.neo4j.driver.exceptions.AuthenticationException",
        "org.neo4j.driver.exceptions.AuthorizationExpiredException",
        "org.neo4j.driver.exceptions.TokenExpiredException",
        "redis.clients.jedis.exceptions.JedisConnectionException",
        "redis.clients.jedis.exceptions.JedisAccessControlException",
        "javax.net.ssl.SSLException",
        "com.zaxxer.hikari.pool.HikariPool\$PoolInitializationException",
        "java.net.ConnectException",
        "java.net.UnknownHostException",
        "java.net.NoRouteToHostException",
        "java.sql.SQLTransientConnectionException",
        "java.sql.SQLNonTransientConnectionException",
        "java.sql.SQLInvalidAuthorizationSpecException",
    )

    /** Oracle's codes for a user refused (`ORA-01017`) and an account locked (`ORA-28000`). */
    private val ORACLE_REFUSED = setOf(1017, 28000)

    /** Oracle's listener and network codes, `ORA-12500` to `ORA-12599`, and `ORA-17002`, an I/O failure on connecting. */
    private fun oracleUnreachable(code: Int) = code in 12500..12599 || code == 17002

    fun unreachable(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_DEPTH).any { cause ->
            (named(cause.javaClass) && !forbidden(cause)) || (cause is SQLException && sqlUnreachable(cause))
        }

    /** Jedis's `NOPERM`, a command the user, signed in, may not run: the statement's failure, as Neo4j's `Forbidden` is. */
    private fun forbidden(cause: Throwable) = cause.message.orEmpty().startsWith("NOPERM")

    private fun named(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }.any { it.name in UNREACHABLE }

    /** SQL state class `08`, a connection that failed, or `28`, a user the store refused, or Oracle's own codes for them. */
    private fun sqlUnreachable(e: SQLException): Boolean {
        val state = e.sqlState.orEmpty()
        // An error code is the driver's own: SQLite's are its result codes, so Oracle's are read only from Oracle's driver.
        val oracle = e.javaClass.name.startsWith("oracle.") || e.message.orEmpty().startsWith("ORA-")
        return state.startsWith("08") || state.startsWith("28") || (oracle && (e.errorCode in ORACLE_REFUSED || oracleUnreachable(e.errorCode)))
    }

    private const val MAX_DEPTH = 12
}
