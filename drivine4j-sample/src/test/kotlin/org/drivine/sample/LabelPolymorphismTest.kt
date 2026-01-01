package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.AnonymousWebUser
import org.drivine.sample.fragment.RegisteredWebUser
import org.drivine.sample.view.GuideUserWithOptionalWebUser
import org.drivine.sample.view.GuideUserWithPolymorphicWebUser
import org.drivine.sample.view.loadAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for label-based polymorphic deserialization.
 *
 * When nodes have multiple labels (e.g., [:WebUser:Anonymous]),
 * the correct subtype should be resolved based on the composite label key.
 *
 * This relies on automatic subtype registration for sealed classes.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class LabelPolymorphismTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    private lateinit var guideWithAnonymous: UUID
    private lateinit var guideWithRegistered: UUID
    private lateinit var anonymousUserId: UUID
    private lateinit var registeredUserId: UUID

    @BeforeEach
    fun setupTestData() {
        // Clean up previous test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.testMarker = 'polymorphism-test' DETACH DELETE n")
        )

        guideWithAnonymous = UUID.randomUUID()
        guideWithRegistered = UUID.randomUUID()
        anonymousUserId = UUID.randomUUID()
        registeredUserId = UUID.randomUUID()

        // Create test data with different WebUser subtypes
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    // Guide with Anonymous WebUser (has both WebUser and Anonymous labels)
                    CREATE (g1:GuideUser {uuid: '$guideWithAnonymous', guideProgress: 10, testMarker: 'polymorphism-test'})
                    CREATE (w1:WebUser:Anonymous {uuid: '$anonymousUserId', displayName: 'Anon User', anonymousToken: 'token-abc', testMarker: 'polymorphism-test'})
                    CREATE (g1)-[:IS_WEB_USER]->(w1)

                    // Guide with Registered WebUser (has both WebUser and Registered labels)
                    CREATE (g2:GuideUser {uuid: '$guideWithRegistered', guideProgress: 20, testMarker: 'polymorphism-test'})
                    CREATE (w2:WebUser:Registered {uuid: '$registeredUserId', displayName: 'Registered User', email: 'test@example.com', testMarker: 'polymorphism-test'})
                    CREATE (g2)-[:IS_WEB_USER]->(w2)
                """.trimIndent())
        )
    }

    // ==================== Polymorphic Deserialization Tests ====================

    @Test
    fun `polymorphic relationship deserializes to correct subtype based on labels`() {
        // Load views with polymorphic WebUser relationship
        val results = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> { }

        assertEquals(2, results.size, "Should return 2 GuideUsers with WebUsers")

        // Find the one with Anonymous WebUser
        val withAnonymous = results.find { it.core.uuid == guideWithAnonymous }
        assertNotNull(withAnonymous)
        assertIs<AnonymousWebUser>(withAnonymous.webUser, "Should deserialize to AnonymousWebUser based on labels")
        assertEquals("token-abc", (withAnonymous.webUser as AnonymousWebUser).anonymousToken)

        // Find the one with Registered WebUser
        val withRegistered = results.find { it.core.uuid == guideWithRegistered }
        assertNotNull(withRegistered)
        assertIs<RegisteredWebUser>(withRegistered.webUser, "Should deserialize to RegisteredWebUser based on labels")
        assertEquals("test@example.com", (withRegistered.webUser as RegisteredWebUser).email)
    }

    @Test
    fun `polymorphic deserialization works with optional relationship view`() {
        // GuideUserWithOptionalWebUser also has polymorphic WebUser
        val results = graphObjectManager.loadAll<GuideUserWithOptionalWebUser> {
            where {
                query.core.guideProgress gte 0
            }
        }

        // Should return our 2 test users (might be more from other tests)
        val testResults = results.filter {
            it.core.uuid == guideWithAnonymous || it.core.uuid == guideWithRegistered
        }
        assertEquals(2, testResults.size)

        // Verify polymorphic deserialization
        testResults.forEach { result ->
            when (result.core.uuid) {
                guideWithAnonymous -> {
                    assertIs<AnonymousWebUser>(result.webUser)
                }
                guideWithRegistered -> {
                    assertIs<RegisteredWebUser>(result.webUser)
                }
            }
        }
    }

    @Test
    fun `composite label key matching uses sorted labels`() {
        // This test verifies that the composite key is correctly built from sorted labels
        // The order of labels in Neo4j shouldn't matter - [WebUser, Anonymous] and [Anonymous, WebUser]
        // should both resolve to AnonymousWebUser

        val results = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> { }

        val anonymousResult = results.find { it.core.uuid == guideWithAnonymous }
        assertNotNull(anonymousResult)

        // The subtype should be resolved regardless of label order in Neo4j
        val webUser = anonymousResult.webUser
        assertIs<AnonymousWebUser>(webUser)
        assertEquals(anonymousUserId, webUser.uuid)
    }

    @Test
    fun `subtype-specific properties are correctly populated`() {
        val results = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> { }

        results.forEach { result ->
            val webUser = result.webUser
            assertNotNull(webUser)

            // Common properties should always be populated
            assertTrue(webUser.displayName.isNotEmpty())
            assertNotNull(webUser.uuid)

            // Subtype-specific properties should be populated for the correct type
            when (webUser) {
                is AnonymousWebUser -> {
                    assertEquals("token-abc", webUser.anonymousToken)
                }
                is RegisteredWebUser -> {
                    assertEquals("test@example.com", webUser.email)
                }
            }
        }
    }
}