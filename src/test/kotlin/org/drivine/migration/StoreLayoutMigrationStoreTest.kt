package org.drivine.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.StoreLayoutException
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.migration.MigrationReport.Status
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.store.StoreIdentityResolver
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A graph drivine4j 0.7.0 wrote, in store layout 1, into the test's own Postgres or Oracle, migrated to layout 2 by the
 * shared module's migrator as [StoreLayoutMigration] runs it on the store (drivine-cypher-spec#256): what
 * [StoreLayoutMigrationSqliteTest] shows on a file, shown on a server.
 *
 * The graph is written by 0.7.0 itself (src/migrationFixture070, which the build installs before the tests), so it is
 * the layout 1 a release wrote, not this checkout's idea of it. Each test has graphs of its own.
 *
 * - A dry run writes nothing and reports what a run would do.
 * - A run stopped by two spellings of one moment under a unique key is corrected with SQL and resumed, and the graph it
 *   leaves is the graph this version writes from the same statements: the same rows, the same stamp and marker, and,
 *   once the application has made its schema, the same indexes. The internal ids, and the count store the migration
 *   leaves unseeded (drivine-cypher-spec#349), are the differences, and the only ones.
 * - A run stopped by a failed batch, or by its session killed while it writes, is resumed to the end, each row
 *   rewritten once.
 */
@OptIn(ExperimentalStoreLayoutMigration::class)
abstract class StoreLayoutMigrationStoreTest(private val type: DatabaseType) {

    /** The store's connections, as the test JVM's pool gives them. */
    protected abstract val dataSource: DataSource
    protected abstract val url: String
    protected abstract val user: String
    protected abstract val password: String

    /** A SQL expression that writes the JSON column [column] as text that tells each value's type. */
    protected abstract fun json(column: String): String

    /** Lists the graph's own tables, one name a row. */
    protected abstract fun tables(graph: String): String

    /** Lists the indexes on the graph's tables, one a row: its name and what it indexes, as the store writes them. */
    protected abstract fun indexes(graph: String): String

    /** Makes the write of the Robot's row in [graph]'s nodes fail with `stopped by the test`, or, with [sleep], wait. */
    protected abstract fun stopAtRobot(graph: String, sleep: Boolean)

    /** Removes what [stopAtRobot] made. */
    protected abstract fun unstop(graph: String)

    /**
     * Kills the session waiting in [stopAtRobot]'s sleep in [graph], once there is one: true when it found one to kill.
     * Only that one: the store may be a local instance others use, and Oracle's schema DDL sleeps too while it waits.
     */
    protected abstract fun killSleeper(graph: String): Boolean

    /** Where the property [property] of the row `n` of a node table is the text [text]. */
    protected abstract fun propertyIs(property: String, text: String): String

    private val store = type.name.lowercase()
    private val mapper = ObjectMapper()

    private val declarations = listOf(
        Declaration("Person", "born", Kind.DATE),
        Declaration("KNOWS", "since", Kind.DATE),
        Declaration("Event", "at", Kind.DATE_TIME),
        // Layout 1 kept 2.0 as a whole number on Oracle: declared a float, it is the float this version writes.
        Declaration("Person", "height", Kind.FLOAT),
    )

    /** A graph name of the test's own, unused on a store kept between runs. */
    private fun graph(name: String) = "mig_${name}_${System.nanoTime().toString().takeLast(8)}"

    /** Writes the fixture's layout-1 graph [graph] with drivine4j 0.7.0, in a JVM of its own. */
    private fun writeLayout1(graph: String) {
        val lib = File(checkNotNull(System.getProperty("drivine.migration.fixture070")) { "Run by Gradle, which installs the fixture." })
        val java = File(System.getProperty("java.home"), "bin/java").path
        // Its output goes to a file, not a pipe read to its end, which would wait on a writer that hangs past the timeout.
        val output = File.createTempFile("fixture070-", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(java, "-cp", "${lib.path}/*", "FixtureKt", store, url, user, password, graph)
            .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("0.7.0's writer didn't finish in 5 minutes: ${output.readText()}")
        }
        assertEquals(0, process.exitValue(), "0.7.0's writer: ${output.readText()}")
    }

    private fun provider(graph: String) = JdbcConnectionProvider(graph, type, dataSource, graphName = graph)

    private fun manager(graph: String) = NonTransactionalPersistenceManager(provider(graph), graph, type, SubtypeRegistry())

    private fun <T> sql(block: (Connection) -> T): T = dataSource.connection.use { it.autoCommit = true; block(it) }

    protected fun execute(statement: String) = sql { c -> c.createStatement().use { it.execute(statement) } }

    private fun texts(query: String): List<String> = sql { c ->
        c.createStatement().use { s ->
            s.executeQuery(query).use { rs ->
                val width = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..width).joinToString("|") { rs.getString(it).orEmpty() } else null }.toList()
            }
        }
    }

    /** Every row of every table of [graph], and its indexes: what a dry run must leave as it was. */
    private fun snapshot(graph: String): List<String> =
        texts(tables(graph)).sorted().flatMap { table -> texts("SELECT * FROM $table").sorted().map { "$table: $it" } } + texts(indexes(graph)).sorted()

    private fun props(graph: String, where: String): String = texts("SELECT ${json("n.props")} FROM ${graph}_node n WHERE $where").single()

    private val robot get() = propertyIs("name", "R2")

    /** The id of the Event 0.7.0 wrote second, which collides with the first in layout 2: read before its text is rewritten. */
    private fun laterEvent(graph: String): String =
        texts("SELECT n.nid FROM ${graph}_node n WHERE ${propertyIs("at", "2024-03-15T10:30:00.500Z")}").single()

    @Test
    fun `a graph 0_7_0 wrote is refused, dry run, stopped at a collision, corrected and migrated, and is the graph this version writes`() {
        val graph = graph("full")
        writeLayout1(graph)
        assertFailsWith<StoreLayoutException> { provider(graph).connect() }
        val later = laterEvent(graph)

        val untouched = snapshot(graph)
        val dryRun = StoreLayoutMigration(provider(graph)).migrate(declarations, dryRun = true)
        assertEquals(Status.DRY_RUN, dryRun.status, dryRun.message)
        assertEquals(untouched, snapshot(graph), "a dry run writes nothing")
        assertTrue(dryRun.candidates.any { it.label == "Person" && it.property == "nick" && it.kind == "date" }, "${dryRun.candidates}")
        assertTrue(dryRun.tables.getValue("nodes").let { it.rows > 0 && it.changed > 0 }, "${dryRun.tables}")
        assertTrue(dryRun.properties.any { it.label == "Person" && it.property == "born" && (it.changes["temporal"] ?: 0) > 0 }, "${dryRun.properties}")

        val stopped = StoreLayoutMigration(provider(graph)).migrate(declarations)
        assertEquals(Status.COLLISIONS, stopped.status, stopped.message)
        assertEquals(listOf("Event" to listOf("at")), stopped.collisions.map { it.label to it.properties })
        assertFailsWith<StoreLayoutException> { provider(graph).connect() }

        execute("DELETE FROM ${graph}_node WHERE nid = $later")
        val migrated = StoreLayoutMigration(provider(graph)).migrate(declarations)
        assertEquals(Status.MIGRATED, migrated.status, migrated.message)
        assertEquals(Status.CURRENT, StoreLayoutMigration(provider(graph)).migrate(declarations).status)
        assertTrue(texts(tables(graph)).none { it.lowercase().endsWith("_migrate2") }, "no checkpoint is left: ${texts(tables(graph))}")

        readsAsLayout2(graph)
        assertSameAsWritten(graph)
    }

    @Test
    fun `a run stopped by a failed batch is reported failed, its committed batches kept, and resumed to the end`() {
        val graph = graph("failed")
        writeLayout1(graph)
        val ada = propertyIs("name", "Ada")
        val adaBefore = props(graph, ada)
        val robotBefore = props(graph, robot)
        val later = laterEvent(graph)
        stopAtRobot(graph, sleep = false)
        val failed = StoreLayoutMigration(provider(graph)).migrate(declarations, batchSize = 2)
        assertEquals(Status.FAILED, failed.status, failed.message)
        assertTrue(failed.message.orEmpty().contains("stopped by the test"), failed.message)
        assertEquals(robotBefore, props(graph, robot), "the failed batch is rolled back")
        // Ada's row, written before the Robot's, is in a batch committed before the failed one.
        assertTrue(props(graph, ada) != adaBefore, "a committed batch is kept: ${props(graph, ada)}")
        assertFailsWith<StoreLayoutException> { provider(graph).connect() }
        unstop(graph)

        val dry = StoreLayoutMigration(provider(graph)).migrate(declarations, batchSize = 2, dryRun = true)
        assertEquals(Status.IN_PROGRESS, dry.status, dry.message)
        val other = StoreLayoutMigration(provider(graph)).migrate(declarations.dropLast(1), batchSize = 2)
        assertEquals(Status.REFUSED, other.status, other.message)
        assertEquals(robotBefore, props(graph, robot), "neither writes")
        resumeToTheEnd(graph, later)
    }

    @Test
    fun `a run whose session is killed while it writes is resumed to the end, each row rewritten once`() {
        val graph = graph("killed")
        writeLayout1(graph)
        val robotBefore = props(graph, robot)
        val later = laterEvent(graph)
        stopAtRobot(graph, sleep = true)
        val run = CompletableFuture.supplyAsync { runCatching { StoreLayoutMigration(provider(graph)).migrate(declarations, batchSize = 2) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!killSleeper(graph)) {
            if (System.nanoTime() > deadline) fail("The migration never reached the Robot's row: ${run.getNow(null)}")
            Thread.sleep(200)
        }
        val ended = run.get(120, TimeUnit.SECONDS)
        ended.onSuccess { assertEquals(Status.FAILED, it.status, it.message) }
        unstop(graph)
        assertEquals(robotBefore, props(graph, robot), "the killed batch is rolled back")
        assertFailsWith<StoreLayoutException> { provider(graph).connect() }
        resumeToTheEnd(graph, later)
    }

    @Test
    fun `a value not of its declared kind refuses the graph before anything is written`() {
        val graph = graph("invalid")
        writeLayout1(graph)
        val untouched = snapshot(graph)
        val wrong = declarations + Declaration("Person", "name", Kind.DATE)
        assertEquals(Status.INVALID, StoreLayoutMigration(provider(graph)).migrate(wrong).status)
        assertEquals(untouched, snapshot(graph), "an invalid graph is refused before any write")
        val kept = StoreLayoutMigration(provider(graph)).migrate(wrong, dryRun = true, keepInvalid = true)
        assertEquals(Status.DRY_RUN, kept.status, kept.message)
        assertTrue(kept.invalidCount > 0, "$kept")
        assertEquals(untouched, snapshot(graph))
    }

    /** Resumes a stopped run past the collision it then meets, at the row [later], and checks the graph it leaves. */
    private fun resumeToTheEnd(graph: String, later: String) {
        assertEquals(Status.COLLISIONS, StoreLayoutMigration(provider(graph)).migrate(declarations, batchSize = 2).status)
        execute("DELETE FROM ${graph}_node WHERE nid = $later")
        val migrated = StoreLayoutMigration(provider(graph)).migrate(declarations, batchSize = 2)
        assertEquals(Status.MIGRATED, migrated.status, migrated.message)
        readsAsLayout2(graph)
        assertSameAsWritten(graph)
    }

    /** This version reads [graph] as layout 2: dates as dates, a key that begins with `$` as the user's, rewritten once. */
    private fun readsAsLayout2(graph: String) {
        val pm = manager(graph)
        fun one(cypher: String): Any? = pm.query(QuerySpecification.withStatement(CypherStatement(cypher))).single()
        assertEquals("1815-12-10", one("MATCH (p:Person {name: 'Ada'}) RETURN toString(p.born)"))
        assertEquals("Charles", one("MATCH (p:Person) WHERE p.born < date('1800-01-01') RETURN p.name"))
        assertEquals(true, one("MATCH ()-[k:KNOWS]->() RETURN k.since = date('2020-01-01')"))
        assertEquals(1L, (one("MATCH (r:Robot) RETURN r.`\$t`") as Number).toLong())
        assertEquals("y", one("MATCH (r:Robot) RETURN r.`\$x`"))
        assertEquals("2024-03-15", one("MATCH (p:Person {name: 'Mary'}) RETURN p.nick"))
        assertEquals(2.0, one("MATCH (p:Person {name: 'Charles'}) RETURN p.height"))
    }

    /**
     * [graph], once its schema is made, against the graph this version writes from the fixture's statements, less the
     * collision: the same nodes and relationships, property for property, each value of the same type, the same stamp
     * and marker, and the same indexes.
     */
    private fun assertSameAsWritten(graph: String) {
        val native = graph("native")
        val writer = manager(native)
        FIXTURE.forEach { writer.execute(QuerySpecification.withStatement(CypherStatement(it))) }
        val identities = listOf(writer, manager(graph)).map { pm ->
            pm.constraints.ensureUnique("Person", "name")
            pm.constraints.ensureUnique("Event", "at")
            // The store's identity, which a graph this version writes is given when first asked: the migrated graph's
            // stamp, kept from layout 1, is given one as that graph's would be.
            StoreIdentityResolver(pm).resolve().id
        }
        assertTrue(identities.distinct().size == 2, "each graph has an identity of its own: $identities")
        assertEquals(contents(native), contents(graph), "the migrated graph's rows are the ones this version writes")
        val tablesOf = others(graph)
        assertTrue(tablesOf.contains("<g>_schema") && tablesOf.any { it.startsWith("  ") }, "the marker is compared: $tablesOf")
        assertEquals(others(native), tablesOf, "and its other tables: the marker, the lock")
        assertTrue(texts(indexes(graph)).size >= 2, "the indexes are compared: ${texts(indexes(graph))}")
        val written = texts(indexes(native)).map { it.replace(native, "<g>", ignoreCase = true) }.toSet()
        val migrated = texts(indexes(graph)).map { it.replace(graph, "<g>", ignoreCase = true) }.toSet()
        assertEquals(written, migrated, "and its indexes: this version's alone ${written - migrated}, the migrated graph's alone ${migrated - written}")
    }

    /**
     * Each table of [graph] but its nodes and relationships, and its rows, the graph's name written `<g>`: but for the
     * count store's rows, which the migration leaves unseeded (drivine-cypher-spec#349) where this version counts as it
     * writes.
     */
    private fun others(graph: String): List<String> = texts(tables(graph)).map { it.lowercase() }.sorted().flatMap { table ->
        val named = table.replace(graph.lowercase(), "<g>")
        when (named) {
            "<g>_node", "<g>_edge" -> emptyList()
            "<g>_count2" -> listOf(named)
            else -> listOf(named) + texts("SELECT * FROM $table").map { "  ${it.replace(graph, "<g>", ignoreCase = true)}" }.sorted()
        }
    }

    /**
     * The nodes and relationships of [graph], each with its labels or type, its properties as JSON that tells each
     * value's type, and its stamp: a relationship's ends named by their properties, as the ids differ. The store's own
     * node gives its id and when it was assigned, which are the graph's own.
     */
    private fun contents(graph: String): List<String> {
        val nodes = texts("SELECT n.nid, ${json("n.labels")}, ${json("n.props")}, n.stamp FROM ${graph}_node n").associate { row ->
            val (nid, labels, props, stamp) = row.split("|", limit = 4)
            nid to "${canonical(labels)} ${canonical(props, own = labels.contains("DrivineStore"))} stamp=$stamp"
        }
        val edges = texts("SELECT e.src, e.dst, e.type, ${json("e.props")} FROM ${graph}_edge e").map { row ->
            val (src, dst, type, props) = row.split("|", limit = 4)
            "(${nodes[src]})-[$type ${canonical(props)}]->(${nodes[dst]})"
        }
        return (nodes.values + edges).sorted()
    }

    private fun canonical(json: String, own: Boolean = false): String {
        val node: JsonNode = mapper.readTree(json)
        if (own && node is ObjectNode) node.remove(listOf("storeId", "assignedAt"))
        return sorted(node).toString()
    }

    private fun sorted(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> mapper.createObjectNode().also { out -> node.properties().sortedBy { it.key }.forEach { (k, v) -> out.set<JsonNode>(k, sorted(v)) } }
        else -> node
    }

    private companion object {
        /**
         * The statements of src/migrationFixture070's Fixture.kt, but the second spelling of the Event's moment, and the
         * first written as the datetime its declaration makes of 0.7.0's text.
         */
        val FIXTURE = listOf(
            "CREATE (:Person {name: 'Ada', born: date('1815-12-10'), height: 1.65, visits: 3})",
            "CREATE (:Person {name: 'Charles', born: date('1791-12-26'), height: 2.0})",
            "CREATE (:Person {name: 'Mary', nick: '2024-03-15'})",
            "CREATE (a:Person {name: 'Bob'})-[:KNOWS {since: date('2020-01-01')}]->(b:Person {name: 'Cy'})",
            "CREATE (:Robot {`\$t`: 1, `\$x`: 'y', name: 'R2'})",
            "CREATE (:Event {at: datetime('2024-03-15T10:30:00.5Z')})",
        )
    }
}
