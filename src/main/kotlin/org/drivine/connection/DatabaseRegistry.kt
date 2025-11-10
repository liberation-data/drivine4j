package org.drivine.connection

import org.springframework.stereotype.Component

@Component
class DatabaseRegistry {

    constructor(dataSourceMap: DataSourceMap) {
        dataSourceMap.dataSources.forEach { (key, value) ->
            withProperties(value).register(key)
        }
    }

    private val providers: MutableMap<String, ConnectionProvider> = mutableMapOf()

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
