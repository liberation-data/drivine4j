package org.drivine.manager

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Finding
import sample.stateless.Human
import sample.stateless.Memo

/**
 * `create`: a save that makes the node only if it is not there, in one statement, and otherwise
 * writes nothing and says so; verified on Neo4j, FalkorDB and Memgraph, and on Oracle.
 */
abstract class StatelessCreateContract {

    abstract val pm: PersistenceManager

    abstract val connections: ConnectionProvider

    private fun manager() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val stateless: StatelessGraphObjectManager
        get() = manager()

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun property(id: String, key: String): String? = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN coalesce(toString(n[\$key]), '')")
            .bind(mapOf("id" to id, "key" to key)).transform(String::class.java)
    ).firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun count(label: String): Long = pm.query(
        QuerySpecification.withStatement("MATCH (n:$label) RETURN count(n)").transform(Long::class.java)
    ).single()

    /** Makes the finding, or adds its sources to the one there: the upsert `create` is for. */
    private fun record(finding: Finding, over: StatelessGraphObjectManager = stateless): Finding =
        assertNotNull(over.create(finding) ?: over.update<Finding>(finding.id, attempts = 50) { it.merge(finding) })

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    @Test
    fun `create makes a node that is not there, and hands back the whole stamp it left`() {
        val made = assertNotNull(stateless.create(Claim("c1", "Ada founded Acme")))

        val stamp = assertNotNull(made.stamp)
        assertTrue(Stamps.isTwoTokens(stamp), "a node the save made is vouched for whole: $stamp")
        assertEquals(stamp, property("c1", Stamps.PROPERTY))
        assertEquals(Claim("c1", "Ada founded Acme", stamp = stamp), stateless.load<Claim>("c1"))
    }

    @Test
    fun `create of a node that is there writes nothing and returns null`() {
        val first = stateless.save(Claim("c1", "first", note = "kept"))

        assertNull(stateless.create(Claim("c1", "second", note = "lost")))

        assertEquals(first, stateless.load<Claim>("c1"), "neither a field nor the stamp was written")
        assertEquals(1, count("Claim"))
    }

    @Test
    fun `create of a type with no stamp field makes the node once`() {
        assertEquals(Memo("m1", "Call Ada"), stateless.create(Memo("m1", "Call Ada")))
        assertNull(stateless.create(Memo("m1", "Call Bob")))

        assertEquals("Call Ada", property("m1", "text"))
        assertNotNull(property("m1", Stamps.PROPERTY), "the node it made is stamped, as a save stamps it")
        assertTrue(property("m1", "__drivine.made") == null, "the mark that tells a made node is taken off again")
    }

    @Test
    fun `create of a view makes the root, its related nodes and the relationships to them`() {
        val made = assertNotNull(stateless.create(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada")))))

        assertNotNull(made.claim.stamp)
        assertEquals(listOf(Human("h1", "Ada")), stateless.load<ClaimView>("c1")?.people)
    }

    @Test
    fun `create of a view whose root is there writes nothing, not even its related nodes`() {
        stateless.save(Claim("c1", "one"))

        assertNull(stateless.create(ClaimView(Claim("c1", "two"), people = listOf(Human("h1", "Ada")))))

        assertEquals("one", property("c1", "text"))
        assertEquals(0, count("Human"), "the related node was not made")
        assertEquals(emptyList(), stateless.load<ClaimView>("c1")?.people)
    }

    @Test
    fun `a create that fails for another reason than the node being there throws, and makes nothing`() {
        // A related node the store refuses: the root is not there, so the failure is not an answer.
        // The constraint is dropped after: on Oracle the graph is shared with other test classes.
        val uniqueName = UniquenessConstraintSpec("Human", "name")
        pm.constraints.ensure(uniqueName)
        try {
            stateless.save(Human("h0", "Ada"))

            assertFailsWith<DrivineException> { stateless.create(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada")))) }

            assertEquals(0, count("Claim"), "the root the failed statement made went with it")
            assertNotNull(stateless.create(Claim("c1", "one")))
        } finally {
            pm.constraints.drop(uniqueName)
        }
    }

    @Test
    fun `create refuses an object that carries a stamp, before anything is written`() {
        val loaded = stateless.save(Claim("c1", "one"))
        run("MATCH (n:Claim) DETACH DELETE n")

        val refused = assertFailsWith<IllegalArgumentException> { stateless.create(loaded) }

        assertContains(refused.message.orEmpty(), "carries a stamp")
        assertEquals(0, count("Claim"))
    }

    @Test
    fun `create and then update keep both of two first writes made one after the other`() {
        record(Finding("f1", listOf("first")))
        record(Finding("f1", listOf("second")))

        assertEquals(setOf("first", "second"), stateless.load<Finding>("f1")?.sources?.toSet())
    }

    @Test
    fun `concurrent first writes of one id all end up in the one node`() {
        // Two writers of a node that is not there yet each find it absent. Only the uniqueness of the
        // id stops both making it, on an engine that runs writes side by side.
        pm.constraints.ensure(UniquenessConstraintSpec("Finding", "id"))
        repeat(ROUNDS) { round ->
            val id = "f$round"
            val failures = mutableListOf<Throwable>()
            val go = CountDownLatch(1)
            val writers = (1..WRITERS).map { writer ->
                thread {
                    try {
                        val own = manager()
                        go.await()
                        record(Finding(id, listOf("w$writer")), own)
                    } catch (failure: Throwable) {
                        synchronized(failures) { failures += failure }
                    }
                }
            }
            go.countDown()
            writers.forEach { it.join(120_000) }

            assertEquals(emptyList(), failures, "round $round")
            assertEquals((1..WRITERS).map { "w$it" }.toSet(), stateless.load<Finding>(id)?.sources?.toSet(), "round $round")
        }
        assertEquals(ROUNDS.toLong(), count("Finding"), "one node for each id")
    }

    /** [pm] bound to [connection], as a manager bound to a transaction runs a statement and reports its failure. */
    private fun boundTo(connection: Connection) = object : PersistenceManager by pm {
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = try {
            connection.query(spec)
        } catch (failure: Exception) {
            throw DrivineException.withRootCause(failure, spec)
        }
    }

    /**
     * Runs [work] in a transaction of its own that has read the graph before [meanwhile] runs outside
     * it, and commits. What it returns, or how it failed; a transaction that failed is rolled back.
     */
    private fun <T> inTransaction(meanwhile: () -> Unit = {}, work: (StatelessGraphObjectManager) -> T): Result<T> {
        val connection = connections.connect()
        connection.startTransaction()
        return try {
            runCatching {
                // The transaction's first read. On Memgraph this is the snapshot it sees from here on.
                connection.query(QuerySpecification.withStatement("MATCH (n) RETURN count(n)").transform(Long::class.java))
                meanwhile()
                work(StatelessGraphObjectManager(boundTo(connection), Neo4jObjectMapper.instance, SubtypeRegistry()))
                    .also { connection.commitTransaction() }
            }.onFailure { runCatching { connection.rollbackTransaction() } }
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a create in a transaction that began before another writer made the node makes no second node`() {
        pm.constraints.ensure(UniquenessConstraintSpec("Finding", "id"))

        var made: Finding? = null
        val inside = inTransaction(meanwhile = { assertNotNull(stateless.create(Finding("f1", listOf("outside")))) }) {
            it.create(Finding("f1", listOf("inside"))).also { finding -> made = finding }
        }

        if (pm.type == DatabaseType.MEMGRAPH) {
            // The transaction's snapshot has no node, so it makes one, and the constraint refuses it
            // when the transaction commits. Run again, the transaction finds the other writer's node.
            assertNotNull(made, "the create made the node the transaction's snapshot lacks")
            assertTrue(inside.isFailure, "the commit is refused: $inside")
            assertNull(inTransaction { it.create(Finding("f1", listOf("inside"))) }.getOrThrow())
        } else {
            assertNull(inside.getOrThrow(), "the create finds the other writer's node")
        }
        assertEquals(1, count("Finding"))
        assertEquals(listOf("outside"), stateless.load<Finding>("f1")?.sources)
    }

    private companion object {
        const val ROUNDS = 10
        const val WRITERS = 4
    }
}

@Testcontainers
class StatelessCreateNeo4jTest : StatelessCreateContract() {
    companion object {
        private const val PASSWORD = "statelesscreate"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-stateless-create", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val connections: ConnectionProvider get() = provider
}

@Testcontainers
class StatelessCreateFalkorDbTest : StatelessCreateContract() {
    companion object {
        private const val GRAPH = "statelesscreate"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-stateless-create", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val connections: ConnectionProvider get() = provider
}

@Testcontainers
class StatelessCreateMemgraphTest : StatelessCreateContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-stateless-create", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val connections: ConnectionProvider get() = provider
}

@OracleTests
class StatelessCreateOracleTest : StatelessCreateContract() {
    private val store = OracleTestDatabase.graphManager("contracts")
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(store)
    override val connections: ConnectionProvider = OracleTestDatabase.connections(store)
}
