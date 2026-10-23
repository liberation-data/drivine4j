package org.drivine.transaction

/**
 * How a block run by [Transactions.transaction] takes the transaction the thread is in: join it, set it
 * aside, or refuse. The names, and what they mean, are Spring's. All but `NESTED` are supported.
 */
enum class Propagation {
    /**
     * Support a current transaction, throw an exception if none exists.
     */
    MANDATORY,
    /**
     * Execute within a nested transaction if a current transaction exists, behave like `REQUIRED` otherwise.
     * Not supported: [Transactions.transaction] refuses it, with a transaction or without one.
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
