package org.drivine.transaction.backend.neo4j

import org.drivine.transaction.backend.SessionHolder
import org.neo4j.driver.Session

/**
 * Neo4j-specific implementation of SessionHolder.
 * Wraps a Neo4j driver Session.
 */
class Neo4jSessionHolder(
    val session: Session
) : SessionHolder {
    override val sessionId: String = session.toString()
}