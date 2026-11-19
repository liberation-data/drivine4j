package org.drivine.migration

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.StoreLayoutException
import org.drivine.migration.MigrationReport.Status
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A SQLite graph drivine4j 0.7.0 wrote, in store layout 1 (`src/test/resources/migration`), migrated to layout 2 by the
 * shared module's migrator, run by [StoreLayoutMigration] on the store itself (liberation-data/drivine4j#832,
 * drivine-cypher-spec#256): refused before, a dry run that writes nothing, two spellings of one moment under a unique
 * index stopping the run as `collisions` (layout 1's index dropped before the writes, so the rewrite isn't refused by it),
 * resumed once corrected with plain SQL, and read by this version as layout 2 after.
 */
@OptIn(ExperimentalStoreLayoutMigration::class)
class StoreLayoutMigrationSqliteTest {

    @TempDir
    lateinit var directory: Path

    private fun fixture(): String {
        val path = directory.resolve("layout1.sqlite")
        StoreLayoutMigrationSqliteTest::class.java.getResourceAsStream("/migration/layout1-0.7.0.sqlite")!!.use { Files.copy(it, path) }
        return path.toString()
    }

    private val declarations = listOf(
        Declaration("Person", "born", Kind.DATE),
        Declaration("KNOWS", "since", Kind.DATE),
        Declaration("Event", "at", Kind.DATE_TIME),
    )

    private fun provider(path: String) = JdbcConnectionProvider.sqlite("migration", path, graphName = "drivine")

