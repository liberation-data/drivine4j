package org.drivine.migration

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.StoreLayoutException
import org.drivine.cypher.store.migrate.LayoutMigration.Declaration
import org.drivine.cypher.store.migrate.LayoutMigration.Kind
import org.drivine.cypher.store.migrate.Status
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
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
}
