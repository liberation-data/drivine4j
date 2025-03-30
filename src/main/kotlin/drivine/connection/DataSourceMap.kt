package drivine.connection

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "database")
data class DataSourceMap(
    val dataSources: Map<String, ConnectionProperties> = mutableMapOf()
)