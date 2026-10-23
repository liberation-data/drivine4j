package org.drivine.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.util.stream.Stream

/**
 * A datasource named under `database.datasources` whose properties don't make a datasource fails
 * startup, saying which one and what is missing, rather than being dropped from the [DataSourceMap].
 * Both ways of building the map from properties — the starter and `@EnableDrivinePropertiesConfig` —
 * go through [DrivinePropertiesConfiguration], so each case runs on both.
 */
class DrivinePropertiesConfigurationTest {

    private val neo = arrayOf(
        "database.datasources.neo.type=NEO4J",
        "database.datasources.neo.host=localhost",
        "database.datasources.neo.port=7687",
        "database.datasources.neo.user-name=neo4j",
        "database.datasources.neo.password=password",
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `full datasources make the DataSourceMap`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues(*neo, "database.datasources.graph.type=FALKORDB", "database.datasources.graph.host=localhost")
            .run { context ->
                assertThat(context).hasNotFailed()
                val dataSources = context.getBean(DataSourceMap::class.java).dataSources
                assertThat(dataSources).containsOnlyKeys("neo", "graph")
                assertThat(dataSources.getValue("graph").type).isEqualTo(DatabaseType.FALKORDB)
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a datasource with no type fails startup and says what is missing`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources.neo.collection-sort-strategy=APOC_SORT_MAPS")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootMessage(context.startupFailure)).isEqualTo(
                    "Drivine datasource 'neo' has no type: set database.datasources.neo.type " +
                        "(one of ${DatabaseType.entries.joinToString()}). " +
                        "Properties found: collection-sort-strategy."
                )
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a datasource with no type beside a full one fails, naming only the partial one`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues(*neo, "database.datasources.graph.host=localhost", "database.datasources.graph.port=6379")
            .run { context ->
                assertThat(context).hasFailed()
                val message = rootMessage(context.startupFailure)
                assertThat(message).startsWith("Drivine datasource 'graph' has no type: set database.datasources.graph.type ")
                assertThat(message).endsWith("Properties found: host, port.")
                assertThat(message).doesNotContain("'neo'")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a datasource from environment variables with no type fails and names the variables`(path: String, runner: ApplicationContextRunner) {
        runner.withEnvironmentVariables(mapOf("DATABASE_DATASOURCES_NEO_COLLECTION_SORT_STRATEGY" to "APOC_SORT_MAPS"))
            .run { context ->
                assertThat(context).hasFailed()
                val message = rootMessage(context.startupFailure)
                assertThat(message).startsWith("Drivine datasource 'neo' has no type: set database.datasources.neo.type ")
                assertThat(message).endsWith("Properties found: DATABASE_DATASOURCES_NEO_COLLECTION_SORT_STRATEGY.")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `environment variables that complete a datasource from properties make it whole`(path: String, runner: ApplicationContextRunner) {
        // How CI runs the tests: the yaml names the datasource, a variable adds a property to it.
        runner.withPropertyValues(*neo)
            .withEnvironmentVariables(mapOf("DATABASE_DATASOURCES_NEO_COLLECTION_SORT_STRATEGY" to "APOC_SORT_MAPS"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(DataSourceMap::class.java).dataSources).containsOnlyKeys("neo")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a type given under another spelling of the name counts`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources.my-graph.host=localhost")
            .withEnvironmentVariables(mapOf("DATABASE_DATASOURCES_MYGRAPH_TYPE" to "FALKORDB"))
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a type given in bracket notation counts`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources[graph].type=FALKORDB", "database.datasources.graph.host=localhost")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(DataSourceMap::class.java).dataSources).containsOnlyKeys("graph")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a datasource whose name has a dot is told to set its type in bracket notation`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources[my.graph].host=localhost")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootMessage(context.startupFailure))
                    .startsWith("Drivine datasource 'my.graph' has no type: set database.datasources[my.graph].type ")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `an empty type is no type`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources.neo.type=", "database.datasources.neo.host=localhost")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootMessage(context.startupFailure))
                    .startsWith("Drivine datasource 'neo' has no type: set database.datasources.neo.type ")
                    .endsWith("Properties found: type, host.")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `an empty type that overrides a type is no type`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues(*neo)
            .withEnvironmentVariables(mapOf("DATABASE_DATASOURCES_NEO_TYPE" to ""))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootMessage(context.startupFailure))
                    .startsWith("Drivine datasource 'neo' has no type: set database.datasources.neo.type ")
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `a type whose placeholder resolves empty is no type`(path: String, runner: ApplicationContextRunner) {
        runner.withPropertyValues("database.datasources.neo.type=\${NEO_TYPE:}", "database.datasources.neo.host=localhost")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootMessage(context.startupFailure))
                    .startsWith("Drivine datasource 'neo' has no type: set database.datasources.neo.type ")
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableDrivine
    @EnableDrivinePropertiesConfig
    class EnableDrivinePropertiesApp

    private fun rootMessage(failure: Throwable?): String? {
        var cause = failure
        while (cause?.cause != null) cause = cause.cause
        return cause?.message
    }

    companion object {
        @JvmStatic
        fun paths(): Stream<Arguments> = Stream.of(
            Arguments.of("starter", isolatedRunner().withConfiguration(AutoConfigurations.of(DrivineAutoConfiguration::class.java))),
            Arguments.of("@EnableDrivinePropertiesConfig", isolatedRunner().withUserConfiguration(EnableDrivinePropertiesApp::class.java)),
        )
    }
}
