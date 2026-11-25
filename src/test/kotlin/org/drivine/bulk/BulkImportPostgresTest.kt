package org.drivine.bulk

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import sample.caseinsensitive.CiAccount
import sample.graphproperty.ChunkNode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A bulk import into Postgres (liberation-data/drivine4j#684): its rows are those a save writes, its relationships are
 * found by their ends' keys, and the edge table's foreign keys and indexes are made again as the layout makes them. A
 * refused import, or a failed one, leaves the graph as it was.
 */
@PostgresTests
class BulkImportPostgresTest {

    private val graph = "bulk_in"
    private val saved = "bulk_saved"
    private val pm = PostgresTestDatabase.graphManager(graph)
    private val savedPm = PostgresTestDatabase.graphManager(saved)
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val savedManager = StatelessGraphObjectManager(savedPm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val provider = JdbcConnectionProvider(graph, DatabaseType.POSTGRES, PostgresTestDatabase.provider.nativeDriver, graphName = graph)

    @BeforeEach
    fun empty() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.clear(savedPm)
    }

    /** The first column of each row [sql] gives, each as its text. */
    private fun column(sql: String): List<String?> = PostgresTestDatabase.provider.nativeDriver.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } }
    }

    private fun count(sql: String): Long = column(sql).single()!!.toLong()

    private fun nodes(g: String) = count("SELECT count(*) FROM ${g}_node WHERE NOT labels @> '[\"DrivineStore\"]' AND NOT labels @> '[\"_DrivineSchema\"]'")

    private fun edges(g: String) = count("SELECT count(*) FROM ${g}_edge")

    /** The edge table's foreign keys and indexes, each its definition with the graph's name taken out. */
    private fun edgeObjects(g: String): List<String?> =
        (
            column("SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = '${g}_edge'::regclass ORDER BY conname") +
                column("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = '${g}_edge' ORDER BY indexname")
            ).map { it?.replace("public.", "")?.replace(g, "<g>") }

    /** A text with what CSV quotes, a backslash, and a character past the Basic Multilingual Plane, kept as it is. */
    private val note = "say \"hi\", \\ then\nnext \uD83D\uDE00"

    private val account = mapOf<String, Any?>(
        "id" to "ada", "email" to "Ada@Example.org", "name" to "Ada", "nothing" to null,
        "born" to LocalDate.of(1815, 12, 10), "scores" to listOf(1, 2.5), "\$code" to "A1", "note" to note,
    )

    @Test
    fun `an imported node's row is the row a save writes`() {
        savedManager.save(CiAccount("ada", "Ada@Example.org", "Ada", mapOf("born" to LocalDate.of(1815, 12, 10), "scores" to listOf(1, 2.5), "\$code" to "A1", "note" to note)))
        val report = BulkImport(provider).run {
            nodes("CiAccount", key = "id", rows = sequenceOf(account), lowercased = listOf("email"))
        }
        assertEquals(mapOf("CiAccount" to 1L), report.nodes)
        val row = { g: String -> column("SELECT labels::text || ' ' || props::text FROM ${g}_node WHERE labels @> '[\"CiAccount\"]'").single() }
        assertEquals(row(saved), row(graph))
        val stamp = column("SELECT stamp FROM ${graph}_node WHERE labels @> '[\"CiAccount\"]'").single()!!
        assertTrue(Regex("[0-9a-f]{16}:[0-9a-f]{16}").matches(stamp), stamp)
        // Loaded as a saved one is, stamp and all.
        val loaded = assertNotNull(manager.load("ada", CiAccount::class.java))
        assertEquals(savedManager.load("ada", CiAccount::class.java)!!.copy(stamp = null), loaded.copy(stamp = null))
        assertEquals(stamp, loaded.stamp)
        assertEquals(note, loaded.extra["note"])
    }

    @Test
    fun `an imported node of several labels holds them as a save of a fragment of those labels does`() {
        savedManager.save(ChunkNode("c1", "text", sequenceNumber = 3L))
        val report = BulkImport(provider).run {
            // The call's label among the others too, which is no second label.
            nodes("ContentElement", key = "id", rows = sequenceOf(mapOf("id" to "c1", "text" to "text", "sequence_number" to 3L)), otherLabels = listOf("Chunk", "ContentElement"))
        }
        assertEquals(mapOf("ContentElement" to 1L), report.nodes)
        val row = { g: String -> column("SELECT labels::text || ' ' || props::text FROM ${g}_node WHERE labels @> '[\"Chunk\"]'").single() }
        assertEquals(row(saved), row(graph))
        assertEquals(ChunkNode("c1", "text", sequenceNumber = 3L), manager.load("c1", ChunkNode::class.java))
    }

    @Test
    fun `relationships are found by their ends' keys, the graph's nodes stamped anew, and the edge table made again as the layout makes it`() {
        val old = manager.save(CiAccount("old", "old@example.org"))
        val report = BulkImport(provider).run(maintenanceMegabytes = 64) {
            nodes("CiAccount", key = "id", rows = sequenceOf(account), lowercased = listOf("email"))
            nodes("Team", key = "code", rows = listOf(mapOf("code" to "t1", "name" to "One"), mapOf("code" to 2L, "name" to "Two")))
            relationships(
                "MEMBER_OF", BulkImport.End("CiAccount", "id"), BulkImport.End("Team", "code"),
                listOf(BulkImport.Pair("ada", "t1", mapOf("since" to 2020, "none" to null)), BulkImport.Pair("old", "t1"), BulkImport.Pair("old", 2L)),
            )
            // An end found by another property than the one its nodes were made under.
            relationships("MENTORS", BulkImport.End("CiAccount", "email"), BulkImport.End("CiAccount", "id"), sequenceOf(BulkImport.Pair("old@example.org", "ada")))
        }
        assertEquals(mapOf("MEMBER_OF" to 3L, "MENTORS" to 1L), report.relationships)
        assertEquals(
            listOf("ada t1 {\"since\": 2020}", "old 2 {}", "old t1 {}"),
            column(
                "SELECT (a.props ->> 'id') || ' ' || (b.props ->> 'code') || ' ' || e.props::text FROM ${graph}_edge e " +
                    "JOIN ${graph}_node a ON a.nid = e.src JOIN ${graph}_node b ON b.nid = e.dst WHERE e.type = 'MEMBER_OF' ORDER BY 1",
            ),
        )
        assertEquals(
            listOf("old ada"),
            column("SELECT (a.props ->> 'id') || ' ' || (b.props ->> 'id') FROM ${graph}_edge e JOIN ${graph}_node a ON a.nid = e.src JOIN ${graph}_node b ON b.nid = e.dst WHERE e.type = 'MENTORS'"),
        )
        // Cypher reads them.
        assertEquals(3L, PostgresTestDatabase.fixtures(pm).query(QuerySpecification.withStatement("MATCH (:CiAccount)-[r:MEMBER_OF]->(:Team) RETURN count(r)").transform(Long::class.java)).single())
        // The graph's node that gained relationships keeps its first token and has a new second one.
        val stamp = column("SELECT stamp FROM ${graph}_node WHERE props ->> 'id' = 'old'").single()!!
        assertEquals(old.stamp!!.substring(0, 17), stamp.substring(0, 17))
        assertNotEquals(old.stamp, stamp)
        // The edge table's foreign keys and indexes, as a fresh graph's.
        assertEquals(edgeObjects(saved), edgeObjects(graph))
    }

    @Test
    fun `an import is refused and leaves the graph as it was where a key is taken, an end is missing or two nodes, or nodes come after relationships`() {
        manager.save(CiAccount("old", "old@example.org"))
        // Two of the graph's nodes that hold one key, which names no one node.
        PostgresTestDatabase.fixtures(pm).execute(QuerySpecification.withStatement(cypherStatement("CREATE (:Team {code: 'dup'}), (:Team {code: 'dup'})")))
        val before = nodes(graph) to edges(graph)
        val objects = edgeObjects(graph)
        val taken = assertThrows<DrivineException> {
            BulkImport(provider).run { nodes("CiAccount", key = "id", rows = sequenceOf(mapOf("id" to "new"), mapOf("id" to "old"))) }
        }
        assertTrue("key=old" in taken.message!!, taken.message)
        val missing = assertThrows<DrivineException> {
            BulkImport(provider).run {
                nodes("Team", key = "code", rows = sequenceOf(mapOf("code" to "t1")))
                relationships("MEMBER_OF", BulkImport.End("CiAccount", "id"), BulkImport.End("Team", "code"), sequenceOf(BulkImport.Pair("nobody", "t1")))
            }
        }
        assertTrue("nobody" in missing.message!!, missing.message)
        val ambiguous = assertThrows<DrivineException> {
            BulkImport(provider).run {
                relationships("MEMBER_OF", BulkImport.End("CiAccount", "id"), BulkImport.End("Team", "code"), sequenceOf(BulkImport.Pair("old", "dup")))
            }
        }
        assertTrue("two of its nodes hold" in ambiguous.message!! && "dup" in ambiguous.message!!, ambiguous.message)
        assertThrows<IllegalStateException> {
            BulkImport(provider).run {
                relationships("MEMBER_OF", BulkImport.End("CiAccount", "id"), BulkImport.End("CiAccount", "id"), emptySequence())
                nodes("Team", key = "code", rows = sequenceOf(mapOf("code" to "t1")))
            }
        }
        val keyless = assertThrows<DrivineException> {
            BulkImport(provider).run { nodes("Team", key = "code", rows = sequenceOf(mapOf("name" to "no code"))) }
        }
        assertTrue("'code'" in keyless.message!!, keyless.message)
        val stamped = assertThrows<DrivineException> {
            BulkImport(provider).run { nodes("Team", key = "code", rows = sequenceOf(mapOf("code" to "t1", Stamps.PROPERTY to Stamps.fresh()))) }
        }
        assertTrue(Stamps.PROPERTY in stamped.message!!, stamped.message)
        assertEquals(before, nodes(graph) to edges(graph))
        assertEquals(objects, edgeObjects(graph))
        assertEquals(0L, count("SELECT count(*) FROM pg_class WHERE relname LIKE '${graph}_bulk%'"))
    }

    @Test
    fun `an import is refused on a connection of the application's transaction, which would hold all of it`() {
        val native = PostgresTestDatabase.provider.nativeDriver
        val joining = JdbcConnectionProvider(graph, DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(native), graphName = graph)
        // The graph's tables made first, outside the transaction.
        joining.connect().release()
        val before = nodes(graph)
        val held = native.connection.apply { autoCommit = false }
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(native, ConnectionHolder(held))
        try {
            val refused = assertThrows<DrivineException> { BulkImport(joining).run { nodes("Team", key = "code", rows = sequenceOf(mapOf("code" to "t1"))) } }
            assertTrue("outside a transaction" in refused.message!!, refused.message)
            assertTrue(!held.isClosed)
        } finally {
            TransactionSynchronizationManager.unbindResource(native)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
            held.rollback()
            held.close()
        }
        assertEquals(before, nodes(graph))
    }

    @Test
    fun `an import runs on Postgres alone`() {
        val sqlite = JdbcConnectionProvider("s", DatabaseType.SQLITE, PostgresTestDatabase.provider.nativeDriver, graphName = "s")
        assertThrows<DrivineException> { BulkImport(sqlite) }
    }
}
