package org.drivine.schema

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * With nothing to apply, the manager says so, and says which: no catalog at all, or catalogs that
 * are all empty, named. No database is spoken to, so none is registered.
 */
class SchemaManagerNothingToApplyTest {

    private val logger = LoggerFactory.getLogger(SchemaManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun listen() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stopListening() {
        logger.detachAppender(appender)
    }

    private fun schemaManager(catalogs: List<SchemaCatalog>): SchemaManager {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap()))
        return SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs)
    }

    private fun onlyLine(): ILoggingEvent {
        assertEquals(1, appender.list.size, "one line: ${appender.list.map { it.formattedMessage }}")
        return appender.list.single()
    }

    @Test
    fun `no catalog at all is said at INFO, with the catalogs read once`() {
        schemaManager(emptyList()).enforce()

        val line = onlyLine()
        assertEquals(Level.INFO, line.level)
        assertContains(line.formattedMessage, "found no SchemaCatalog; nothing to apply")
        assertContains(line.formattedMessage, "Catalogs are read once, when the SchemaManager is built")
    }

    @Test
    fun `catalogs that are all empty are named, an unnamed one as unnamed`() {
        val catalogs = listOf(
            SchemaCatalog.of().named("dice"),
            SchemaCatalog.of(),
            SchemaCatalog.of().named("rag").forDatabase("neo"),
            SchemaCatalog.of(),
        )

        schemaManager(catalogs).enforce()

        val line = onlyLine()
        assertEquals(Level.INFO, line.level)
        assertContains(line.formattedMessage, "found 4 SchemaCatalog(s), all empty: 'dice', unnamed (2), 'rag'; nothing to apply")
        assertContains(line.formattedMessage, "Catalogs are read once")
    }

    @Test
    fun `every way in says it`() {
        val manager = schemaManager(listOf(SchemaCatalog.of().named("dice")))

        manager.enforce("dice")
        manager.recreateAll()
        manager.recreateAll("dice")

        assertEquals(3, appender.list.size)
        appender.list.forEach { assertContains(it.formattedMessage, "all empty: 'dice'") }
    }
}
