package org.drivine.manager

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.DrivineException
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphBackends
import org.drivine.backend.LowerCopyLookups
import org.drivine.backend.oracle.OracleBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.LowerCopies
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.containsIgnoreCase
import org.drivine.query.dsl.eqIgnoreCase
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.EnsureResult
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.summary.Plan
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.caseinsensitive.CiAccount
import sample.caseinsensitive.CiAccountQueryDsl
import sample.caseinsensitive.CiAccountView
import sample.caseinsensitive.CiAccountViewQueryDsl
import sample.caseinsensitive.CiEmailQueryDsl
import sample.caseinsensitive.CiLate
import sample.caseinsensitive.CiMember
import sample.caseinsensitive.CiPlanned
import sample.caseinsensitive.CiRedone

/**
 * `@CaseInsensitive`: a save keeps a lowercased copy of the property, which loads do not see; an
 * index that ignores case is made over the copy, and ensuring it fills the copy in on the nodes saved
 * before; once it has, `eqIgnoreCase` and `containsIgnoreCase` compare with the copy, which the index
 * serves. Verified on Neo4j, FalkorDB, Memgraph, Oracle, Postgres and SQLite.
 */
abstract class CaseInsensitiveContract {

    abstract val pm: PersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** The copy of the email the node of [label] with [id] keeps; null when it keeps none. */
    private fun copy(label: String, id: String): String? =
        pm.query(QuerySpecification.withStatement("MATCH (n:$label {id: '$id'}) RETURN n.`${LowerCopies.of("email")}`").transform(String::class.java)).singleOrNull()

    private fun account(id: String): CiAccount = assertNotNull(stateless.load(id, CiAccount::class.java))

    /** The ids of the [CiAccount]s whose email equals [email], whatever its case. */
    private fun accountsNamed(email: String): Set<String> =
        stateless.loadAll(CiAccount::class.java, CiAccountQueryDsl.INSTANCE) { where { query.email eqIgnoreCase email } }.map { it.id }.toSet()

    /** The statement a load of [graphClass] whose email equals [email], whatever its case, is written as. */
    internal fun lookup(graphClass: Class<*>, email: String, contains: Boolean = false): GraphStatement {
        val spec = GraphQuerySpec(CiEmailQueryDsl.INSTANCE).apply {
            where { if (contains) query.email containsIgnoreCase email else query.email eqIgnoreCase email }
        }
        return backend().loadAll(graphClass, spec).statement
    }

    /** The backend the object managers write statements with. */
    internal open fun backend(): GraphBackend = GraphBackends.of(pm, Neo4jObjectMapper.instance)

    /** Whether [statement] reads the lowercased copy of the email. */
    private fun readsCopy(statement: GraphStatement): Boolean = LowerCopies.of("email") in statement.statement

    /** What the store records of the copies filled in. */
    private fun recorded(): Set<Pair<String, String>> = assertNotNull(pm.indexes.lowerCopies).stored()

    /** Forgets what the store records of copies filled in, as on a store where none has been. */
    open fun forgetCopies() = Unit

    @BeforeEach
    fun clean() {
        run("MATCH (n) DETACH DELETE n")
        forgetCopies()
    }

    // ----- The copy -----

    @Test
    fun `a save writes the lowercased copy, a load and a flat bag do not see it, and it follows the property`() {
        stateless.save(CiAccount("a1", "Ann@Example.COM", "Ann", extra = mapOf("tier" to "gold")))
        assertEquals("ann@example.com", copy("CiAccount", "a1"))

        val loaded = account("a1")
        assertEquals("Ann@Example.COM", loaded.email)
        assertEquals(mapOf<String, Any?>("tier" to "gold"), loaded.extra)

        stateless.save(loaded.copy(email = "ANNE@Example.com"))
        assertEquals("anne@example.com", copy("CiAccount", "a1"))

        // A save of another field leaves the copy as the property is.
        stateless.save(account("a1").copy(name = "Anne"))
        assertEquals("anne@example.com", copy("CiAccount", "a1"))

        stateless.save(account("a1").copy(email = null), nullPolicy = NullPolicy.CLEAR)
        assertNull(copy("CiAccount", "a1"))
        assertEquals(mapOf<String, Any?>("tier" to "gold"), account("a1").extra)

        assertNotNull(stateless.create(CiAccount("a2", "Bea@X.com")))
        assertEquals("bea@x.com", copy("CiAccount", "a2"))
    }

