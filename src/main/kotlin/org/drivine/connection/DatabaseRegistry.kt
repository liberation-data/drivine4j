package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry

/**
 * Registry of database connection providers.
 * Must be configured as a bean in your Spring configuration.
 */
class DatabaseRegistry(
    dataSourceMap: DataSourceMap,
    val subtypeRegistry: SubtypeRegistry = SubtypeRegistry()
) {

    private val providers: MutableMap<String, ConnectionProvider> = mutableMapOf()

    init {
        dataSourceMap.dataSources.forEach { (key, value) ->
            withProperties(value).register(key)
        }
    }

    fun builder(): ConnectionProviderBuilder {
        return ConnectionProviderBuilder(this, subtypeRegistry)
    }

    fun connectionProvider(name: String = "default"): ConnectionProvider? {
        return if (name == "default") providers.values.firstOrNull() else providers[name]
    }

    /** The names of all registered datasources, in registration order. */
    val databaseNames: Set<String>
        get() = providers.keys.toSet()

    /**
     * Resolves a database name to an actual registered name, honouring the `"default"` alias
     * (the first-registered datasource). Returns null if no matching datasource is registered.
     */
    fun resolveDatabaseName(name: String): String? =
        if (name == "default") providers.keys.firstOrNull() else name.takeIf { providers.containsKey(it) }

    fun register(connectionProvider: ConnectionProvider) {
        providers[connectionProvider.name] = connectionProvider
    }

    fun withProperties(properties: ConnectionProperties, name: String = "default"): ConnectionProviderBuilder {
        return this.builder().withProperties(properties)
    }

}
