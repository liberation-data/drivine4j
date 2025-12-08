package org.drivine.connection

/**
 * Registry of database connection providers.
 * Must be configured as a bean in your Spring configuration.
 */
class DatabaseRegistry(dataSourceMap: DataSourceMap) {

    private val providers: MutableMap<String, ConnectionProvider> = mutableMapOf()

    init {
        dataSourceMap.dataSources.forEach { (key, value) ->
            withProperties(value).register(key)
        }
    }

    fun builder(): ConnectionProviderBuilder {
        return ConnectionProviderBuilder(this)
    }

    fun connectionProvider(name: String = "default"): ConnectionProvider? {
        return if (name == "default") providers.values.firstOrNull() else providers[name]
    }

    fun register(connectionProvider: ConnectionProvider) {
        providers[connectionProvider.name] = connectionProvider
    }

    fun withProperties(properties: ConnectionProperties, name: String = "default"): ConnectionProviderBuilder {
        return this.builder().withProperties(properties)
    }

}