    @Test
    fun `saveAll, its named fields, patch and patchAll each keep the copy in step`() {
        stateless.saveAll(listOf(CiAccount("a1", "One@X.com"), CiAccount("a2", "Two@X.com")))
        assertEquals("one@x.com", copy("CiAccount", "a1"))
        assertEquals("two@x.com", copy("CiAccount", "a2"))

        stateless.saveAll(listOf(CiAccount("a1", "Uno@X.com", "not written")), only = setOf(CiAccount::email))
        assertEquals("uno@x.com", copy("CiAccount", "a1"))
        assertNull(account("a1").name)

        stateless.saveAll(listOf(CiAccount("a2", "Dos@X.com", "Two")), except = setOf(CiAccount::name))
        assertEquals("dos@x.com", copy("CiAccount", "a2"))

        stateless.patch("a1", CiAccount::class.java, CiAccountQueryDsl.INSTANCE) { fields { query.email setTo "Eins@X.com" } }
        assertEquals("eins@x.com", copy("CiAccount", "a1"))

        stateless.patchAll(listOf(CiAccount("a1", "Un@X.com"), CiAccount("a2", "Deux@X.com")), only = setOf(CiAccount::email))
        assertEquals("un@x.com", copy("CiAccount", "a1"))
        assertEquals("deux@x.com", copy("CiAccount", "a2"))

        stateless.patchAll(listOf(CiAccount("a2", null)), nullPolicy = NullPolicy.CLEAR, only = setOf(CiAccount::email))
        assertNull(copy("CiAccount", "a2"))
    }

    @Test
    fun `the copy is no part of whether a save changed the node`() {
        stateless.save(CiAccount("a1", "Ann@X.com", "Ann"))
        val loaded = account("a1")
        // Written by Cypher of the test's own, which keeps no copy.
        run("MATCH (n:CiAccount {id: 'a1'}) REMOVE n.`${LowerCopies.of("email")}`")

        val saved = stateless.save(loaded)
        assertEquals(loaded.stamp, saved.stamp)
        assertEquals("ann@x.com", copy("CiAccount", "a1"))
        // Saved again as it is, beside the copy it now has.
        assertEquals(saved.stamp, stateless.save(saved).stamp)
    }

    @Test
    fun `update reads past the copy, on Neo4j where it compares each field as loaded`() {
        stateless.save(CiAccount("a1", "Ann@X.com", "Ann", extra = mapOf("tier" to "gold")))

        val updated = assertNotNull(stateless.update("a1", CiAccount::class.java) { it.copy(name = "Anne") })
        assertEquals("Anne", updated.name)
        assertEquals("Anne", account("a1").name)
        assertEquals("ann@x.com", copy("CiAccount", "a1"))
    }

    // ----- The lookup -----

