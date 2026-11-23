package org.drivine.migration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Code that opted in to the store layout migration while it was experimental still compiles and runs, its opt-in left
 * over: [ExperimentalStoreLayoutMigration] is deprecated, and no longer required (liberation-data/drivine4j#832).
 */
@Suppress("DEPRECATION", "OPT_IN_ARGUMENT_IS_NOT_MARKER")
@OptIn(ExperimentalStoreLayoutMigration::class)
class StoreLayoutMigrationOptInTest {

    @Test
    fun `an opt-in left over from the experimental migration still compiles`() {
        assertEquals(Kind.DATE, Declaration("Person", "born", Kind.DATE).kind)
    }
}
