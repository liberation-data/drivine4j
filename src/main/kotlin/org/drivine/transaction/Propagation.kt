package org.drivine.transaction

/**
 * Enumeration that represents transaction propagation behaviors for use with the @Transactional annotation.
 * Only 'REQUIRED' is currently supported.
 */
enum class Propagation {
    /**
     * Support a current transaction, throw an exception if none exists.
     */
    MANDATORY,
    /**
     * Execute within a nested transaction if a current transaction exists, behave like `REQUIRED` otherwise.
     */
    NESTED,
    /**
     * Execute non-transactionally, throw an exception if a transaction exists.
     */
    NEVER,
    /**
     * Execute non-transactionally, suspend the current transaction if one exists.
     */
    NOT_SUPPORTED,
    /**
     * Support a current transaction, create a new one if none exists.
     */
    REQUIRED,
    /**
     * Create a new transaction, and suspend the current transaction if one exists.
     */
    REQUIRES_NEW,
    /**
     * Support a current transaction, execute non-transactionally if none exists.
     */
    SUPPORTS
}