    @Test
    fun `before the copies are filled in a lookup lowercases the property, and after it compares with the copy`() {
        // Nodes saved before there were copies.
        run("CREATE (:CiLate {id: 'l1', email: 'Ann@X.com'}), (:CiLate {id: 'l2', email: 'bob@x.com'})")
        assertNull(copy("CiLate", "l1"))
        fun found(email: String, contains: Boolean = false): Set<String> = stateless.loadAll(CiLate::class.java, CiEmailQueryDsl.INSTANCE) {
            where { if (contains) query.email containsIgnoreCase email else query.email eqIgnoreCase email }
        }.map { it.id }.toSet()

        assertFalse(readsCopy(lookup(CiLate::class.java, "ANN@x.com")))
        assertEquals(setOf("l1"), found("ANN@x.com"))
        assertEquals(setOf("l1"), found("NN@X", contains = true))

        assertTrue(pm.indexes.ensure(RangeIndexSpec("CiLate", "email", ignoreCase = true)) is EnsureResult.Created)
        assertEquals("ann@x.com", copy("CiLate", "l1"))
        assertEquals("bob@x.com", copy("CiLate", "l2"))
        assertContains(recorded(), "CiLate" to "email")

        assertTrue(readsCopy(lookup(CiLate::class.java, "ANN@x.com")))
        assertTrue(readsCopy(lookup(CiLate::class.java, "NN@X", contains = true)))
        assertEquals(setOf("l1"), found("ANN@x.com"))
        assertEquals(setOf("l1"), found("NN@X", contains = true))
        assertEquals(setOf("l1", "l2"), found("@x.COM", contains = true))
        // An empty value is looked up on the property itself: Oracle keeps no copy of an empty text.
        assertFalse(readsCopy(lookup(CiLate::class.java, "")))
        // Ensured again, the index is there and the copies are recorded: nothing is filled in.
        assertTrue(pm.indexes.ensure(RangeIndexSpec("CiLate", "email", ignoreCase = true)) is EnsureResult.AlreadyMatching)
    }

