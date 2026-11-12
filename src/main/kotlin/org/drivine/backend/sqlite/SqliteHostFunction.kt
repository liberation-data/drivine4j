package org.drivine.backend.sqlite

import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostCheck
import org.drivine.cypher.host.HostFunction
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.sqlite.Function
import org.sqlite.core.Codes
import java.sql.Connection
import java.sql.SQLException

/**
 * One of the shared module's host functions ([HostFunction], see `org.drivine.cypher.host.HostFunctions`), registered on a
 * connection of xerial's driver: SQLite calls [xFunc], which hands the function its arguments as [HostArgs] and takes its
 * result as [HostResult], each read and write one of xerial's. A function that throws fails the statement with its
 * message, as the module's SQLite extension fails it.
 *
 * xerial reads and writes every text as a Java string, so a function's [HostFunction.encoding] makes no difference here:
 * UTF-8's and UTF-16's reads and writes are the same. They differ only for a lone surrogate, which the module's extension
 * writes as U+FFFD through UTF-8 and keeps through UTF-16, and xerial need not do either. No host function registered
 * here reads or writes UTF-16.
 */
internal class SqliteHostFunction(private val function: HostFunction) : Function() {

    override fun xFunc() {
        try {
            function.body.call(hostArgs, args(), hostResult)
        } catch (e: Exception) {
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    private val hostArgs = object : HostArgs {
        override fun type(i: Int): SqlType = when (value_type(i)) {
            Codes.SQLITE_INTEGER -> SqlType.INTEGER
            Codes.SQLITE_FLOAT -> SqlType.FLOAT
            Codes.SQLITE_TEXT -> SqlType.TEXT
            Codes.SQLITE_BLOB -> SqlType.BLOB
            else -> SqlType.NULL
        }

        override fun long(i: Int): Long = value_long(i)

        override fun int(i: Int): Int = value_int(i)

        override fun double(i: Int): Double = value_double(i)

        override fun text(i: Int): String? = value_text(i)

        override fun text16(i: Int): String? = value_text(i)

        override fun blob(i: Int): ByteArray? = value_blob(i)
    }

    private val hostResult = object : HostResult {
        override fun nullValue() = result()

        override fun long(value: Long) = result(value)

        override fun int(value: Int) = result(value)

        override fun double(value: Double) = result(value)

        override fun text(value: String) = result(value)

        override fun text16(value: String) = result(value)

        override fun blob(value: ByteArray) = result(value)

        override fun error(message: String) = this@SqliteHostFunction.error(message)
    }

    companion object {
        /** SQLite's `SQLITE_INNOCUOUS`: the function has no side effects, so a trigger or view of an untrusted schema may call it. */
        private const val FLAG_INNOCUOUS = 0x00200000

        /** SQLite's `SQLITE_DIRECTONLY`: only a statement of the application's own may call the function. */
        private const val FLAG_DIRECTONLY = 0x00080000

        /** Registers [function] on [connection], of the arity and with the flags it declares. */
        fun register(connection: Connection, function: HostFunction) {
            val flags = (if (function.deterministic) FLAG_DETERMINISTIC else 0) or
                (if (function.innocuous) FLAG_INNOCUOUS else 0) or
                (if (function.directOnly) FLAG_DIRECTONLY else 0)
            Function.create(connection, function.name, SqliteHostFunction(function), function.arity, flags)
        }

        /**
         * Checks the host functions registered on [connection] are those the compiler's SQL calls ([HostCheck]): it throws
         * where they are of another version, or where SQLite has no `drivine_host_version()`.
         */
        fun check(connection: Connection) {
            val reported = try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(HostCheck.SQL).use { rows -> rows.next(); rows.getLong(1).takeUnless { rows.wasNull() } }
                }
            } catch (e: SQLException) {
                if (e.message?.contains("no such function") == true) null else throw e
            }
            HostCheck.verify(reported)
        }
    }
}
