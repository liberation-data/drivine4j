package org.drivine.migration

import org.drivine.connection.DatabaseType
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.test.OracleTestContainer
import java.sql.DriverManager
import java.sql.SQLException
import javax.sql.DataSource

/** [StoreLayoutMigrationStoreTest] on Oracle. */
@OracleTests
class StoreLayoutMigrationOracleTest : StoreLayoutMigrationStoreTest(DatabaseType.ORACLE) {
    override val dataSource: DataSource get() = OracleTestDatabase.provider.nativeDriver
    override val url: String get() = OracleTestContainer.getJdbcUrl()
    override val user: String get() = OracleTestContainer.getConnectionUsername()
    override val password: String get() = OracleTestContainer.getConnectionPassword()

    // Extended, so that a binary double is told from a NUMBER, as the migration reads them.
    override fun json(column: String) = "JSON_SERIALIZE($column RETURNING CLOB EXTENDED)"

    override fun tables(graph: String) = "SELECT table_name FROM user_tables WHERE table_name LIKE UPPER('${graph}\\_%') ESCAPE '\\'"

    // The indexes Oracle names itself (a primary key's, a LOB's) are named apart in each graph, and left out. An index built
    // over rows is written COMPUTE STATISTICS, one built on an empty table not: how it was built, not what it indexes.
    override fun indexes(graph: String) =
        "SELECT index_name, REPLACE(DBMS_METADATA.GET_DDL('INDEX', index_name), ' COMPUTE STATISTICS') FROM user_indexes " +
            "WHERE table_name LIKE UPPER('${graph}\\_%') ESCAPE '\\' AND index_name NOT LIKE 'SYS\\_%' ESCAPE '\\'"

    override fun stopAtRobot(graph: String, sleep: Boolean) {
        // The sleeper names its graph as its action, for killSleeper to kill it alone.
        val stop = if (sleep) "DBMS_APPLICATION_INFO.SET_ACTION('$graph'); DBMS_SESSION.SLEEP(600);" else "RAISE_APPLICATION_ERROR(-20001, 'stopped by the test');"
        execute(
            """
            CREATE OR REPLACE TRIGGER ${graph}_test_stop BEFORE UPDATE ON ${graph}_node FOR EACH ROW
            DECLARE
                v_name VARCHAR2(400);
            BEGIN
                SELECT JSON_VALUE(:OLD.props, '${'$'}.name') INTO v_name FROM dual;
                IF v_name = 'R2' THEN $stop END IF;
            END;
            """.trimIndent(),
        )
    }

    override fun unstop(graph: String) {
        execute("DROP TRIGGER IF EXISTS ${graph}_test_stop")
    }

    // As system, as the test user may not see or kill another session.
    override fun killSleeper(graph: String): Boolean =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.PASSWORD).use { admin ->
            val sessions = admin.createStatement().use { s ->
                s.executeQuery("SELECT sid || ',' || serial# FROM v\$session WHERE username = UPPER('$user') AND event = 'PL/SQL lock timer' AND action = '$graph'")
                    .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
            sessions.forEach { session ->
                // ORA-00031: the session is marked to be killed, as it will be once its call returns: killed all the same.
                runCatching { admin.createStatement().use { it.execute("ALTER SYSTEM KILL SESSION '$session' IMMEDIATE") } }
                    .onFailure { if ((it as? SQLException)?.errorCode != 31) throw it }
            }
            sessions.isNotEmpty()
        }

    override fun propertyIs(property: String, text: String) = "JSON_VALUE(n.props, '\$.$property') = '$text'"
}
