package org.drivine.migration

import org.drivine.connection.DatabaseType
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.test.PostgresTestContainer
import javax.sql.DataSource

/** [StoreLayoutMigrationStoreTest] on Postgres. */
@PostgresTests
class StoreLayoutMigrationPostgresTest : StoreLayoutMigrationStoreTest(DatabaseType.POSTGRES) {
    override val dataSource: DataSource get() = PostgresTestDatabase.provider.nativeDriver
    override val url: String get() = PostgresTestContainer.getJdbcUrl()
    override val user: String get() = PostgresTestContainer.getConnectionUsername()
    override val password: String get() = PostgresTestContainer.getConnectionPassword()

    override fun json(column: String) = "$column::text"

    override fun tables(graph: String) =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE '${graph}\\_%'"

    override fun indexes(graph: String) =
        "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename LIKE '${graph}\\_%'"

    override fun stopAtRobot(graph: String, sleep: Boolean) {
        val stop = if (sleep) "PERFORM pg_sleep(600);" else "RAISE EXCEPTION 'stopped by the test';"
        execute(
            """
            CREATE FUNCTION ${graph}_test_stop() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF OLD.props->>'name' = 'R2' THEN $stop END IF; RETURN NEW; END
            ${'$'}${'$'}
            """.trimIndent(),
        )
        execute("CREATE TRIGGER ${graph}_test_stop BEFORE UPDATE ON ${graph}_node FOR EACH ROW EXECUTE FUNCTION ${graph}_test_stop()")
    }

    override fun unstop(graph: String) {
        execute("DROP TRIGGER IF EXISTS ${graph}_test_stop ON ${graph}_node")
        execute("DROP FUNCTION IF EXISTS ${graph}_test_stop()")
    }

    // The sleeping session's statement is the migration's update of the graph's nodes, which names the graph.
    override fun killSleeper(graph: String): Boolean = dataSource.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                    "WHERE wait_event = 'PgSleep' AND pid <> pg_backend_pid() AND query LIKE '%${graph}%'",
            )
                .use { rs -> generateSequence { if (rs.next()) rs.getBoolean(1) else null }.toList().any { it } }
        }
    }

    override fun propertyIs(property: String, text: String) = "n.props->>'$property' = '$text'"
}
