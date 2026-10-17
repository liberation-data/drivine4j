package org.drivine

/**
 * A batch of more than one statement was refused before any of it ran, because the store cannot undo
 * a statement it has run and is configured to refuse what it cannot apply whole: FalkorDB in
 * [org.drivine.connection.FalkorDbTransactionMode.STRICT]. Nothing was written.
 *
 * What is one statement is said at [org.drivine.manager.StatelessGraphObjectManager.saveAll] and
 * [org.drivine.manager.StatelessGraphObjectManager.patchAll]. To go on, split the batch into calls
 * that are each one statement, or save its objects one at a time: each save is one statement.
 *
 * It is an [UnsupportedOperationException], as the refusal was before it had a name of its own.
 */
class NonAtomicBatchException(
    /** How many statements the batch would have run. */
    val statements: Int,
    cause: Throwable? = null,
) : UnsupportedOperationException(
    """
    A batch of $statements statements was refused: this store cannot undo a statement it has run, so the
    batch could not be applied whole, and nothing was written. Split it into calls that are each one
    statement, or save its objects one at a time.
    """.trimIndent().replace("\n", " "),
    cause,
)