    @Test
    fun `recreate fills the copies in again, where a write that bypassed Drivine left one out of step`() {
        val spec = RangeIndexSpec("CiRedone", "email", ignoreCase = true)
        stateless.save(CiRedone("l1", "Ann@X.com"))
        pm.indexes.ensure(spec)
        // Written by Cypher of the test's own, which keeps no copy.
        run("MATCH (n:CiRedone {id: 'l1'}) SET n.email = 'Anne@X.com'")
        run("CREATE (:CiRedone {id: 'l2', email: 'Bob@X.com'})")

        // The copies are recorded as filled in, so ensure leaves them as they are.
        assertTrue(pm.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals("ann@x.com", copy("CiRedone", "l1"))
        assertNull(copy("CiRedone", "l2"))

        pm.indexes.recreate(spec)
        assertEquals("anne@x.com", copy("CiRedone", "l1"))
        assertEquals("bob@x.com", copy("CiRedone", "l2"))
    }

    @Test
    fun `a view's save keeps the copies of its root and its targets, and a lookup on its root compares with the copy`() {
        pm.indexes.ensure(RangeIndexSpec("CiAccount", "email", ignoreCase = true))
        stateless.save(CiAccountView(CiAccount("a1", "Ann@X.com"), friends = listOf(CiAccount("f1", "Bob@X.com"))))
        assertEquals("ann@x.com", copy("CiAccount", "a1"))
        assertEquals("bob@x.com", copy("CiAccount", "f1"))

        val spec = GraphQuerySpec(CiAccountViewQueryDsl.INSTANCE).apply {
            where {
                query.account.email eqIgnoreCase "ANN@x.com"
                query.friends.email eqIgnoreCase "bob@X.COM"
            }
        }
        assertTrue(readsCopy(backend().loadAll(CiAccountView::class.java, spec).statement))
        val found = stateless.loadAll(CiAccountView::class.java, CiAccountViewQueryDsl.INSTANCE) {
            where {
                query.account.email eqIgnoreCase "ANN@x.com"
                query.friends.email eqIgnoreCase "bob@X.COM"
            }
        }
        assertEquals(listOf("a1"), found.map { it.account.id })
    }

    @Test
    fun `count, deleteAll and a patch's predicate compare with the copy too`() {
        pm.indexes.ensure(RangeIndexSpec("CiAccount", "email", ignoreCase = true))
        stateless.saveAll(listOf(CiAccount("a1", "Ann@X.com"), CiAccount("a2", "Bob@X.com")))

        assertEquals(setOf("a1"), accountsNamed("aNN@x.COM"))
        assertEquals(1L, stateless.count(CiAccount::class.java, CiAccountQueryDsl.INSTANCE) { where { query.email eqIgnoreCase "BOB@x.com" } })
        val grouped = stateless.groups.load(CiAccount::class.java, CiAccountQueryDsl.INSTANCE) {
            where { query.email eqIgnoreCase "ann@X.COM" }
            val email = keyOrNull(query.email)
            rows { it[email] }
        }
        assertEquals(listOf<String?>("Ann@X.com"), grouped)

        val patched = stateless.patch("a1", CiAccount::class.java, CiAccountQueryDsl.INSTANCE) {
            fields { query.name setTo "Ann" }
            where { query.email eqIgnoreCase "ANN@X.COM" }
        }
        assertTrue(patched.applied)
        assertEquals("Ann", account("a1").name)

        assertEquals(1, stateless.deleteAll(CiAccount::class.java, CiAccountQueryDsl.INSTANCE) { where { query.email eqIgnoreCase "bob@X.COM" } })
        assertEquals(setOf("a1"), stateless.loadAll(CiAccount::class.java).map { it.id }.toSet())
    }

    @Test
    fun `an index over the copy serves the lookup`() {
        seedPlanned(PLANNED)
        assertTrue(pm.indexes.ensure(RangeIndexSpec("CiPlanned", "email", ignoreCase = true)) is EnsureResult.Created)
        prepareForPlan()

        val statement = lookup(CiPlanned::class.java, "Planned-7@X.com")
        assertTrue(readsCopy(statement), statement.statement)
        assertIndexServes(statement)
        assertEquals(listOf("p7"), stateless.loadAll(CiPlanned::class.java, CiEmailQueryDsl.INSTANCE) { where { query.email eqIgnoreCase "PLANNED-7@x.com" } }.map { it.id })
    }

    /** Saves [count] [CiPlanned]s. */
    open fun seedPlanned(count: Int) {
        stateless.saveAll((0 until count).map { CiPlanned("p$it", "Planned-$it@X.com") })
    }

    /** Makes ready what the store plans a statement with, once the nodes and the index are there. */
    open fun prepareForPlan() = Unit

    /** Asserts the store reads [statement] through the index over the copy. */
    internal abstract fun assertIndexServes(statement: GraphStatement)

    // ----- Uniqueness -----

    @Test
    open fun `a uniqueness constraint that ignores case refuses an email beside another that differs only in case`() {
        val spec = UniquenessConstraintSpec("CiMember", "email", ignoreCase = true)
        stateless.saveAll(listOf(CiMember("m1", "a@x.com"), CiMember("m2", "A@x.com")))
        val violation = pm.constraints.ensure(spec)
        assertTrue(violation is EnsureResult.Violation, "$violation")

        stateless.delete("m2", CiMember::class.java)
        assertTrue(pm.constraints.ensure(spec) is EnsureResult.Created)
        assertNotNull(pm.constraints.find(spec))

        assertFailsWith<Exception> { stateless.save(CiMember("m3", "A@X.COM")) }
        assertEquals(setOf("m1"), stateless.loadAll(CiMember::class.java).map { it.id }.toSet())
        // One that differs in more than case is another email.
        stateless.save(CiMember("m4", "b@x.com"))

        // Written by Cypher of the test's own, which keeps no copy: recreate fills it in.
        run("CREATE (:CiMember {id: 'm5', email: 'C@x.com'})")
        pm.constraints.recreate(spec)
        assertEquals("c@x.com", copy("CiMember", "m5"))
    }

    companion object {
        const val PLANNED = 2000

        /** The ids of the operators of [plan] and every plan beneath it. */
        fun operators(plan: Plan): List<String> = listOf(plan.operatorType()) + plan.children().flatMap(::operators)
    }
}

class CaseInsensitiveNeo4jTest : CaseInsensitiveContract() {
    companion object {
        private const val PASSWORD = SharedNeo4j.PASSWORD

        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-ci", type = DatabaseType.NEO4J,
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

    /** The plan Neo4j makes for [statement], as the operators it is made of. */
    private fun plan(statement: GraphStatement): List<String> =
        GraphDatabase.driver(container.boltUrl, AuthTokens.basic("neo4j", PASSWORD)).use { driver ->
            driver.session().use { session -> operators(session.run("EXPLAIN ${statement.statement}", statement.bindings).consume().plan()) }
        }

    /** An index is planned with once it is online: Neo4j makes it in the background. */
    override fun prepareForPlan() = pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(60)"))

    override fun assertIndexServes(statement: GraphStatement) {
        val plan = plan(statement)
        assertTrue(plan.any { it.startsWith("NodeIndexSeek") }, "$plan")
    }

    @Test
    fun `a TEXT index over the copy serves containsIgnoreCase`() {
        seedPlanned(PLANNED)
        // The range index alone, which a test before may have made, is read whole for a CONTAINS.
        pm.indexes.drop(RangeIndexSpec("CiPlanned", "email", ignoreCase = true))
        assertTrue(pm.indexes.ensure(TextIndexSpec("CiPlanned", "email", ignoreCase = true)) is EnsureResult.Created)
        prepareForPlan()

        val plan = plan(lookup(CiPlanned::class.java, "NED-7@", contains = true))
        assertTrue(plan.any { it.startsWith("NodeIndexContainsScan") }, "$plan")
    }
}

class CaseInsensitiveFalkorDbTest : CaseInsensitiveContract() {
    companion object {
        private const val GRAPH = "caseinsensitive"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-ci", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager

    /** `GRAPH.CONSTRAINT` takes no property whose name holds a dot, as the copy's does (FalkorDB/FalkorDB#551). */
    @Test
    override fun `a uniqueness constraint that ignores case refuses an email beside another that differs only in case`() {
        val spec = UniquenessConstraintSpec("CiMember", "email", ignoreCase = true)
        val refused = assertFailsWith<DrivineException> { pm.constraints.ensure(spec) }
        assertContains(refused.message!!, "FalkorDB/FalkorDB/issues/551")
        // recreate is refused too, before anything is dropped, filled in or made.
        val again = assertFailsWith<DrivineException> { pm.constraints.recreate(spec) }
        assertContains(again.message!!, "FalkorDB/FalkorDB/issues/551")
        assertTrue(pm.indexes.list().none { it.label == "CiMember" })
        assertTrue(assertNotNull(pm.indexes.lowerCopies).stored().isEmpty())
    }

    /** FalkorDB's plan for [statement], its values given in the `CYPHER` prefix that binds them. */
    override fun assertIndexServes(statement: GraphStatement) {
        val values = statement.bindings.entries.joinToString(" ") { (name, value) -> "$name='${value.toString().replace("'", "\\'")}'" }
        val plan = container.execInContainer("redis-cli", "GRAPH.EXPLAIN", GRAPH, "CYPHER $values ${statement.statement}").stdout
        assertContains(plan, "Node By Index Scan")
    }
}

class CaseInsensitiveMemgraphTest : CaseInsensitiveContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-ci", type = DatabaseType.MEMGRAPH,
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

