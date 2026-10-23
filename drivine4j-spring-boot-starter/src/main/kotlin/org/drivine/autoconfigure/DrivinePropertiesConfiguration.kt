package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Configuration imported by @EnableDrivinePropertiesConfig and by [DrivineAutoConfiguration].
 * Creates a DataSourceMap bean from DrivineProperties.
 *
 * Note: If using @EnableDrivineTestConfig, that configuration's DataSourceMap
 * will take precedence (marked as @Primary).
 *
 * If you define your own DataSourceMap bean, this one will be skipped
 * (due to @ConditionalOnMissingBean).
 */
@Configuration
class DrivinePropertiesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun dataSourceMap(properties: ObjectProvider<DrivineProperties>, environment: Environment): DataSourceMap {
        // Before the properties bind: binding drops a datasource with no type, or fails on it with
        // a Kotlin null check that names neither the datasource nor the property.
        requireEveryDatasourceTyped(environment)
        return DataSourceMap(properties.getObject().datasources)
    }

    /**
     * Fails, naming the datasource and what was given for it, when a name under
     * `database.datasources` has properties but no `type`. `type` is the one `ConnectionProperties`
     * field without a default; without it Spring drops the entry without a word (when nothing else
     * bound either), and the datasource would surface only on first use, as "no database is
     * registered".
     */
    private fun requireEveryDatasourceTyped(environment: Environment) {
        val (name, found) = propertiesByDatasource(environment).entries
            .firstOrNull { (_, properties) -> properties.none { it.isType } } ?: return
        throw IllegalStateException(
            "Drivine datasource '$name' has no type: set ${if ('.' in name) "$DATASOURCES[$name]" else "$DATASOURCES.$name"}.type " +
                "(one of ${DatabaseType.entries.joinToString()}). " +
                "Properties found: ${found.joinToString { it.shown }}."
        )
    }

    /** A property given for a datasource, and how to show it: as written, or as its environment variable. */
    private data class Found(val isType: Boolean, val shown: String)

    /** Each name under `database.datasources`, from every property source, with the properties given for it. */
    private fun propertiesByDatasource(environment: Environment): Map<String, Set<Found>> {
        val nameIndex = DATASOURCES_NAME.numberOfElements
        // Keyed as relaxed binding compares names (`my-graph`, `myGraph` and MYGRAPH are one), shown as first written.
        val names = linkedMapOf<String, String>()
        val found = linkedMapOf<String, MutableSet<Found>>()
        // Sources come highest-priority first, and only the first `type` for a datasource binds.
        val typeSeen = mutableSetOf<String>()
        ConfigurationPropertySources.get(environment)
            .filterIsInstance<IterableConfigurationPropertySource>()
            .forEach { source ->
                val fromEnvironment = source.underlyingSource is SystemEnvironmentPropertySource
                source.filter { DATASOURCES_NAME.isAncestorOf(it) && it.numberOfElements > nameIndex + 1 }
                    .forEach { property ->
                        val key = property.getElement(nameIndex, Form.UNIFORM)
                        names.putIfAbsent(key, property.getElement(nameIndex, Form.ORIGINAL))
                        // An empty `type:`, or one whose placeholder resolves empty, binds as null, so it is no type either.
                        val isType = property.numberOfElements == nameIndex + 2 &&
                            property.getElement(nameIndex + 1, Form.UNIFORM) == "type" &&
                            typeSeen.add(key) &&
                            source.getConfigurationProperty(property)?.value?.toString()
                                ?.let(environment::resolvePlaceholders)?.isNotBlank() == true
                        val shown = if (fromEnvironment) {
                            property.toString().uppercase().replace('.', '_').replace("-", "")
                        } else {
                            (nameIndex + 1 until property.numberOfElements).joinToString(".") { property.getElement(it, Form.ORIGINAL) }
                        }
                        found.getOrPut(key) { linkedSetOf() }.add(Found(isType, shown))
                    }
            }
        return found.mapKeys { (key, _) -> names.getValue(key) }
    }

    private companion object {
        const val DATASOURCES = "database.datasources"
        val DATASOURCES_NAME: ConfigurationPropertyName = ConfigurationPropertyName.of(DATASOURCES)
    }
}
