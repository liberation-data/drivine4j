package org.drivine.transaction

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.TestAppContext
import java.util.*

/**
 * Example tests demonstrating Spring's @Transactional and @Rollback support with Drivine.
 *
 * By default, all @Transactional test methods will rollback their transactions (just like Spring).
 * Use @Rollback(false) to commit changes permanently.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
class TransactionRollbackTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @Test
    @Rollback // This is the default - transaction will rollback
    fun testWithRollback() {
        // This data will be rolled back after the test
        val uuid = UUID.randomUUID().toString()
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (p:Person {uuid: ${'$'}uuid, name: 'Test Person', age: 25})
            """.trimIndent())
            .bind(mapOf("uuid" to uuid)))

        // Verify it was created
        val result = manager.query(QuerySpecification
            .withStatement("MATCH (p:Person {uuid: ${'$'}uuid}) RETURN count(p) as count")
            .bind(mapOf("uuid" to uuid)))

        assert(result.isNotEmpty()) { "Person should exist during test" }
        println("Created person in transaction - will be rolled back")
    }

    @Test
    @Rollback(false) // Explicitly commit this transaction
    fun testWithCommit() {
        // This data will be committed to the database
        val uuid = "committed-person-${System.currentTimeMillis()}"
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (p:Person {uuid: ${'$'}uuid, name: 'Committed Person', age: 30, committed: true})
            """.trimIndent())
            .bind(mapOf("uuid" to uuid)))

        println("Created person with commit - will persist after test")
    }

    @Test
    fun testDefaultRollback() {
        // Without @Rollback annotation, defaults to rollback=true
        val uuid = UUID.randomUUID().toString()
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (p:Person {uuid: ${'$'}uuid, name: 'Another Test Person', age: 35})
            """.trimIndent())
            .bind(mapOf("uuid" to uuid)))

        println("Created person without explicit @Rollback - will be rolled back by default")
    }

    @Test
    @Rollback(false)
    fun cleanupCommittedData() {
        // Clean up any committed test data
        val result = manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person {committed: true}) DETACH DELETE p"))
        println("Cleaned up committed test data: $result")
    }
}

/**
 * Example demonstrating class-level @Rollback annotation.
 * All tests in this class will commit by default unless overridden at method level.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(false) // Class-level: commit by default
class ClassLevelRollbackTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @Test
    fun testInheritsClassLevelCommit() {
        // Inherits @Rollback(false) from class level - will commit
        val uuid = "class-level-commit-${System.currentTimeMillis()}"
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (p:Person {uuid: ${'$'}uuid, name: 'Class Level Commit', classLevel: true})
            """.trimIndent())
            .bind(mapOf("uuid" to uuid)))

        println("Test inherits class-level @Rollback(false) - will commit")
    }

    @Test
    @Rollback(true) // Method-level override: rollback instead of commit
    fun testOverridesClassLevelWithRollback() {
        // Method annotation overrides class-level - will rollback
        val uuid = UUID.randomUUID().toString()
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (p:Person {uuid: ${'$'}uuid, name: 'Override Rollback', classLevel: true})
            """.trimIndent())
            .bind(mapOf("uuid" to uuid)))

        println("Test overrides class-level with @Rollback(true) - will rollback")
    }

    @Test
    @Rollback(false)
    fun cleanupClassLevelData() {
        // Clean up any committed test data from class-level tests
        val result = manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person {classLevel: true}) DETACH DELETE p"))
        println("Cleaned up class-level test data: $result")
    }
}