    override fun assertIndexServes(statement: GraphStatement) {
        val plan = pm.query(QuerySpecification.withStatement("EXPLAIN ${statement.statement}").bind(statement.bindings).transform(String::class.java))
            .joinToString("\n")
        assertContains(plan, "ScanAllByLabelPropert")
    }
}

@OracleTests
class CaseInsensitiveOracleTest : CaseInsensitiveContract() {
    private val graph = OracleGraph("contracts")
    private val inner = OracleTestDatabase.graphManager("contracts")
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(inner)

    override fun forgetCopies() {
        OracleTestDatabase.run("DELETE FROM ${graph.catalog} WHERE kind = 'LOWER_COPY'")
        listOf("CiLate", "CiAccount", "CiPlanned", "CiRedone").forEach { pm.indexes.drop(RangeIndexSpec(it, "email", ignoreCase = true)) }
        pm.constraints.drop(UniquenessConstraintSpec("CiMember", "email", ignoreCase = true))
    }

    /** Written by Oracle itself: a few thousand nodes are slow to save one block at a time. */
    override fun seedPlanned(count: Int) {
        OracleTestDatabase.run(
            """
            INSERT INTO ${graph.nodes} (labels, props)
            SELECT JSON('["CiPlanned"]'), JSON_OBJECT('id' VALUE 'p' || (level - 1), 'email' VALUE 'Planned-' || (level - 1) || '@X.com' RETURNING JSON)
            FROM dual CONNECT BY level <= $count
            """,
        )
    }

