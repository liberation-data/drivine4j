package org.drivine.backend.oracle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a Cypher statement run through a connection on Oracle is compiled with: the leading property of each labelled index
 * the graph's catalog lists, kept for the database, its user and the graph (#805). No database is needed: the catalog's
 * rows are given as a connection's query gives them.
 */
class OracleIndexedPropertiesTest {

    private var now = 0L
    private val clock = OracleIndexedProperties.clock
    private val logger = LoggerFactory.getLogger(OracleIndexedProperties::class.java) as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>()

    /** A graph of its own for each test: what is read is kept for the process. */
    private val graph = "oip_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    @BeforeEach
    fun setUp() {
        OracleIndexedProperties.clock = { now }
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun tearDown() {
        OracleIndexedProperties.clock = clock
        logger.detachAppender(appender)
        logger.level = null
    }

    /** A connection to [url] as [user] that answers nothing but who it is. */
    private fun connection(url: String = "jdbc:oracle:thin:@db", user: String = "SCOTT"): Connection {
        val metaData = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
            when (method.name) {
                "getURL" -> url
                "getUserName" -> user
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DatabaseMetaData
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            if (method.name == "getMetaData") metaData else throw UnsupportedOperationException(method.name)
        } as Connection
    }

    private fun row(label: String, vararg properties: String, kind: String = "RANGE_INDEX") =
        mapOf("kind" to kind, "label" to label, "properties" to properties.toList())

    /** Rows that count each read, and give [rows] or throw what [failing] gives. */
    private class Catalog(var rows: List<Any?>, var failing: Exception? = null) {
        var reads = 0
        val statements = mutableListOf<String>()
        val read: (String) -> List<Any?> = { sql ->
            reads++
            statements += sql
            failing?.let { throw it }
            rows
        }
    }

    private val warnings get() = appender.list.filter { it.level == Level.WARN }

    @Test
    fun `each label's indexes give the property each begins with, a uniqueness constraint's among them, read by the catalog's own query`() {
        val catalog = Catalog(listOf(row("Person", "name", "age"), row("Person", "id", kind = "UNIQUENESS_CONSTRAINT"), row("City", "name")))
        val leading = OracleIndexedProperties.leading(connection(), graph, catalog.read)
        assertEquals(setOf("Person" to "name", "Person" to "id", "City" to "name"), leading)
        assertEquals(listOf(LabelledIndexCatalog.query(OracleGraph(graph))), catalog.statements)
    }

    @Test
    fun `what was read is kept until it is a minute old, then read again`() {
        val catalog = Catalog(listOf(row("Person", "name")))
        val connection = connection()
        OracleIndexedProperties.leading(connection, graph, catalog.read)
        catalog.rows = listOf(row("Person", "name"), row("Person", "age"))
        now += OracleIndexedProperties.MAX_AGE.toNanos() - 1
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(1, catalog.reads)
        now += 1
        assertEquals(setOf("Person" to "name", "Person" to "age"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(2, catalog.reads)
    }

    @Test
    fun `an index made or dropped in this process has the catalog read again at once, whatever the graph name's case`() {
        val catalog = Catalog(listOf(row("Person", "name")))
        val connection = connection()
        OracleIndexedProperties.leading(connection, graph, catalog.read)
        catalog.rows = emptyList()
        LabelledIndexCatalog.changed(graph.uppercase())
        assertEquals(emptySet(), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(2, catalog.reads)
        assertEquals(emptySet(), OracleIndexedProperties.leading(connection, graph.uppercase(), catalog.read))
        assertEquals(2, catalog.reads, "The same graph, named in another case, is the same store")
    }

    @Test
    fun `each database and user has its own`() {
        val catalog = Catalog(listOf(row("Person", "name")))
        OracleIndexedProperties.leading(connection(), graph, catalog.read)
        catalog.rows = listOf(row("City", "name"))
        assertEquals(setOf("City" to "name"), OracleIndexedProperties.leading(connection(user = "OTHER"), graph, catalog.read))
        assertEquals(setOf("City" to "name"), OracleIndexedProperties.leading(connection(url = "jdbc:oracle:thin:@other"), graph, catalog.read))
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection(), graph, catalog.read))
        assertEquals(3, catalog.reads)
    }

    @Test
    fun `a catalog that can't be read keeps what was read before, says so once, and is read again after a minute`() {
        val catalog = Catalog(listOf(row("Person", "name")))
        val connection = connection()
        OracleIndexedProperties.leading(connection, graph, catalog.read)
        catalog.failing = SQLException("ORA-00942: table or view does not exist", "42000", 942)
        now += OracleIndexedProperties.MAX_AGE.toNanos()
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(2, catalog.reads, "A read that failed is not tried again on every statement")
        now += OracleIndexedProperties.MAX_AGE.toNanos()
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(3, catalog.reads)
        assertEquals(1, warnings.size, appender.list.joinToString("\n") { it.formattedMessage })
        assertTrue(warnings.single().formattedMessage.contains(graph))
        catalog.failing = null
        catalog.rows = listOf(row("City", "name"))
        now += OracleIndexedProperties.MAX_AGE.toNanos()
        assertEquals(setOf("City" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
    }

    @Test
    fun `a catalog never read gives none, and is read again after a minute`() {
        val catalog = Catalog(emptyList(), failing = SQLException("ORA-00942: table or view does not exist", "42000", 942))
        val connection = connection()
        assertEquals(emptySet(), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(emptySet(), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(1, catalog.reads)
        catalog.failing = null
        catalog.rows = listOf(row("Person", "name"))
        now += OracleIndexedProperties.MAX_AGE.toNanos()
        assertEquals(setOf("Person" to "name"), OracleIndexedProperties.leading(connection, graph, catalog.read))
        assertEquals(1, warnings.size)
    }
}
