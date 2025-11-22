package org.drivine.transaction.backend

/**
 * Holds a database session. The actual session implementation is database-specific.
 *
 * This interface can be extended in the future to add common session operations
 * like query execution, session configuration, etc.
 */
interface SessionHolder {
    /**
     * Unique identifier for this session
     */
    val sessionId: String
}