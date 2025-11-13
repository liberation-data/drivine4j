package org.drivine.test

import org.drivine.transaction.Transaction
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.TransactionOptions
import org.slf4j.LoggerFactory
import org.springframework.test.annotation.Rollback
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * Test execution listener that manages transactions for Drivine tests.
 *
 * This listener:
 * - Starts a transaction before each test method
 * - Respects the @Rollback annotation (default is true)
 * - Commits or rolls back the transaction after the test completes
 *
 * Usage:
 * ```
 * @SpringBootTest
 * @TestExecutionListeners(
 *     listeners = [DrivineTestTransactionListener::class],
 *     mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
 * )
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
class TestTransactionListener : TestExecutionListener {
    private val logger = LoggerFactory.getLogger(TestTransactionListener::class.java)

    override fun beforeTestMethod(testContext: TestContext) {
        val contextHolder = getContextHolder(testContext)
        val testMethod = testContext.testMethod

        // Determine if we should rollback (default is true, just like Spring)
        val rollbackAnnotation = testMethod.getAnnotation(Rollback::class.java)
            ?: testMethod.declaringClass.getAnnotation(Rollback::class.java)
        val shouldRollback = rollbackAnnotation?.value ?: true

        // Store the rollback flag in thread-local storage
        TestTransactionContext.setRollback(shouldRollback)

        // Start a transaction for the test
        val options = TransactionOptions(rollback = shouldRollback)
        val transaction = Transaction(options, contextHolder)

        logger.debug("Started test transaction ${transaction.id} for ${testMethod.name} (rollback=$shouldRollback)")

        // Push a context so the transaction is active
        transaction.pushContext("test:${testMethod.name}")
    }

    override fun afterTestMethod(testContext: TestContext) {
        val contextHolder = getContextHolder(testContext)
        val transaction = contextHolder.currentTransaction
        val testMethod = testContext.testMethod

        if (transaction != null) {
            try {
                // Pop the context, which will trigger commit or rollback
                transaction.popContext(isRoot = true)
                logger.debug("Completed test transaction ${transaction.id} for ${testMethod.name}")
            } catch (e: Exception) {
                logger.error("Error completing test transaction ${transaction.id}", e)
                transaction.popContextWithError(e, isRoot = true)
            }
        }

        // Clean up thread-local storage
        TestTransactionContext.clear()
    }

    override fun afterTestExecution(testContext: TestContext) {
        // If the test threw an exception, ensure we rollback
        val exception = testContext.testException
        if (exception != null) {
            val contextHolder = getContextHolder(testContext)
            val transaction = contextHolder.currentTransaction

            if (transaction != null && transaction.callStack.isNotEmpty()) {
                logger.debug("Test threw exception, rolling back transaction ${transaction.id}")
                // Convert Throwable to Exception for the API
                val ex = if (exception is Exception) exception else Exception(exception)
                transaction.popContextWithError(ex, isRoot = true)
                TestTransactionContext.clear()
            }
        }
    }

    private fun getContextHolder(testContext: TestContext): TransactionContextHolder {
        return testContext.applicationContext.getBean(TransactionContextHolder::class.java)
    }
}
