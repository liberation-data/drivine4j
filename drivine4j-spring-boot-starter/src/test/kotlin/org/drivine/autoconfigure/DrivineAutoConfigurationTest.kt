package org.drivine.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.SubtypeRegistry
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import org.drivine.schema.SchemaManager
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class DrivineAutoConfigurationTest {

    private val runner = isolatedRunner()
        .withConfiguration(AutoConfigurations.of(DrivineAutoConfiguration::class.java))

    /** A datasource Drivine can build without reaching it: the Neo4j driver connects on first use. */
    private val datasourceProperties = arrayOf(
        "database.datasources.neo.type=NEO4J",
        "database.datasources.neo.host=localhost",
        "database.datasources.neo.port=7687",
        "database.datasources.neo.user-name=neo4j",
        "database.datasources.neo.password=password",
    )

    /** Every bean Drivine contributes, by type. */
    private val drivineBeanTypes = listOf(
        DataSourceMap::class.java,
        SubtypeRegistry::class.java,
        DatabaseRegistry::class.java,
        TransactionContextHolder::class.java,
        PlatformTransactionManager::class.java,
        Transactions::class.java,
        PersistenceManagerFactory::class.java,
        GraphObjectManagerFactory::class.java,
        SchemaManager::class.java,
        ApplicationRunner::class.java,
        DrivineProperties::class.java,
        DrivineSchemaProperties::class.java,
        DrivineQueryProperties::class.java,
    )

    @Test
    fun `the starter lists the auto-configuration for Spring Boot to find`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)
        assertThat(candidates.candidates).contains(DrivineAutoConfiguration::class.java.name)
    }

    @Test
    fun `a context with no datasource gets no Drivine beans`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DrivineAutoConfiguration::class.java)
            assertThat(context).doesNotHaveBean(DataSourceMap::class.java)
            assertThat(context).doesNotHaveBean(DatabaseRegistry::class.java)
            assertThat(context).doesNotHaveBean(PersistenceManagerFactory::class.java)
            assertThat(context).doesNotHaveBean(GraphObjectManagerFactory::class.java)
            assertThat(context).doesNotHaveBean(SchemaManager::class.java)
            assertThat(context).doesNotHaveBean(PlatformTransactionManager::class.java)
            assertThat(context).doesNotHaveBean(Transactions::class.java)
            assertThat(context).doesNotHaveBean(ApplicationRunner::class.java)
        }
    }

    @Test
    fun `datasource properties give a persistence manager and a schema manager`() {
        runner.withPropertyValues(*datasourceProperties).run { context ->
            assertThat(context).hasNotFailed()
            drivineBeanTypes.forEach { assertThat(context).hasSingleBean(it) }
            assertThat(context.getBean(DataSourceMap::class.java).dataSources).containsOnlyKeys("neo")
            assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("neo")
            assertThat(context.getBean(PlatformTransactionManager::class.java)).isInstanceOf(DrivineTransactionManager::class.java)

            val persistenceManager = context.getBean(PersistenceManagerFactory::class.java).get("neo")
            assertThat(persistenceManager).isInstanceOf(PersistenceManager::class.java)
            assertThat(persistenceManager.database).isEqualTo("neo")
        }
    }

    @Test
    fun `datasource properties are matched by their relaxed name`() {
        runner.withPropertyValues(*datasourceProperties.map { it.replace("datasources", "dataSources") }.toTypedArray())
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PersistenceManagerFactory::class.java)
                assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("neo")
            }
    }

    @Test
    fun `datasources from environment variables are matched`() {
        val variables = datasourceProperties.associate { property ->
            val (name, value) = property.split("=", limit = 2)
            name.uppercase().replace('.', '_').replace("-", "") to value
        }
        runner.withEnvironmentVariables(variables).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(PersistenceManagerFactory::class.java)
            assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("neo")
        }
    }

    @Test
    fun `a DataSourceMap bean is a datasource too`() {
        runner.withUserConfiguration(DataSourceMapConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            drivineBeanTypes.forEach { assertThat(context).hasSingleBean(it) }
            assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("app")
        }
    }

    @Test
    fun `an EnableDrivine application with the starter gets each bean once`() {
        runner.withUserConfiguration(EnableDrivineApp::class.java)
            .withPropertyValues(*datasourceProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DrivineAutoConfiguration::class.java)
                drivineBeanTypes.forEach { assertThat(context).hasSingleBean(it) }
                assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("neo")
            }
    }

    @Test
    fun `an EnableDrivine application with its own DataSourceMap and the starter gets each bean once`() {
        runner.withUserConfiguration(EnableDrivineWithDataSourceMapApp::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DrivineAutoConfiguration::class.java)
            drivineBeanTypes.forEach { assertThat(context).hasSingleBean(it) }
            assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("app")
        }
    }

    @Test
    fun `beans the application defines win`() {
        runner.withUserConfiguration(OwnBeansConfig::class.java)
            .withPropertyValues(*datasourceProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                val own = context.getBean(OwnBeansConfig::class.java)
                assertThat(context.getBean(DataSourceMap::class.java)).isSameAs(own.dataSourceMap)
                assertThat(context.getBean(SubtypeRegistry::class.java)).isSameAs(own.subtypeRegistry)
                assertThat(context.getBean(PlatformTransactionManager::class.java)).isSameAs(own.transactionManager)
                assertThat(context.getBean(SchemaManager::class.java)).isSameAs(own.schemaManager)
                // The rest are Drivine's, built from the application's beans.
                assertThat(context.getBean(DatabaseRegistry::class.java).databaseNames).containsExactly("app")
                assertThat(context.getBean(DatabaseRegistry::class.java).subtypeRegistry).isSameAs(own.subtypeRegistry)
                assertThat(context).hasSingleBean(PersistenceManagerFactory::class.java)
            }
    }

    @Test
    fun `Spring Boot's transaction auto-configuration uses Drivine's transaction manager`() {
        isolatedRunner()
            .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration::class.java, DrivineAutoConfiguration::class.java))
            .withPropertyValues(*datasourceProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(TransactionTemplate::class.java)
                assertThat(context.getBean(TransactionTemplate::class.java).transactionManager)
                    .isSameAs(context.getBean(PlatformTransactionManager::class.java))
                    .isInstanceOf(DrivineTransactionManager::class.java)
            }
    }

    @Test
    fun `drivine schema enabled false leaves out the startup run and keeps the schema manager`() {
        runner.withPropertyValues(*datasourceProperties, "drivine.schema.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(SchemaManager::class.java)
            assertThat(context).doesNotHaveBean(ApplicationRunner::class.java)
        }
    }

    @Test
    fun `excluding the auto-configuration leaves an EnableDrivine application as it was`() {
        isolatedRunner()
            .withUserConfiguration(EnableDrivineApp::class.java)
            .withPropertyValues(*datasourceProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DrivineAutoConfiguration::class.java)
                drivineBeanTypes.forEach { assertThat(context).hasSingleBean(it) }
            }
    }

    @Test
    fun `an empty datasources key is no datasource`() {
        // What YAML gives for a bare `datasources:` key.
        runner.withPropertyValues("database.datasources=").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DrivineAutoConfiguration::class.java)
        }
    }

    @Test
    fun `spring autoconfigure exclude turns the starter off`() {
        isolatedRunner()
            .withUserConfiguration(AutoConfiguredApp::class.java)
            .withPropertyValues(*datasourceProperties, "spring.autoconfigure.exclude=org.drivine.autoconfigure.DrivineAutoConfiguration")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DrivineAutoConfiguration::class.java)
                assertThat(context).doesNotHaveBean(PersistenceManagerFactory::class.java)
            }
    }

    @Test
    fun `Spring Boot's auto-configuration finds the starter`() {
        isolatedRunner()
            .withUserConfiguration(AutoConfiguredApp::class.java)
            .withPropertyValues(*datasourceProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PersistenceManagerFactory::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class AutoConfiguredApp

    @Configuration(proxyBeanMethods = false)
    @EnableDrivine
    @EnableDrivinePropertiesConfig
    class EnableDrivineApp

    @Configuration(proxyBeanMethods = false)
    @EnableDrivine
    class EnableDrivineWithDataSourceMapApp {
        @Bean
        fun dataSourceMap() = sampleDataSourceMap()
    }

    @Configuration(proxyBeanMethods = false)
    class DataSourceMapConfig {
        @Bean
        fun dataSourceMap() = sampleDataSourceMap()
    }

    @Configuration(proxyBeanMethods = false)
    class OwnBeansConfig {
        val dataSourceMap = sampleDataSourceMap()
        val subtypeRegistry = SubtypeRegistry()
        val transactionManager = NoOpTransactionManager()
        val schemaManager = DatabaseRegistry(DataSourceMap()).let { registry ->
            SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, emptyList())
        }

        @Bean
        fun appDataSourceMap(): DataSourceMap = dataSourceMap

        @Bean
        fun appSubtypeRegistry(): SubtypeRegistry = subtypeRegistry

        @Bean
        fun appTransactionManager(): PlatformTransactionManager = transactionManager

        @Bean
        fun appSchemaManager(): SchemaManager = schemaManager
    }

    class NoOpTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) {}
        override fun rollback(status: TransactionStatus) {}
    }
}

private fun sampleDataSourceMap() = DataSourceMap(
    mapOf("app" to ConnectionProperties(type = DatabaseType.NEO4J, host = "localhost", port = 7687, userName = "neo4j", password = "password"))
)
