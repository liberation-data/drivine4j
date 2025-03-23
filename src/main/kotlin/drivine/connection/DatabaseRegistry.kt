package drivine.connection

import java.util.Properties

class DatabaseRegistry private constructor() {
    private val providers: MutableMap<String, ConnectionProvider> = mutableMapOf()

    companion object {
        @Volatile
        private var instance: DatabaseRegistry? = null

        fun resolveFromProperties(properties: Properties, name: String? = null): ConnectionProvider {
            return getInstance()
                .builder()
                .withProperties(ConnectionProperties.fromProperties(properties, name))
                .register(name ?: "default")
        }

        fun getInstance(): DatabaseRegistry {
            return instance ?: synchronized(this) {
                instance ?: DatabaseRegistry().also { instance = it }
            }
        }

        fun tearDown() {
            instance = null
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
}
