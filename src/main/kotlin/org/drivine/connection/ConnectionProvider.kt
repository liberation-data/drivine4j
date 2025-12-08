package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry

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
     * SubtypeRegistry for polymorphic deserialization.
     * Can be null if no subtypes are registered.
     */
    val subtypeRegistry: SubtypeRegistry?

    /**
     * Connect to the database and return the connection.
     */
    fun connect(): Connection

    /**
     * End the connection and perform cleanup.
     */
    fun end()
}
