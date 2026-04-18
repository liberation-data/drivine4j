package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * Cleans the FalkorDB graph before each test method.
 *
 * FalkorDB transactions are passthrough — `@Rollback(true)` is a no-op.
 * This listener ensures test isolation by deleting all nodes and relationships
 * before each test.
 *
 * Activated by adding `@TestExecutionListeners` to your test class, or by
 * registering in `spring.factories` / `META-INF/spring/org.springframework.test.context.TestExecutionListener`.
 */
class FalkorDbCleanupListener : TestExecutionListener {

    private val logger = LoggerFactory.getLogger(FalkorDbCleanupListener::class.java)

    override fun beforeTestMethod(testContext: TestContext) {
        try {
            val pm = testContext.applicationContext.getBean(PersistenceManager::class.java)
            if (pm.type == DatabaseType.FALKORDB) {
                logger.debug("FalkorDB cleanup: deleting all nodes before {}", testContext.testMethod.name)
                pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
            }
        } catch (e: Exception) {
            logger.debug("FalkorDB cleanup skipped: {}", e.message)
        }
    }
}