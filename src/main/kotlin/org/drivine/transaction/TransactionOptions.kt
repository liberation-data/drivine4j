package org.drivine.transaction

data class TransactionOptions(
    val rollback: Boolean = false,
    val propagation: Propagation? = null
)
