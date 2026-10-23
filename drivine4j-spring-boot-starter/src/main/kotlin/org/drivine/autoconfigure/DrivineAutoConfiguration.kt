package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.IgnoreErrorsBindHandler
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * What the starter configures from the classpath: the beans `@EnableDrivine` and
 * `@EnableDrivinePropertiesConfig` import, once the application has a datasource for them —
 * an entry under `database.datasources`, or a [DataSourceMap] bean of its own. A context with
 * neither gets no Drivine beans.
 *
 * Every bean backs off when the application defines its own. An application that also has
 * `@EnableDrivine` gets each bean once: the configurations it imports are the ones imported here,
 * and Spring registers a configuration class once however many times it is imported.
 *
 * It runs before Spring Boot's transaction managers, so the [org.springframework.transaction.PlatformTransactionManager]
 * is Drivine's, as it is under `@EnableDrivine`. `spring.autoconfigure.exclude` turns it off.
 */
@AutoConfiguration(
    beforeName = [
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration",
    ]
)
@ConditionalOnClass(PersistenceManagerFactory::class)
@Conditional(DrivineAutoConfiguration.OnDatasource::class)
@EnableConfigurationProperties(
    DrivineProperties::class,
    DrivineSchemaProperties::class,
    DrivineQueryProperties::class,
)
@Import(
    DrivinePropertiesConfiguration::class,
    DrivineConfiguration::class,
    DrivineSchemaConfiguration::class,
)
class DrivineAutoConfiguration {

    /** Datasource properties, or a [DataSourceMap] bean. */
    internal class OnDatasource : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN) {

        @Conditional(OnDatasourceProperties::class)
        class DatasourceProperties

        @ConditionalOnBean(DataSourceMap::class)
        class DataSourceMapBean
    }

    /**
     * At least one entry under `database.datasources`, from any property source. A bare
     * `datasources:` key in YAML is an empty string, not a map: it counts as no datasource rather
     * than failing the condition, so the starter stays harmless on that classpath.
     */
    internal class OnDatasourceProperties : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val datasources = Binder.get(context.environment)
                .bind(DATASOURCES, Bindable.mapOf(String::class.java, Any::class.java), IgnoreErrorsBindHandler())
                .orElse(emptyMap())
            return if (datasources.isEmpty()) {
                ConditionOutcome.noMatch("no $DATASOURCES properties")
            } else {
                ConditionOutcome.match("$DATASOURCES ${datasources.keys}")
            }
        }
    }

    private companion object {
        const val DATASOURCES = "database.datasources"
    }
}
