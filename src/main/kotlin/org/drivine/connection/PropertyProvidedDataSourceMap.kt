package org.drivine.connection

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "database")
data class PropertyProvidedDataSourceMap(
    var dataSources: Map<String, ConnectionProperties> = mutableMapOf()
)


