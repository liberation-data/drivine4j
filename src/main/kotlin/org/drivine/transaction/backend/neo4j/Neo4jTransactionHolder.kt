package org.drivine.transaction.backend.neo4j

import org.drivine.transaction.backend.TransactionHolder
import org.neo4j.driver.Transaction
import java.util.UUID

/**
 * Neo4j-specific implementation of TransactionHolder.
 * Wraps a Neo4j driver Transaction.
 */
class Neo4jTransactionHolder(
    val transaction: Transaction
) : TransactionHolder {
    override val transactionId: String = UUID.randomUUID().toString().substring(0, 8)
}