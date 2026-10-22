package org.drivine.oracle

import com.zaxxer.hikari.HikariDataSource
import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.query.QuerySpecification
import org.drivine.test.OracleTestContainer
import org.drivine.query.cypherStatement
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.stateless.Human
import sample.stateless.Memo
import sample.stateless.MemoView
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SQL through `Connection` and `PersistenceManager` on Oracle: parameters, the types that come back,
 * transactions, a block that returns rows, and a `@GraphView` hydrated from one JSON column.
 */
@OracleTests
class OracleConnectionIntegrationTest {

    private val pm = OracleTestDatabase.pm

    private fun <T> connected(work: (Connection) -> T): T {
        val connection = OracleTestDatabase.provider.connect()
        try {
            return work(connection)
        } finally {
            connection.release()
        }
    }

    private fun spec(sql: String, vararg parameters: Pair<String, Any?>) = QuerySpecification.withStatement(sql.trimIndent()).bind(mapOf(*parameters))

    private fun names(): List<String> = pm.query(spec("SELECT name FROM pm_person ORDER BY name").transform(String::class.java))

    @BeforeEach
    fun clean() = OracleTestDatabase.run("DELETE FROM pm_mentions", "DELETE FROM pm_person", "DELETE FROM pm_memo")

    // ----- Parameters and what comes back -----

    @Test
    fun `a parameter is bound by name, as often as the statement names it`() {
        assertEquals(listOf<Any>(7L), pm.query(spec("SELECT \$a + \$b + \$a FROM dual", "a" to 2, "b" to 3)))
    }

