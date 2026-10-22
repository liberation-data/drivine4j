package org.drivine.postgres

import com.zaxxer.hikari.HikariDataSource
import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.test.PostgresTestContainer
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
import kotlin.test.assertTrue

/**
 * SQL through `Connection` and `PersistenceManager` on Postgres: parameters, the types that come back,
 * transactions, a statement that writes and returns rows, and a `@GraphView` hydrated from one `jsonb`
 * column.
 */
@PostgresTests
class PostgresConnectionIntegrationTest {

    private val pm = PostgresTestDatabase.pm

    private fun <T> connected(work: (Connection) -> T): T {
        val connection = PostgresTestDatabase.provider.connect()
        try {
            return work(connection)
        } finally {
            connection.release()
        }
    }

    private fun spec(sql: String, vararg parameters: Pair<String, Any?>) = QuerySpecification.withStatement(sql.trimIndent()).bind(mapOf(*parameters))

    private fun names(): List<String> = pm.query(spec("SELECT name FROM pm_person ORDER BY name").transform(String::class.java))

    @BeforeEach
    fun clean() = PostgresTestDatabase.run("DELETE FROM pm_mentions", "DELETE FROM pm_person", "DELETE FROM pm_memo")

    // ----- Parameters and what comes back -----

    @Test
    fun `a parameter is bound by name, as often as the statement names it`() {
        assertEquals(listOf<Any>(7L), pm.query(spec("SELECT \$a::int + \$b::int + \$a::int", "a" to 2, "b" to 3)))
    }

