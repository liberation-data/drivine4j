package org.drivine.backend.sqlite

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * The connections to the SQLite file at [path], each opened as Drivine's statements want it, with the
 * functions they call registered on it (see [SqliteFunctions]). A pool asks it for each connection it
 * keeps.
 *
 * - The journal is a write-ahead log, so readers do not wait for the writer, nor the writer for them.
 * - Foreign keys are enforced, so a node's relationships go with it.
 * - A connection that finds the file locked waits for it, up to [busyTimeoutMillis], before it gives up.
 * - A transaction takes the write lock as it begins (`BEGIN IMMEDIATE`), so one that reads and then
 *   writes is not turned away for what another wrote in between: two such transactions wait their turn.
 * - The log is synced at a checkpoint and not at each commit, as is safe with a write-ahead log: a commit
 *   may be lost to a power cut, and the file is never left corrupt.
 */
internal class SqliteDataSource(private val path: String, private val busyTimeoutMillis: Int = DEFAULT_BUSY_TIMEOUT) : DataSource {

    private val driver = SQLiteDataSource(
        SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            enforceForeignKeys(true)
            setBusyTimeout(busyTimeoutMillis)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        },
    ).apply { url = "jdbc:sqlite:$path" }

    override fun getConnection(): Connection = driver.connection.also(SqliteFunctions::register)

    /** A SQLite file has no users: the credentials are not asked for. */
    override fun getConnection(username: String?, password: String?): Connection = connection

    override fun getLogWriter(): PrintWriter? = driver.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        driver.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        driver.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = driver.loginTimeout

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("SQLite's data source has no parent logger.")

    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else driver.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || driver.isWrapperFor(iface)

    companion object {
        /** How long a connection waits for a locked file: long enough for any one save to finish. */
        const val DEFAULT_BUSY_TIMEOUT = 30_000
    }
}
