package org.drivine

/**
 * A batch of more than one statement was refused before any of it ran, because the store cannot undo
 * a statement it has run and is configured to refuse what it cannot apply whole: FalkorDB in
 * [org.drivine.connection.FalkorDbTransactionMode.STRICT]. Nothing was written.
 *
 * A `saveAll` is one statement where it can be: fragments, of any number of labels, up to 1,000 of
 * them. Each view is a statement of its own, and so is a fragment the batch cannot write by an
 * `UNWIND`: one with a `@PropertyBag` or a `@NodeLabels` field, and on FalkorDB one with a vector.
 * A `patchAll` is one statement for up to 1,000 rows, and 100 where a `@VectorIndex` field is
 * written. To go on, split the batch into calls that are each one statement, or save such objects
 * one at a time: each save is one statement.
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
