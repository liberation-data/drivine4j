package org.drivine.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.test.PostgresTestContainer
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import org.hibernate.jpa.HibernatePersistenceProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Drivine transaction inside an application's JPA transaction, a [JpaTransactionManager]'s over Hibernate, joins it
 * (#724), as it joins a `DataSourceTransactionManager`'s (see [PostgresSpringTransactionJoinTest]). `JpaTransactionManager`
 * reads only the JPA transaction's own mark to roll back, not the `ConnectionHolder`'s, so a Drivine rollback also
 * registers a synchronization that fails the commit: the JPA transaction rolls back, with the application's entity and
 * Drivine's write in it. The manager binds a `ConnectionHolder`, which `TransactionAwareDataSourceProxy` hands Drivine
 * the transaction's connection through, only where its [HibernateJpaDialect] gives the JDBC connection, as it does.
 */
@PostgresTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresJpaTransactionJoinTest {

    /** The application's entity, in a table of its own. */
    @Entity
    @Table(name = APP)
    open class Record(
        @field:Id @field:Column(name = "id") open var id: String = "",
        @field:Column(name = "run") open var run: String = "",
        @field:Column(name = "name") open var name: String = "",
    )

    private lateinit var pool: HikariDataSource
    private lateinit var factory: EntityManagerFactory
    private lateinit var entities: EntityManager
    private lateinit var template: TransactionTemplate
    private lateinit var provider: JdbcConnectionProvider
    private lateinit var transactions: Transactions
    private lateinit var drivine: PersistenceManager

    /** Reads what is committed, on a connection of the test JVM's own pool, which commits each statement. */
    private lateinit var reader: NonTransactionalPersistenceManager

    private val run = "jpa-" + UUID.randomUUID().toString().take(8)

    @BeforeAll
    fun setUp() {
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $APP", "CREATE TABLE $APP (id text PRIMARY KEY, run text NOT NULL, name text NOT NULL)")
        pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = PostgresTestContainer.getJdbcUrl()
                username = PostgresTestContainer.getConnectionUsername()
                password = PostgresTestContainer.getConnectionPassword()
                maximumPoolSize = 4
                addDataSourceProperty("stringtype", "unspecified")
            },
        )
        factory = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = pool
            persistenceUnitName = "drivineJoin"
            setPersistenceProviderClass(HibernatePersistenceProvider::class.java)
            setManagedTypes(PersistenceManagedTypes.of(Record::class.java.name))
            jpaDialect = HibernateJpaDialect()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            afterPropertiesSet()
        }.`object`!!
        entities = SharedEntityManagerCreator.createSharedEntityManager(factory)
        template = TransactionTemplate(JpaTransactionManager(factory).apply { setJpaDialect(HibernateJpaDialect()) })

        provider = JdbcConnectionProvider("jpaJoin", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(pool), graphName = GRAPH)
        // The graph's tables are made on the provider's first connection, which is had outside a Spring transaction.
        provider.connect().release(null)
        val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
        val holder = TransactionContextHolder(registry)
        transactions = Transactions(holder)
        drivine = PersistenceManagerFactory(registry, holder).get(provider.name, PersistenceManagerType.TRANSACTIONAL)
        reader = PostgresTestDatabase.graphManager(GRAPH)
    }

    @AfterEach
    fun clean() {
        reader.execute(QuerySpecification.withStatement(cypherStatement("MATCH (n:Note {run: \$run}) DELETE n")).bind(mapOf("run" to run)))
        PostgresTestDatabase.pm.execute(QuerySpecification.withStatement(sqlStatement("DELETE FROM $APP WHERE run = \$run")).bind(mapOf("run" to run)))
    }

    @AfterAll
    fun tearDown() {
        provider.end()
        factory.close()
        pool.close()
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $APP")
    }

    /** The application's entity, persisted and flushed, so its row is written in the JPA transaction. */
    private fun persist(name: String) {
        entities.persist(Record(UUID.randomUUID().toString(), run, name))
        entities.flush()
    }

    /** A note written by Drivine, in whatever transaction of Drivine's is open. */
    private fun note(name: String) = drivine.execute(
        QuerySpecification.withStatement(cypherStatement("CREATE (:Note {run: \$run, name: \$name})")).bind(mapOf("run" to run, "name" to name)),
    )

    /** That Drivine is given the JPA transaction's connection, through the holder the manager bound, and joins it. */
    private fun assertJoined() {
        assertIs<ConnectionHolder>(TransactionSynchronizationManager.getResource(pool), "JpaTransactionManager bound a ConnectionHolder")
        val connection = provider.connect() as JdbcConnection
        try {
            assertTrue(connection.joined, "Drivine was given the JPA transaction's connection, and joins it")
        } finally {
            connection.release(null)
        }
    }

    /** What is committed, read on another connection: `app:` rows of the entity's table and `drivine:` notes, in order. */
    private fun committed(): List<String> {
        val rows = PostgresTestDatabase.pm.query(
            QuerySpecification.withStatement(sqlStatement("SELECT name FROM $APP WHERE run = \$run")).bind(mapOf("run" to run)).transform(String::class.java),
        )
        val notes = reader.query(
            QuerySpecification.withStatement(cypherStatement("MATCH (n:Note {run: \$run}) RETURN n.name")).bind(mapOf("run" to run)).transform(String::class.java),
        )
        return (rows.map { "app:$it" } + notes.map { "drivine:$it" }).sorted()
    }

    /** Runs [block] in a JPA transaction and gives what it threw, or null: see [PostgresSpringTransactionJoinTest]. */
    private fun inJpa(block: (TransactionStatus) -> Unit): Throwable? = runCatching { template.executeWithoutResult(block) }.exceptionOrNull()

    @Test
    fun `a Drivine transaction that fails inside a JPA transaction fails its commit, which rolls back the entity and Drivine's write`() {
        var marked: Boolean? = null
        val failed = inJpa { status ->
            persist("entity")
            assertJoined()
            // The application catches Drivine's failure and carries on, as a caller of a failed @Transactional method may.
            assertFailsWith<IllegalStateException> {
                transactions.transaction {
                    note("drivine")
                    error("Drivine's block fails")
                }
            }
            marked = status.isRollbackOnly
        }

        assertEquals(emptyList(), committed())
        assertEquals(false, marked, "JpaTransactionManager reads no mark of Drivine's: the commit is failed instead")
        assertIs<UnexpectedRollbackException>(failed)
        assertContains(failed.message!!, "a Drivine transaction that joined this one rolled back")
    }

    @Test
    fun `a Drivine transaction that commits inside a JPA transaction is committed at the JPA commit, with the entity`() {
        var afterDrivine: List<String>? = null
        val failed = inJpa {
            persist("entity")
            assertJoined()
            transactions.transaction { note("drivine") }
            afterDrivine = committed()
        }

        assertEquals(emptyList(), afterDrivine, "Drivine's commit committed none of the JPA transaction")
        assertNull(failed)
        assertEquals(listOf("app:entity", "drivine:drivine"), committed())
    }

    private companion object {
        const val GRAPH = "jpa_join"
        const val APP = "jpa_join_app"
    }
}
