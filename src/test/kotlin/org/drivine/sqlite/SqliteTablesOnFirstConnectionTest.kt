package org.drivine.sqlite

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.backend.sqlite.SqliteDataSource
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.TablesOnFirstConnectionContract
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import javax.sql.DataSource

/** The graph's tables made on a SQLite provider's first connection, in a file of its own for each test. */
class SqliteTablesOnFirstConnectionTest : TablesOnFirstConnectionContract() {

    @TempDir
    lateinit var directory: Path

    private val path: String
        get() = directory.resolve("fresh.db").toString()

    override val writesAtOnce = false

    override fun provider(graph: String): JdbcConnectionProvider = JdbcConnectionProvider.sqlite("fresh", path, graphName = graph)

    override fun notCommitting(graph: String): JdbcConnectionProvider = JdbcConnectionProvider(
        "fresh",
        DatabaseType.SQLITE,
        HikariDataSource(HikariConfig().apply { dataSource = SqliteDataSource(path); isAutoCommit = false }),
        ownsDataSource = true,
        graphName = graph,
    )

    /** Connections that refuse to write (`query_only`): SQLite has no users, and a file's own permissions are the platform's. */
    override fun readOnly(graph: String): JdbcConnectionProvider {
        val file = SqliteDataSource(path)
        val reading = object : DataSource by file {
            override fun getConnection() = file.connection.also { c -> c.createStatement().use { it.execute("PRAGMA query_only = ON") } }
        }
        return JdbcConnectionProvider("fresh", DatabaseType.SQLITE, reading, graphName = graph)
    }

    override fun tables(graph: String): List<String> = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.prepareStatement("SELECT lower(name) FROM sqlite_master WHERE type = 'table' AND lower(name) LIKE ? ESCAPE '\\' ORDER BY 1").use { s ->
            s.setString(1, "${graph.lowercase()}\\_%")
            s.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }
}
