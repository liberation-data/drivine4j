package org.drivine.sqlite

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.NodeIdIndexCheck
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

@NodeFragment(labels = ["UsedTicket"])
data class UsedTicket(
    @NodeId
    val id: String,
)

/**
 * A fragment saved to a database no catalog names it on is warned of there, where its saves scan the label, as on the
 * database whose catalog names it (#858). Two SQLite graphs, `a` and `b`, stand for two databases.
 */
class SqliteNodeIdUsedTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(NodeIdIndexCheck::class.java) as Logger

    @BeforeEach
    fun start() {
        NodeIdIndexCheck.reset()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        NodeIdIndexCheck.reset()
        listOf("a", "b").forEach(SqliteTestDatabase::drop)
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("UsedTicket") }.map { it.formattedMessage }

    @Test
    fun `a fragment catalogued for one database and saved to another is warned of on both`() {
        val registry = DatabaseRegistry(DataSourceMap()).also { registry ->
            listOf("a", "b").forEach { graph ->
                registry.register(JdbcConnectionProvider(graph, DatabaseType.SQLITE, SqliteTestDatabase.provider.nativeDriver, graphName = graph))
            }
        }
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
        val schema = SchemaManager(
            factory,
            registry,
            listOf(
                // UsedTicket's id is left without its index on a, whose catalogs make another item; b's names another label only.
                SchemaCatalog.fromFragments(UsedTicket::class.java).withoutNodeIdIndexes().forDatabase("a").named("tickets"),
                SchemaCatalog.of(RangeIndexSpec("Elsewhere", "code")).forDatabase("a").named("other-a"),
                SchemaCatalog.of(RangeIndexSpec("Elsewhere", "code")).forDatabase("b").named("other"),
            ),
        )
        schema.enforce()
        assertEquals(1, warnings().size, "on a, whose catalog names it: ${warnings()}")
        assertEquals(true, warnings().single().endsWith(" On database 'a'."))

        StatelessGraphObjectManager(factory.get("b"), Neo4jObjectMapper.instance, SubtypeRegistry()).save(UsedTicket("t1"))
        assertEquals(2, warnings().size, "and on b, where it was saved: ${warnings()}")
        assertEquals(true, warnings().last().endsWith(" On database 'b'."))
    }
}
