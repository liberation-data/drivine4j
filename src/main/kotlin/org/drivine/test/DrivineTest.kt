package org.drivine.test

import org.springframework.test.context.TestExecutionListeners

/**
 * Annotation to enable Drivine transaction management in tests.
 *
 * This annotation automatically registers the TestTransactionListener which:
 * - Starts a transaction before each test method
 * - Respects the @Rollback annotation (default is true)
 * - Commits or rolls back the transaction after the test completes
 *
 * Usage:
 * ```
 * @SpringBootTest
 * @DrivineTest
 * class MyTests {
 *     @Test
 *     @Rollback(false) // Commits the transaction
 *     fun testThatCommits() { }
 *
 *     @Test // Rolls back by default
 *     fun testThatRollsBack() { }
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@TestExecutionListeners(
    listeners = [TestTransactionListener::class],
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
annotation class DrivineTest