    @Test
    fun `a parameter the statement names and nothing binds is refused`() {
        val failure = assertFailsWith<DrivineException> { pm.query(spec("SELECT \$missing FROM dual")) }
        assertTrue("\$missing" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a row of one column is its value, a row of several is the list of them`() {
        pm.execute(spec("INSERT INTO pm_person (id, name, age, score) VALUES (\$id, \$name, \$age, \$score)", "id" to "ada", "name" to "Ada", "age" to 36, "score" to 0.5))

        assertEquals(listOf<Any>("Ada"), pm.query(spec("SELECT name FROM pm_person")))
        assertEquals(listOf<Any>(listOf("Ada", 36L, 0.5)), pm.query(spec("SELECT name, age, score FROM pm_person")))
    }

    @Test
    fun `a null parameter is stored as null, and a null column comes back null`() {
        pm.execute(spec("INSERT INTO pm_person (id, name, age) VALUES (\$id, \$name, \$age)", "id" to "ada", "name" to "Ada", "age" to null))

        assertEquals(listOf<Any?>(listOf("Ada", null)), pm.query(spec("SELECT name, age FROM pm_person")))
    }

    @Test
    fun `an instant is stored as a timestamp with its zone, and read back as the same instant`() {
        val born = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        pm.execute(spec("INSERT INTO pm_person (id, name, born) VALUES ('ada', 'Ada', \$born)", "born" to born))

        assertEquals(born, (pm.query(spec("SELECT born FROM pm_person")).single() as ZonedDateTime).toInstant())
        assertEquals(born, pm.getOne(spec("SELECT born FROM pm_person").transform(Instant::class.java)))
        assertEquals(listOf<Any>("Ada"), pm.query(spec("SELECT name FROM pm_person WHERE born >= \$born", "born" to born)))
    }

    @Test
    fun `a list is bound as JSON, for the statement to read as rows`() {
        pm.execute(
            spec(
                """
                INSERT INTO pm_person (id, name, age)
                SELECT id, name, age FROM JSON_TABLE(${'$'}people, '$[*]' COLUMNS (id VARCHAR2(64) PATH '$.id', name VARCHAR2(100) PATH '$.name', age NUMBER PATH '$.age'))
                """,
                "people" to listOf(mapOf("id" to "ada", "name" to "Ada", "age" to 36), mapOf("id" to "bob", "name" to "Bob", "age" to 41)),
            )
        )

        assertEquals(listOf("Ada", "Bob"), names())
    }

    @Test
    fun `skip and limit page a statement`() {
        ('a'..'e').forEach { pm.execute(spec("INSERT INTO pm_person (id, name) VALUES (\$id, \$id)", "id" to it.toString())) }

        assertEquals(listOf("b", "c"), pm.query(spec("SELECT name FROM pm_person ORDER BY name").skip(1).limit(2).transform(String::class.java)))
        assertEquals(listOf("a"), pm.query(spec("SELECT name FROM pm_person ORDER BY name").limit(1).transform(String::class.java)))
    }

    @Test
    fun `a vector is bound from a list of numbers and read back as one`() {
        pm.execute(spec("INSERT INTO pm_person (id, name, embedding) VALUES ('ada', 'Ada', TO_VECTOR(\$v))", "v" to listOf(1.0f, 0.0f, 0.0f)))
        pm.execute(spec("INSERT INTO pm_person (id, name, embedding) VALUES ('bob', 'Bob', TO_VECTOR(\$v))", "v" to floatArrayOf(0.0f, 1.0f, 0.0f)))

        assertEquals(listOf(1.0, 0.0, 0.0), pm.query(spec("SELECT embedding FROM pm_person WHERE id = 'ada'")).single())
        assertEquals(
            listOf("Bob", "Ada"),
            pm.query(
                spec("SELECT name FROM pm_person ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(\$q), COSINE)", "q" to listOf(0.1f, 0.9f, 0.0f))
                    .transform(String::class.java)
            ),
        )
    }

    @Test
    fun `a Cypher statement is compiled to SQL and runs`() {
        // CypherQueryContract tests what Cypher compiles to; here, that a connection compiles it at all.
        assertEquals(listOf<Any?>(2L), pm.query(QuerySpecification.withStatement(cypherStatement("RETURN 1 + 1"))))
    }

    @Test
    fun `connection properties build a provider for an Oracle service`() {
        val registry = DatabaseRegistry(DataSourceMap(mapOf("ora" to ConnectionProperties(
            type = DatabaseType.ORACLE, host = OracleTestContainer.getConnectionHost(), port = OracleTestContainer.getConnectionPort(),
            userName = OracleTestContainer.getConnectionUsername(), password = OracleTestContainer.getConnectionPassword(),
            databaseName = OracleTestContainer.SERVICE, defaultGraphPath = "props", poolMax = 3, connectionTimeout = 20_000,
        ))))
        val provider = assertNotNull(registry.connectionProvider("ora"))
        try {
            // What the properties say of the graph and of the pool is what the provider has.
            assertEquals("props", provider.graphName)
            val pool = assertIs<HikariDataSource>((provider as JdbcConnectionProvider).nativeDriver)
            assertEquals(3, pool.maximumPoolSize)
            assertEquals(20_000, pool.connectionTimeout)
            assertEquals("64", pool.dataSourceProperties.getProperty("oracle.jdbc.implicitStatementCacheSize"))
            val connection = provider.connect()
            try {
                assertEquals(listOf<Any>("Ada"), connection.query(spec("SELECT \$name FROM dual", "name" to "Ada")))
            } finally {
                connection.release()
            }
        } finally {
            provider.end()
        }
    }

    // ----- Documents -----

    @Test
    fun `a JSON column is a document, with whole numbers as longs`() {
        val row = pm.query(spec("SELECT JSON_OBJECT('name' VALUE 'Ada', 'age' VALUE 36, 'tags' VALUE JSON_ARRAY('a', 'b') RETURNING JSON) FROM dual")).single()

        assertEquals(mapOf("name" to "Ada", "age" to 36L, "tags" to listOf("a", "b")), row)
    }

    @Test
    fun `a view is hydrated from one JSON column read through a property graph`() {
        OracleTestDatabase.run(
            "INSERT INTO pm_memo (id, text) VALUES ('m1', 'Ada met Bob')",
            "INSERT INTO pm_person (id, name) VALUES ('ada', 'Ada')",
            "INSERT INTO pm_person (id, name) VALUES ('bob', 'Bob')",
            "INSERT INTO pm_mentions (memo_id, person_id) VALUES ('m1', 'bob')",
            "INSERT INTO pm_mentions (memo_id, person_id) VALUES ('m1', 'ada')",
        )

        val view = pm.getOne(
            spec(
                """
                SELECT JSON_OBJECT(
                    'memo' VALUE JSON_OBJECT('id' VALUE root.id, 'text' VALUE root.text),
                    'people' VALUE (
                        SELECT JSON_ARRAYAGG(JSON_OBJECT('id' VALUE g.id, 'name' VALUE g.name) ORDER BY g.name)
                        FROM GRAPH_TABLE (pm_graph MATCH (m IS Memo) -[IS MENTIONS]-> (h IS Human) COLUMNS (m.id AS root_id, h.id AS id, h.name AS name)) g
                        WHERE g.root_id = root.id
                    )
                    RETURNING JSON)
                FROM GRAPH_TABLE (pm_graph MATCH (m IS Memo) WHERE m.id = ${'$'}id COLUMNS (m.id AS id, m.text AS text)) root
                """,
                "id" to "m1",
            ).transform(MemoView::class.java)
        )

        assertEquals(MemoView(Memo("m1", "Ada met Bob"), listOf(Human("ada", "Ada"), Human("bob", "Bob"))), view)
    }

    // ----- Transactions -----

    @Test
    fun `a transaction is seen by its own connection, and by others once it commits`() = connected { connection ->
        connection.startTransaction()
        connection.query(spec("INSERT INTO pm_person (id, name) VALUES ('ada', 'Ada')"))

        assertEquals(listOf<Any>("Ada"), connection.query(spec("SELECT name FROM pm_person")))
        assertEquals(emptyList(), names())
        connection.commitTransaction()
        assertEquals(listOf("Ada"), names())
    }

    @Test
    fun `a transaction rolled back, or released while open, leaves nothing`() {
        connected { connection ->
            connection.startTransaction()
            connection.query(spec("INSERT INTO pm_person (id, name) VALUES ('ada', 'Ada')"))
            connection.rollbackTransaction()
            assertFailsWith<DrivineException> { connection.commitTransaction() }
        }
        connected { connection ->
            connection.startTransaction()
            connection.query(spec("INSERT INTO pm_person (id, name) VALUES ('bob', 'Bob')"))
        }

        assertEquals(emptyList(), names())
    }

    @Test
    fun `a batch is applied whole or not at all`() {
        val insert = "INSERT INTO pm_person (id, name) VALUES (\$id, \$id)"
        assertFailsWith<DrivineException> { pm.executeBatch(listOf(spec(insert, "id" to "a"), spec(insert, "id" to "b"), spec(insert, "id" to "a"))) }
        assertEquals(emptyList(), names())

        assertEquals(listOf(emptyList(), listOf<Any?>(1L)), pm.queryBatch(listOf(spec(insert, "id" to "a"), spec("SELECT COUNT(*) FROM pm_person"))))
        assertEquals(listOf("a"), names())
    }

    // ----- A block: one statement that writes and returns rows -----

    private val block = """
        DECLARE
            v_id   VARCHAR2(64) := ${'$'}id;
            v_rows SYS_REFCURSOR;
        BEGIN
            INSERT INTO pm_person (id, name) VALUES (v_id, 'first');
            INSERT INTO pm_person (id, name) VALUES (v_id || ${'$'}suffix, 'second');
            OPEN v_rows FOR SELECT name FROM pm_person WHERE id LIKE v_id || '%' ORDER BY name;
            DBMS_SQL.RETURN_RESULT(v_rows);
        END;
    """

    @Test
    fun `a block returns the rows it hands back`() {
        assertEquals(listOf("first", "second"), pm.query(spec(block, "id" to "x", "suffix" to "2").transform(String::class.java)))
    }

    @Test
    fun `a block that fails part way writes nothing, with no transaction around it`() {
        // An empty suffix makes the second insert repeat the first one's key.
        assertFailsWith<DrivineException> { pm.query(spec(block, "id" to "x", "suffix" to "")) }

        assertEquals(emptyList(), names())
        assertNull(pm.maybeGetOne(spec("SELECT name FROM pm_person WHERE id = 'x'")))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun schema() {
            OracleTestDatabase.drop("PROPERTY GRAPH pm_graph", "TABLE pm_mentions", "TABLE pm_person", "TABLE pm_memo")
            OracleTestDatabase.run(
                """
                CREATE TABLE pm_person (
                    id VARCHAR2(64) PRIMARY KEY, name VARCHAR2(100), age NUMBER(10), score BINARY_DOUBLE,
                    born TIMESTAMP WITH TIME ZONE, embedding VECTOR(3, FLOAT32)
                )
                """,
                "CREATE TABLE pm_memo (id VARCHAR2(64) PRIMARY KEY, text VARCHAR2(400))",
                """
                CREATE TABLE pm_mentions (
                    memo_id VARCHAR2(64) NOT NULL REFERENCES pm_memo (id), person_id VARCHAR2(64) NOT NULL REFERENCES pm_person (id),
                    PRIMARY KEY (memo_id, person_id)
                )
                """,
                """
                CREATE PROPERTY GRAPH pm_graph
                    VERTEX TABLES (
                        pm_memo KEY (id) LABEL Memo PROPERTIES (id, text),
                        pm_person KEY (id) LABEL Human PROPERTIES (id, name)
                    )
                    EDGE TABLES (
                        pm_mentions KEY (memo_id, person_id)
                            SOURCE KEY (memo_id) REFERENCES pm_memo (id)
                            DESTINATION KEY (person_id) REFERENCES pm_person (id)
                            LABEL MENTIONS NO PROPERTIES
                    )
                """,
            )
        }
    }
}