    private fun snapshot(path: String): List<String> = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().executeQuery("SELECT name, sql FROM sqlite_master UNION ALL SELECT nid, props FROM drivine_node UNION ALL SELECT eid, props FROM drivine_edge ORDER BY 1, 2")
            .use { rs -> generateSequence { if (rs.next()) "${rs.getString(1)}|${rs.getString(2)}" else null }.toList() }
    }

    @Test
    fun `a graph 0_7_0 wrote is refused, dry run, stopped at a collision, corrected and migrated, then read as layout 2`() {
        val path = fixture()
        val before = provider(path)
        assertFailsWith<StoreLayoutException> { before.connect() }
        before.end()

        val provider = provider(path)
        val untouched = snapshot(path)
        val dryRun = StoreLayoutMigration(provider).migrate(declarations, dryRun = true)
        assertEquals(Status.DRY_RUN, dryRun.status, dryRun.message)
        assertEquals(untouched, snapshot(path), "a dry run writes nothing")
        assertEquals(2, dryRun.notes.size)
        // Person.nick holds a text that reads as a date, in its one row: a candidate, never applied.
        assertTrue(dryRun.candidates.any { it.label == "Person" && it.property == "nick" && it.kind == "date" }, "${dryRun.candidates}")
        // The module's report, as drivine4j's: each label and property as the module names it.
        assertEquals("dryRun", dryRun.toMap()["status"])
        assertTrue(dryRun.tables.getValue("nodes").let { it.rows > 0 && it.changed > 0 }, "${dryRun.tables}")
        assertTrue(dryRun.properties.any { it.label == "Person" && it.property == "born" && (it.changes["temporal"] ?: 0) > 0 }, "${dryRun.properties}")
        assertTrue(dryRun.observed.any { it.label == "Person" && it.property == "nick" && it.classes["date"] == 1L }, "${dryRun.observed}")

        // The two spellings of one moment are one value in layout 2: the run stops, every value rewritten, the graph refused.
        val stopped = StoreLayoutMigration(provider).migrate(declarations)
        assertEquals(Status.COLLISIONS, stopped.status, stopped.message)
        assertEquals(listOf("Event" to listOf("at")), stopped.collisions.map { it.label to it.properties })
        assertFailsWith<StoreLayoutException> { provider(path).connect() }

        // Corrected with the store's own tools, then resumed: migrated.
        DriverManager.getConnection("jdbc:sqlite:$path").use { it.createStatement().executeUpdate("DELETE FROM drivine_node WHERE nid = 8") }
        val migrated = StoreLayoutMigration(provider).migrate(declarations)
        assertEquals(Status.MIGRATED, migrated.status, migrated.message)
        provider.end()

        // This version reads it as layout 2: dates as dates, ordered as dates, a key that begins with $ as the user's.
        val after = provider(path)
        val pm = NonTransactionalPersistenceManager(after, "migration", DatabaseType.SQLITE, SubtypeRegistry())
        fun one(cypher: String): Any? = pm.query(QuerySpecification.withStatement(CypherStatement(cypher))).single()
        assertEquals("1815-12-10", one("MATCH (p:Person {name: 'Ada'}) RETURN toString(p.born)"))
        assertEquals("Charles", one("MATCH (p:Person) WHERE p.born < date('1800-01-01') RETURN p.name"))
        assertEquals(true, one("MATCH ()-[k:KNOWS]->() RETURN k.since = date('2020-01-01')"))
        assertEquals(1L, (one("MATCH (r:Robot) RETURN r.`\$t`") as Number).toLong())
        // An undeclared text that reads as a date stays a text.
        assertEquals("2024-03-15", one("MATCH (p:Person {name: 'Mary'}) RETURN p.nick"))
        assertEquals(false, one("MATCH (p:Person {name: 'Mary'}) RETURN p.nick = date('2024-03-15')"))
        after.end()
        // Layout 1's indexes are dropped; the migration made none, and no checkpoint is left.
        val objects = snapshot(path).filter { it.startsWith("drivine_") }.map { it.substringBefore('|') }
        assertTrue(objects.none { it.startsWith("drivine_uq_") || it == "drivine_migrate2" }, "$objects")
    }

    @NodeFragment(labels = ["Person"])
    data class Timed(@NodeId val name: String, val born: LocalDate?, val tenure: Duration?)

    @Test
    fun `a Duration 0_7_0 saved as its seconds is kept a number by the migration, and loads back as the Duration it was`() {
        // 0.7.0 saved a Duration as its seconds, a number, as Jackson's JavaTimeModule writes one (before drivine4j#862):
        // the migration declares it no kind and keeps the number (drivine-cypher-spec#405), which this version still
        // loads as the Duration, and saves again as a duration.
        val path = fixture()
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().executeUpdate("UPDATE drivine_node SET props = json_set(props, '$.tenure', 3600.0) WHERE json_extract(props, '$.name') = 'Ada'")
            c.createStatement().executeUpdate("DELETE FROM drivine_node WHERE nid = 8")
        }
        val provider = provider(path)
        val timed = StoreLayoutMigration.declarationsOf(Timed::class.java)
        assertEquals(listOf(Declaration("Person", "born", Kind.DATE)), timed)
        val migrated = StoreLayoutMigration(provider).migrate(declarations + timed)
        assertEquals(Status.MIGRATED, migrated.status, migrated.message)
        provider.end()

        val after = provider(path)
        val pm = NonTransactionalPersistenceManager(after, "migration", DatabaseType.SQLITE, SubtypeRegistry())
        assertEquals(3600L, (pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (p:Person {name: 'Ada'}) RETURN p.tenure"))).single() as Number).toLong())
        val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        val ada = manager.load("Ada", Timed::class.java)!!
        assertEquals(Timed("Ada", LocalDate.of(1815, 12, 10), Duration.ofHours(1)), ada)
        manager.save(ada.copy(tenure = Duration.ofMinutes(90)))
        assertEquals(Duration.ofMinutes(90), manager.load("Ada", Timed::class.java)!!.tenure)
        after.end()
    }

    @Test
    fun `a run stopped by a failed batch is reported failed, its committed batches kept, and resumed to the end once the store is fixed`() {
        val path = fixture()
        val provider = provider(path)
        // The Robot's write fails, in the third batch of two: the two before it are committed, the rest unwritten.
        sql(path, "CREATE TRIGGER test_stop BEFORE UPDATE ON drivine_node WHEN OLD.nid = 6 BEGIN SELECT RAISE(ABORT, 'stopped by the test'); END")
        val failed = StoreLayoutMigration(provider).migrate(declarations, batchSize = 2)
        assertEquals(Status.FAILED, failed.status, failed.message)
        assertTrue(failed.message.orEmpty().contains("stopped by the test"), failed.message)
        assertTrue(props(path, 1) != "{\"born\":\"1815-12-10\",\"height\":1.65,\"name\":\"Ada\",\"visits\":3}", "a committed batch is kept: ${props(path, 1)}")
        assertEquals("{\"\$t\":1,\"\$x\":\"y\",\"name\":\"R2\"}", props(path, 6), "the failed batch is rolled back")
        assertFailsWith<StoreLayoutException> { provider(path).connect() }

        sql(path, "DROP TRIGGER test_stop")
        // Part way through: a dry run reads only a graph that hasn't begun, and other declarations don't resume it.
        val dry = StoreLayoutMigration(provider).migrate(declarations, batchSize = 2, dryRun = true)
        assertEquals(Status.IN_PROGRESS, dry.status, dry.message)
        val other = StoreLayoutMigration(provider).migrate(declarations.dropLast(1), batchSize = 2)
        assertEquals(Status.REFUSED, other.status, other.message)
        assertTrue(other.message.orEmpty().contains("other declarations"), other.message)
        assertEquals("{\"\$t\":1,\"\$x\":\"y\",\"name\":\"R2\"}", props(path, 6), "neither writes")
        assertEquals(Status.COLLISIONS, StoreLayoutMigration(provider).migrate(declarations, batchSize = 2).status)
        sql(path, "DELETE FROM drivine_node WHERE nid = 8")
        val migrated = StoreLayoutMigration(provider).migrate(declarations, batchSize = 2)
        assertEquals(Status.MIGRATED, migrated.status, migrated.message)
        provider.end()

        // Each row rewritten once: the Robot's `$t` is the user's, not escaped twice.
        val after = provider(path)
        val pm = NonTransactionalPersistenceManager(after, "migration", DatabaseType.SQLITE, SubtypeRegistry())
        fun one(cypher: String): Any? = pm.query(QuerySpecification.withStatement(CypherStatement(cypher))).single()
        assertEquals(1L, (one("MATCH (r:Robot) RETURN r.`\$t`") as Number).toLong())
        assertEquals("1815-12-10", one("MATCH (p:Person {name: 'Ada'}) RETURN toString(p.born)"))
        after.end()
    }

    @Test
    fun `a graph in layout 2 is current, and another run of a migrated graph finds nothing to do`() {
        val fresh = directory.resolve("fresh.sqlite").toString()
        val made = provider(fresh)
        made.connect().release()
        NonTransactionalPersistenceManager(made, "migration", DatabaseType.SQLITE, SubtypeRegistry())
            .execute(QuerySpecification.withStatement(CypherStatement("CREATE (:Person {name: 'Ada', born: date('1815-12-10')})")))
        val untouched = snapshot(fresh)
        assertEquals(Status.CURRENT, StoreLayoutMigration(made).migrate(declarations).status)
        assertEquals(untouched, snapshot(fresh), "a current graph is left as it is")
        made.end()

        val path = fixture()
        val provider = provider(path)
        assertEquals(Status.COLLISIONS, StoreLayoutMigration(provider).migrate(declarations).status)
        sql(path, "DELETE FROM drivine_node WHERE nid = 8")
        assertEquals(Status.MIGRATED, StoreLayoutMigration(provider).migrate(declarations).status)
        assertEquals(Status.CURRENT, StoreLayoutMigration(provider).migrate(declarations).status)
        provider.end()
    }

    @Test
    fun `a value not of its declared kind refuses the graph before anything is written, and a dry run that keeps it names it`() {
        val path = fixture()
        val provider = provider(path)
        val untouched = snapshot(path)
        val wrong = declarations + Declaration("Person", "name", Kind.DATE)
        val invalid = StoreLayoutMigration(provider).migrate(wrong)
        assertEquals(Status.INVALID, invalid.status, invalid.message)
        assertEquals(untouched, snapshot(path), "an invalid graph is refused before any write")
        val kept = StoreLayoutMigration(provider).migrate(wrong, dryRun = true, keepInvalid = true)
        assertEquals(Status.DRY_RUN, kept.status, kept.message)
        assertTrue(kept.invalidCount > 0, "$kept")
        assertTrue(kept.properties.single { it.label == "Person" && it.property == "name" }.invalid.all { it.table == "nodes" && it.kind == "date" }, "${kept.properties}")
        assertEquals(untouched, snapshot(path))
        provider.end()
    }

    @Test
    fun `a request the module refuses, and a store that keeps no graph, are refused before any connection`() {
        val path = fixture()
        val provider = provider(path)
        assertFailsWith<IllegalArgumentException> { StoreLayoutMigration(provider).migrate(declarations, batchSize = 0) }
        assertFailsWith<IllegalArgumentException> {
            StoreLayoutMigration(provider).migrate(listOf(Declaration("Person", "born", Kind.DATE), Declaration("Person", "born", Kind.DATE_TIME)))
        }
        provider.end()
        val neo4j = JdbcConnectionProvider("neo4j", DatabaseType.NEO4J, Mockito.mock(DataSource::class.java))
        assertFailsWith<DrivineException> { StoreLayoutMigration(neo4j) }
    }

    @Test
    fun `a migration is refused on a connection of the application's transaction, which would hold its writes and roll them back`() {
        val path = fixture()
        val untouched = snapshot(path)
        val held = DriverManager.getConnection("jdbc:sqlite:$path").apply { autoCommit = false }
        val source = Mockito.mock(DataSource::class.java)
        val provider = JdbcConnectionProvider("migration", DatabaseType.SQLITE, TransactionAwareDataSourceProxy(source), graphName = "drivine")
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(source, ConnectionHolder(held))
        try {
            val refused = assertFailsWith<DrivineException> { StoreLayoutMigration(provider).migrate(declarations) }
            assertTrue(refused.message.orEmpty().contains("outside a transaction"), refused.message)
        } finally {
            TransactionSynchronizationManager.unbindResource(source)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
            held.rollback()
            held.close()
        }
        assertEquals(untouched, snapshot(path))
    }

    private fun sql(path: String, statement: String) =
        DriverManager.getConnection("jdbc:sqlite:$path").use { it.createStatement().executeUpdate(statement) }

    private fun props(path: String, nid: Int): String = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().executeQuery("SELECT props FROM drivine_node WHERE nid = $nid").use { rs -> rs.next(); rs.getString(1) }
    }
}
