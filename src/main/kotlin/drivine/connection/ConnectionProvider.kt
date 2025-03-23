package drivine.connection

interface ConnectionProvider {
    /**
     * Name of the database for which connections will be provided.
     */
    val name: String

    /**
     * Type of database for which connections will be provided.
     */
    val type: DatabaseType

    /**
     * Connect to the database and return the connection.
     */
    fun connect(): Connection

    /**
     * End the connection and perform cleanup.
     */
    fun end()
}