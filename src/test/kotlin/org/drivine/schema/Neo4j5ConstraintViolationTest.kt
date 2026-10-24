package org.drivine.schema

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A uniqueness constraint over data that breaks it, on Neo4j 5 LTS, which reports the violation
 * differently from the `neo4j:latest` that [Neo4jSchemaManagementIntegrationTest] runs on: the refusal
 * carries GQL status `50N11` with only `50N42` beneath it, where 2026 names `22N79` and `22N80`.
 *
 * The image is `neo4j:5.26-community`, as `gradle/test-images.txt` pins it.
 */
@Testcontainers
class Neo4j5ConstraintViolationTest {

    companion object {
        private const val PASSWORD = "lts5violation"

        @Container
        @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:5.26-community"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo4j-5-violation", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = null,
                config = emptyMap(), cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    private fun execute(cypher: String) {
        manager.execute(QuerySpecification.withStatement(cypher))
    }

    @BeforeEach
    fun reset() {
        manager.constraints.list().mapNotNull { it.name }.forEach { execute("DROP CONSTRAINT `$it` IF EXISTS") }
        execute("MATCH (n) DETACH DELETE n")
    }

    @Test
    fun `a constraint on data that breaks it is a Violation with the conflicting nodes`() {
        execute("CREATE (:Person {email: 'dup@example.com', name: 'A'})")
        execute("CREATE (:Person {email: 'dup@example.com', name: 'B'})")

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "name"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val nodes = ((result as EnsureResult.Violation).conflictingSample.single()["nodes"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf("A", "B"), nodes.map { it["name"] }.toSet())
        assertNull(manager.constraints.find(UniquenessConstraintSpec("Person", "email")))
    }

    @Test
    fun `the server's refusal of that constraint is suspected as a violation`() {
        execute("CREATE (:Person {email: 'dup@example.com'}), (:Person {email: 'dup@example.com'})")

        val refused = assertThrows<Exception> {
            execute("CREATE CONSTRAINT Person_email_unique FOR (n:Person) REQUIRE n.email IS UNIQUE")
        }

        assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
    }

    @Test
    fun `a write the constraint refuses is a violation`() {
        assertTrue(manager.constraints.ensure(UniquenessConstraintSpec("Person", "email")) is EnsureResult.Created)
        execute("CREATE (:Person {email: 'one@example.com'})")

        val refused = assertThrows<Exception> { execute("CREATE (:Person {email: 'one@example.com'})") }

        assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
    }
}
