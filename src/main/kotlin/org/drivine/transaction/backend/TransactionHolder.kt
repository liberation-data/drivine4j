package org.drivine.transaction.backend

/**
 * Holds a database transaction. The actual transaction implementation is database-specific.
 *
 * This interface can be extended in the future to add common transaction operations
 * like savepoints, transaction metadata, isolation levels, etc.
 */
interface TransactionHolder {
    /**
     * Unique identifier for this transaction
     */
    val transactionId: String
}