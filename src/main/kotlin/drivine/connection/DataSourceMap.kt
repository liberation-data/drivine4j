package drivine.connection

data class DataSourceMap(
    val dataSources: Map<String, ConnectionProperties> = mutableMapOf()
)
