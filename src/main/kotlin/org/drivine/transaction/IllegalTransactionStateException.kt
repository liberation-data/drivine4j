package org.drivine.transaction

import org.drivine.DrivineException

/**
 * A block was refused before it ran, because the transaction the thread is in, or not in, is not
 * what its [Propagation] asks for: `MANDATORY` with no transaction, `NEVER` in one, or `NESTED`.
 */
class IllegalTransactionStateException(message: String) : DrivineException(message)
