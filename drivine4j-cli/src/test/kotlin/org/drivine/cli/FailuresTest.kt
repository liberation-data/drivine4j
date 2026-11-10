package org.drivine.cli

import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.sql.SQLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which failures exit with 3, the store not reached or the user refused, and which with 1, the statement's. The
 * drivers' exceptions are made by name, as [Failures] knows them: the CLI doesn't compile against the drivers.
 */
class FailuresTest {

    private fun made(type: String, vararg args: Any?): Throwable {
        val types = args.map { if (it is Throwable) Throwable::class.java else String::class.java }.toTypedArray()
        return Class.forName(type).getConstructor(*types).newInstance(*args) as Throwable
    }

    /** As Drivine wraps what a driver throws. */
    private fun wrapped(cause: Throwable) = RuntimeException("Drivine: the statement failed", cause)

    @Test
    fun `a store not reached, wherever it is in the causes, is unreachable`() {
        assertTrue(Failures.unreachable(wrapped(ConnectException("Connection refused"))))
        assertTrue(Failures.unreachable(wrapped(made("org.neo4j.driver.exceptions.ServiceUnavailableException", "no server"))))
        assertTrue(Failures.unreachable(made("redis.clients.jedis.exceptions.JedisConnectionException", "refused")))
        assertTrue(Failures.unreachable(SQLException("refused", "08001")))
        assertTrue(Failures.unreachable(wrapped(made("org.neo4j.driver.exceptions.SecurityException", "TLS failed", SSLHandshakeException("handshake")))))
    }

    @Test
    fun `a user or password the store refuses is unreachable`() {
        assertTrue(Failures.unreachable(made("org.neo4j.driver.exceptions.AuthenticationException", "Neo.ClientError.Security.Unauthorized", "no")))
        assertTrue(Failures.unreachable(made("org.neo4j.driver.exceptions.TokenExpiredException", "Neo.ClientError.Security.TokenExpired", "no")))
        assertTrue(Failures.unreachable(made("redis.clients.jedis.exceptions.JedisAccessControlException", "WRONGPASS invalid username-password pair")))
        assertTrue(Failures.unreachable(SQLException("password authentication failed", "28P01")))
        assertTrue(Failures.unreachable(SQLException("ORA-01017: invalid credential or not authorized; logon denied", "72000", 1017)))
        assertTrue(Failures.unreachable(SQLException("ORA-12541: Cannot connect. No listener", null, 12541)))
    }

    @Test
    fun `a statement the signed-in user may not run is the statement's failure`() {
        assertFalse(Failures.unreachable(wrapped(made("org.neo4j.driver.exceptions.SecurityException", "Neo.ClientError.Security.Forbidden", "Permission denied"))))
        assertFalse(Failures.unreachable(made("redis.clients.jedis.exceptions.JedisAccessControlException", "NOPERM User u has no permissions to run the 'GRAPH.QUERY' command")))
    }

    @Test
    fun `a statement's failure is not unreachable`() {
        assertFalse(Failures.unreachable(wrapped(IllegalStateException("division by zero"))))
        assertFalse(Failures.unreachable(made("org.neo4j.driver.exceptions.ClientException", "Neo.ClientError.Statement.SyntaxError", "bad")))
        assertFalse(Failures.unreachable(SQLException("syntax error", "42601")))
        // SQLite's result code 1017 isn't Oracle's ORA-01017.
        assertFalse(Failures.unreachable(SQLException("[SQLITE_ERROR] SQL error", null, 1017)))
    }
}
