package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.view.*
import org.drivine.sample.view.loadAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for non-nullable relationship filtering.
 *
 * When a @GraphView has a non-nullable, non-collection relationship,
 * the generated query should include a WHERE EXISTS clause to filter
 * out root nodes that don't have the required relationship.
 *
 * This prevents MissingKotlinParameterException when deserializing.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class RequiredRelationshipTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    private lateinit var guideWithWebUser: UUID
    private lateinit var guideWithAnonymous: UUID
    private lateinit var guideWithRegistered: UUID
    private lateinit var guideWithoutWebUser: UUID

    @BeforeEach
    fun setupTestData() {
        // Clean up previous test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.testMarker = 'required-rel-test' DETACH DELETE n")
        )

        guideWithWebUser = UUID.randomUUID()
        guideWithAnonymous = UUID.randomUUID()
        guideWithRegistered = UUID.randomUUID()
        guideWithoutWebUser = UUID.randomUUID()

        // Create test data:
        // 1. GuideUser WITH a RegisteredWebUser (using concrete subtype since WebUser is sealed)
        // 2. GuideUser WITH an AnonymousWebUser
        // 3. GuideUser WITH a RegisteredWebUser
        // 4. GuideUser WITHOUT any WebUser
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    // Guide with Registered WebUser (first test case - uses concrete subtype)
                    CREATE (g1:GuideUser {uuid: '$guideWithWebUser', guideProgress: 10, testMarker: 'required-rel-test'})
                    CREATE (w1:WebUser:Registered {uuid: randomUUID(), displayName: 'Basic User', email: 'basic@example.com', testMarker: 'required-rel-test'})
                    CREATE (g1)-[:IS_WEB_USER]->(w1)

                    // Guide with Anonymous WebUser
                    CREATE (g2:GuideUser {uuid: '$guideWithAnonymous', guideProgress: 20, testMarker: 'required-rel-test'})
                    CREATE (w2:WebUser:Anonymous {uuid: randomUUID(), displayName: 'Anon User', anonymousToken: 'token123', testMarker: 'required-rel-test'})
                    CREATE (g2)-[:IS_WEB_USER]->(w2)

                    // Guide with Registered WebUser
                    CREATE (g3:GuideUser {uuid: '$guideWithRegistered', guideProgress: 30, testMarker: 'required-rel-test'})
                    CREATE (w3:WebUser:Registered {uuid: randomUUID(), displayName: 'Registered User', email: 'user@example.com', testMarker: 'required-rel-test'})
                    CREATE (g3)-[:IS_WEB_USER]->(w3)

                    // Guide WITHOUT WebUser
                    CREATE (g4:GuideUser {uuid: '$guideWithoutWebUser', guideProgress: 0, testMarker: 'required-rel-test'})
                """.trimIndent())
        )
    }

    // ==================== Optional Relationship Tests ====================

    @Test
    fun `optional relationship returns all root nodes including those without relationship`() {
        // GuideUserWithOptionalWebUser has nullable webUser
        // Should return ALL 4 GuideUsers
        // Using cleaner DSL syntax: `core.guideProgress` instead of `query.core.guideProgress`
        val results = graphObjectManager.loadAll<GuideUserWithOptionalWebUser> {
            where {
                core.guideProgress gte 0  // Match all test data
            }
        }

        assertEquals(4, results.size, "Should return all 4 GuideUsers")

        // Find the one without WebUser
        val withoutWebUser = results.find { it.core.uuid == guideWithoutWebUser }
        assertNotNull(withoutWebUser, "Should include GuideUser without WebUser")
        assertNull(withoutWebUser.webUser, "WebUser should be null for guide without relationship")

        // Verify others have WebUser
        val withWebUser = results.filter { it.webUser != null }
        assertEquals(3, withWebUser.size, "3 GuideUsers should have WebUser")
    }

    // ==================== Required Relationship Tests ====================

    @Test
    fun `required relationship filters out root nodes without the relationship`() {
        // GuideUserWithRequiredWebUser has NON-nullable webUser
        // Should only return the 3 GuideUsers that have a WebUser
        val results = graphObjectManager.loadAll<GuideUserWithRequiredWebUser> { }

        assertEquals(3, results.size, "Should only return 3 GuideUsers with WebUser")

        // Verify all returned results have non-null webUser
        results.forEach { result ->
            assertNotNull(result.webUser, "All returned results should have webUser")
        }

        // Verify the one without WebUser is NOT returned
        val uuids = results.map { it.core.uuid }
        assertTrue(guideWithoutWebUser !in uuids, "GuideUser without WebUser should NOT be returned")
    }

    @Test
    fun `required relationship with specific labels filters correctly`() {
        // AnonymousGuideUser requires an AnonymousWebUser specifically
        // Should only return the 1 GuideUser with Anonymous WebUser
        val results = graphObjectManager.loadAll<AnonymousGuideUser> { }

        assertEquals(1, results.size, "Should only return 1 GuideUser with Anonymous WebUser")
        assertEquals(guideWithAnonymous, results[0].core.uuid)
        assertEquals("Anon User", results[0].webUser.displayName)
        assertEquals("token123", results[0].webUser.anonymousToken)
    }

    @Test
    fun `required relationship combined with DSL where clause`() {
        // Combine required relationship filtering with DSL conditions
        // Using cleaner DSL syntax: `core.guideProgress` instead of `query.core.guideProgress`
        val results = graphObjectManager.loadAll<GuideUserWithRequiredWebUser> {
            where {
                core.guideProgress gte 15
            }
        }

        // Should return guides with progress >= 15 that also have a WebUser
        // That's guideWithAnonymous (20) and guideWithRegistered (30)
        assertEquals(2, results.size)
        assertTrue(results.all { it.core.guideProgress >= 15 })
    }

    @Test
    fun `required relationship prevents null pointer exceptions`() {
        // This test verifies that we don't get MissingKotlinParameterException
        // when loading GuideUserWithRequiredWebUser
        val results = graphObjectManager.loadAll<GuideUserWithRequiredWebUser> { }

        // If we got here without exception, the WHERE EXISTS worked
        assertTrue(results.isNotEmpty())

        // All webUsers should be non-null and accessible
        results.forEach { result ->
            // This would throw if webUser was somehow null
            val displayName = result.webUser.displayName
            assertTrue(displayName.isNotEmpty())
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `empty result when no matching relationships exist`() {
        // Delete all WebUser nodes
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (w:WebUser {testMarker: 'required-rel-test'}) DETACH DELETE w")
        )

        // Now all GuideUsers have no WebUser relationship
        val results = graphObjectManager.loadAll<GuideUserWithRequiredWebUser> { }

        assertEquals(0, results.size, "Should return empty when no matching relationships")
    }
}