    @Test
    fun `a parameter the statement names and nothing binds is refused`() {
        val failure = assertFailsWith<DrivineException> { pm.query(spec("SELECT \$missing")) }
        assertTrue("\$missing" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a string is sent untyped, so the statement says what it is`() {
        assertEquals(listOf<Any>(42L), pm.query(spec("SELECT 40 + \$n", "n" to "2")))
        assertEquals(listOf<Any>(mapOf("a" to 1L)), pm.query(spec("SELECT CAST(\$doc AS jsonb)", "doc" to """{"a": 1}""")))
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
        // Postgres keeps microseconds.
        val born = Instant.now().truncatedTo(ChronoUnit.MICROS)
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
                SELECT id, name, age FROM jsonb_to_recordset(CAST(${'$'}people AS jsonb)) AS p (id text, name text, age int)
                """,
                "people" to listOf(mapOf("id" to "ada", "name" to "Ada", "age" to 36), mapOf("id" to "bob", "name" to "Bob", "age" to 41)),
            )
        )

        assertEquals(listOf("Ada", "Bob"), names())
    }

    @Test
    fun `an array column is a list`() {
        assertEquals(listOf<Any>(listOf("Person", "Author")), pm.query(spec("SELECT ARRAY['Person', 'Author']")))
    }

    @Test
    fun `skip and limit page a statement`() {
        ('a'..'e').forEach { pm.execute(spec("INSERT INTO pm_person (id, name) VALUES (\$id, \$id)", "id" to it.toString())) }

        assertEquals(listOf("b", "c"), pm.query(spec("SELECT name FROM pm_person ORDER BY name").skip(1).limit(2).transform(String::class.java)))
        assertEquals(listOf("a"), pm.query(spec("SELECT name FROM pm_person ORDER BY name").limit(1).transform(String::class.java)))
    }

    @Test
    fun `a vector is bound from a list of numbers and read back as one`() {
        pm.execute(spec("INSERT INTO pm_person (id, name, embedding) VALUES ('ada', 'Ada', CAST(\$v AS vector))", "v" to listOf(1.0f, 0.0f, 0.0f)))
        pm.execute(spec("INSERT INTO pm_person (id, name, embedding) VALUES ('bob', 'Bob', CAST(\$v AS vector))", "v" to listOf(0.0f, 1.0f, 0.0f)))

        assertEquals(listOf(1.0, 0.0, 0.0), pm.query(spec("SELECT embedding FROM pm_person WHERE id = 'ada'")).single())
        assertEquals(
            listOf("Bob", "Ada"),
            pm.query(
                spec("SELECT name FROM pm_person ORDER BY embedding <=> CAST(\$q AS vector)", "q" to listOf(0.1f, 0.9f, 0.0f))
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
    fun `connection properties build a provider for a Postgres database`() {
        val registry = DatabaseRegistry(DataSourceMap(mapOf("pg" to ConnectionProperties(
            type = DatabaseType.POSTGRES, host = PostgresTestContainer.getConnectionHost(), port = PostgresTestContainer.getConnectionPort(),
            userName = PostgresTestContainer.getConnectionUsername(), password = PostgresTestContainer.getConnectionPassword(),
            databaseName = PostgresTestContainer.DATABASE, defaultGraphPath = "props", poolMax = 3, connectionTimeout = 20_000,
        ))))
        val provider = assertNotNull(registry.connectionProvider("pg"))
        try {
            assertEquals("props", provider.graphName)
            val pool = assertIs<HikariDataSource>((provider as JdbcConnectionProvider).nativeDriver)
            assertEquals(3, pool.maximumPoolSize)
            assertEquals(20_000, pool.connectionTimeout)
            assertEquals("unspecified", pool.dataSourceProperties.getProperty("stringtype"))
            val connection = provider.connect()
            try {
                assertEquals(listOf<Any>("Ada"), connection.query(spec("SELECT \$name", "name" to "Ada")))
            } finally {
                connection.release()
            }
        } finally {
            provider.end()
        }
    }

    // ----- Documents -----

    @Test
    fun `a jsonb column is a document, with whole numbers as longs`() {
        val row = pm.query(spec("SELECT jsonb_build_object('name', 'Ada', 'age', 36, 'tags', jsonb_build_array('a', 'b'))")).single()

        assertEquals(mapOf("name" to "Ada", "age" to 36L, "tags" to listOf("a", "b")), row)
    }

    @Test
    fun `a view is hydrated from one jsonb column, its collection gathered with jsonb_agg`() {
        PostgresTestDatabase.run(
            "INSERT INTO pm_memo (id, text) VALUES ('m1', 'Ada met Bob')",
            "INSERT INTO pm_person (id, name) VALUES ('ada', 'Ada')",
            "INSERT INTO pm_person (id, name) VALUES ('bob', 'Bob')",
            "INSERT INTO pm_mentions (memo_id, person_id) VALUES ('m1', 'bob')",
            "INSERT INTO pm_mentions (memo_id, person_id) VALUES ('m1', 'ada')",
        )

        val view = pm.getOne(
            spec(
                """
                SELECT jsonb_build_object(
                    'memo', jsonb_build_object('id', root.id, 'text', root.text),
                    'people', COALESCE(people.list, '[]'::jsonb))
                FROM pm_memo root
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(jsonb_build_object('id', h.id, 'name', h.name) ORDER BY h.name) AS list
                    FROM pm_mentions m JOIN pm_person h ON h.id = m.person_id
                    WHERE m.memo_id = root.id
                ) people ON true
                WHERE root.id = ${'$'}id
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

    // ----- One statement that writes and returns rows -----

    private val writing = """
        WITH first AS (INSERT INTO pm_person (id, name) VALUES (${'$'}id, 'first') RETURNING name),
             second AS (INSERT INTO pm_person (id, name) VALUES (${'$'}id || ${'$'}suffix, 'second') RETURNING name)
        SELECT name FROM first UNION ALL SELECT name FROM second ORDER BY name
    """

    @Test
    fun `a statement that writes returns the rows it is asked for`() {
        assertEquals(listOf("first", "second"), pm.query(spec(writing, "id" to "x", "suffix" to "2").transform(String::class.java)))
    }

    @Test
    fun `a statement that fails part way writes nothing, with no transaction around it`() {
        // An empty suffix makes the second insert repeat the first one's key.
        assertFailsWith<DrivineException> { pm.query(spec(writing, "id" to "x", "suffix" to "")) }

        assertEquals(emptyList(), names())
    }

    @Test
    fun `a function body in dollar quotes reaches the database as written`() {
        PostgresTestDatabase.run(
            """
            CREATE OR REPLACE FUNCTION pm_greeting(who text) RETURNS text AS ${'$'}fn${'$'}
            BEGIN
                RETURN 'Hello, ' || who || ' (' || '${'$'}notAParameter' || ')';
            END;
            ${'$'}fn${'$'} LANGUAGE plpgsql
            """,
        )

        assertEquals(listOf<Any>("Hello, Ada (\$notAParameter)"), pm.query(spec("SELECT pm_greeting(\$who)", "who" to "Ada")))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun schema() {
            PostgresTestDatabase.run(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "DROP TABLE IF EXISTS pm_mentions, pm_person, pm_memo",
                """
                CREATE TABLE pm_person (
                    id text PRIMARY KEY, name text, age int, score double precision,
                    born timestamptz, embedding vector(3)
                )
                """,
                "CREATE TABLE pm_memo (id text PRIMARY KEY, text text)",
                """
                CREATE TABLE pm_mentions (
                    memo_id text NOT NULL REFERENCES pm_memo (id), person_id text NOT NULL REFERENCES pm_person (id),
                    PRIMARY KEY (memo_id, person_id)
                )
                """,
            )
        }
    }
}
