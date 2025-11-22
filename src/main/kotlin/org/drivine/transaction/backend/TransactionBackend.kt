package org.drivine.transaction.backend

/**
 * Abstraction for database-specific transaction operations.
 * This allows Drivine to support multiple graph databases (Neo4j, ArangoDB, etc.)
 * and even relational databases like PostgreSQL.
 */
interface TransactionBackend {

    /**
     * The name/key of the database this backend manages
     */
    val databaseName: String

    /**
     * Opens a new session for the given database.
     * Returns a session holder that can be used to begin transactions and execute queries.
     */
    fun openSession(): SessionHolder

    /**
     * Begins a transaction on the given session.
     * Returns a transaction holder that can be committed or rolled back.
     */
    fun beginTransaction(session: SessionHolder): TransactionHolder

    /**
     * Commits the transaction.
     */
    fun commit(transaction: TransactionHolder)

    /**
     * Rolls back the transaction.
     */
    fun rollback(transaction: TransactionHolder)

    /**
     * Closes the session and releases resources.
     */
    fun closeSession(session: SessionHolder)

    /**
     * Returns the native session object for use by connections.
     * This is database-specific (e.g., Neo4j Session, ArangoDB Collection, etc.)
     */
    fun getNativeSession(session: SessionHolder): Any

    /**
     * Returns the native transaction object for use by connections.
     * This is database-specific (e.g., Neo4j Transaction)
     */
    fun getNativeTransaction(transaction: TransactionHolder): Any?
}