    override fun prepareForPlan() = OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${graph.nodes.uppercase()}'); END;")

    /** The labelled indexes read on the caller's thread, so a statement is written with the index the test has just made. */
    override fun backend(): GraphBackend =
        LowerCopyLookups(OracleBackend(inner, Neo4jObjectMapper.instance, catalogReads = Runnable::run)) { inner.indexes.lowerCopies }

    /** The plan Oracle makes for [statement], its values written in as literals, explained and read on one session. */
    override fun assertIndexServes(statement: GraphStatement) {
        val sql = statement.bindings.entries.sortedByDescending { it.key.length }
            .fold(statement.statement) { text, (name, value) -> text.replace("\$$name", OracleSql.literal(value.toString())) }
        val block = """
            DECLARE
                v_plan SYS_REFCURSOR;
            BEGIN
                EXECUTE IMMEDIATE ${OracleSql.text("EXPLAIN PLAN FOR $sql")};
                OPEN v_plan FOR SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC'));
                DBMS_SQL.RETURN_RESULT(v_plan);
            END;
        """.trimIndent()
        val plan = inner.query(QuerySpecification.withStatement(block).transform(String::class.java)).joinToString("\n")
        assertContains(plan, graph.objectName("lx", listOf("CiPlanned", LowerCopies.of("email"))).uppercase())
    }
}

@PostgresTests
class CaseInsensitivePostgresTest : CaseInsensitiveContract() {
    override val pm: PersistenceManager = PostgresTestDatabase.fixtures(PostgresTestDatabase.graphManager("contracts"))

    override fun forgetCopies() {
        PostgresTestDatabase.run("DELETE FROM contracts_schema WHERE kind = 'LOWER_COPY'")
        listOf("CiLate", "CiAccount", "CiPlanned", "CiRedone").forEach { pm.indexes.drop(RangeIndexSpec(it, "email", ignoreCase = true)) }
        pm.constraints.drop(UniquenessConstraintSpec("CiMember", "email", ignoreCase = true))
    }

    /** The plan Postgres makes for [statement], with a scan of the whole table ruled out: it names an index only where one answers. */
    override fun assertIndexServes(statement: GraphStatement) {
        val connection = PostgresTestDatabase.provider.connect()
        val plan = try {
            connection.startTransaction()
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_seqscan = off"))
            connection.query(QuerySpecification.withStatement("EXPLAIN ${statement.statement}").bind(statement.bindings)).joinToString("\n")
        } finally {
            connection.release()
        }
        assertFalse("Seq Scan" in plan, plan)
        assertContains(plan, "Index")
    }
}

class CaseInsensitiveSqliteTest : CaseInsensitiveContract() {
    private val graph = SqliteGraph("contracts")
    override val pm: PersistenceManager = SqliteTestDatabase.fixtures(SqliteTestDatabase.graphManager("contracts"))

    override fun forgetCopies() {
        SqliteTestDatabase.run("DELETE FROM ${graph.catalog} WHERE kind = 'LOWER_COPY'")
        listOf("CiLate", "CiAccount", "CiPlanned", "CiRedone").forEach { pm.indexes.drop(RangeIndexSpec(it, "email", ignoreCase = true)) }
        pm.constraints.drop(UniquenessConstraintSpec("CiMember", "email", ignoreCase = true))
    }

    /** The plan SQLite makes for [statement]: it names the partial index over the copy where that answers. */
    override fun assertIndexServes(statement: GraphStatement) {
        val plan = pm.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN ${statement.statement}").bind(statement.bindings)).joinToString("\n")
        assertContains(plan, "USING INDEX ${graph.objectName("ix", listOf("CiPlanned", LowerCopies.of("email")))}")
    }
}
