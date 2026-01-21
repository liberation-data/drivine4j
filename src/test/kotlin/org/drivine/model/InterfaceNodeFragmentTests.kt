package org.drivine.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for supporting @NodeFragment on interfaces.
 *
 * Goal: Allow a library to define a ThreadTimeline with an interface-typed owner,
 * so consumers can plug in their own User implementation.
 */
class InterfaceNodeFragmentTests {

    // =========================================================================
    // Library code - defines the interface contract
    // =========================================================================

    /**
     * Interface that library consumers implement for their User type.
     * The @NodeFragment defines the base label used in queries.
     * @NodeId on the interface ensures save operations can detect changes.
     */
    @NodeFragment(labels = ["User"])
    interface ThreadOwner {
        @get:NodeId
        val uuid: UUID
        val displayName: String
    }

    @NodeFragment(labels = ["Thread"])
    data class ThreadData(
        @NodeId val uuid: UUID,
        val title: String
    )

    /**
     * Library's GraphView - uses the interface type for owner.
     * Query should generate: (thread)-[:OWNED_BY]->(owner:User)
     */
    @GraphView
    data class ThreadTimeline(
        @Root val thread: ThreadData,
        @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
        val owner: ThreadOwner  // Interface type!
    )

    // =========================================================================
    // Consumer code - implements the interface
    // =========================================================================

    /**
     * Consumer's concrete User implementation.
     * Has additional labels and custom fields.
     */
    @NodeFragment(labels = ["User", "GuideUser"])
    data class GuideUser(
        @NodeId override val uuid: UUID,
        override val displayName: String,
        val guideProgress: Int  // Consumer's custom field
    ) : ThreadOwner

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    fun `should extract labels from interface with NodeFragment`() {
        // Test that we can get labels from an interface
        val annotation = ThreadOwner::class.java.getAnnotation(NodeFragment::class.java)

        assertTrue(annotation != null, "Interface should have @NodeFragment annotation")
        assertTrue(annotation.labels.contains("User"), "Labels should contain 'User'")
    }

    @Test
    fun `should generate query with interface type using interface labels`() {
        // This is the key test - can we generate a query when the relationship
        // target is an interface type?

        val builder = GraphViewQueryBuilder.forView(ThreadTimeline::class)
        val query = builder.buildQuery()

        println("Generated query for ThreadTimeline:")
        println(query)

        // The query should use the interface's label (User) for the pattern
        assertTrue(
            query.contains("owner:User") || query.contains("owner: User"),
            "Query should reference owner with User label from interface"
        )

        // Should have the relationship pattern
        assertTrue(
            query.contains("-[:OWNED_BY]->"),
            "Query should have OWNED_BY relationship"
        )
    }

    @Test
    fun `implementing class should have both interface and own labels`() {
        // Verify that GuideUser has both User (from interface) and GuideUser labels
        val annotation = GuideUser::class.java.getAnnotation(NodeFragment::class.java)

        assertTrue(annotation != null, "GuideUser should have @NodeFragment")
        assertTrue(annotation.labels.contains("User"), "Should have User label")
        assertTrue(annotation.labels.contains("GuideUser"), "Should have GuideUser label")
    }
}

/**
 * End-to-end tests for interface-typed relationships.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class InterfaceNodeFragmentE2ETests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {
    // Import the test types from the unit test class
    // (In a real library, these would be in separate files)

    @BeforeEach
    fun setup() {
        // Clean up test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.createdBy = 'interface-test' DETACH DELETE n"
            )
        )

        // Register the consumer's concrete type for polymorphic deserialization
        // This is what a library consumer would do in their configuration
        persistenceManager.registerSubtype(
            InterfaceNodeFragmentTests.ThreadOwner::class.java,
            "GuideUser",
            InterfaceNodeFragmentTests.GuideUser::class.java
        )
        // Also register with composite label key
        persistenceManager.registerSubtype(
            InterfaceNodeFragmentTests.ThreadOwner::class.java,
            "User|GuideUser",  // Composite key format
            InterfaceNodeFragmentTests.GuideUser::class.java
        )
    }

    @Test
    fun `should load ThreadTimeline with interface-typed owner deserialized to concrete type`() {
        val threadUuid = UUID.randomUUID()
        val ownerUuid = UUID.randomUUID()

        // Create test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (t:Thread {
                        uuid: ${'$'}threadUuid,
                        title: 'Test Thread',
                        createdBy: 'interface-test'
                    })
                    CREATE (u:User:GuideUser {
                        uuid: ${'$'}ownerUuid,
                        displayName: 'Test User',
                        guideProgress: 42,
                        createdBy: 'interface-test'
                    })
                    CREATE (t)-[:OWNED_BY]->(u)
                """.trimIndent())
                .bind(mapOf(
                    "threadUuid" to threadUuid.toString(),
                    "ownerUuid" to ownerUuid.toString()
                ))
        )

        // Load using the library's GraphView
        val timeline = graphObjectManager.load(
            threadUuid.toString(),
            InterfaceNodeFragmentTests.ThreadTimeline::class.java
        )

        assertNotNull(timeline, "Should load ThreadTimeline")
        assertEquals("Test Thread", timeline.thread.title)
        assertNotNull(timeline.owner, "Should have owner")
        assertEquals("Test User", timeline.owner.displayName)

        // The owner should be deserialized as the concrete GuideUser type
        assertTrue(
            timeline.owner is InterfaceNodeFragmentTests.GuideUser,
            "Owner should be deserialized as GuideUser, but was ${timeline.owner::class.simpleName}"
        )

        // Access the consumer's custom field
        val guideUser = timeline.owner as InterfaceNodeFragmentTests.GuideUser
        assertEquals(42, guideUser.guideProgress, "Should have consumer's custom field")

        println("Successfully loaded ThreadTimeline with owner: ${timeline.owner}")
        println("Owner type: ${timeline.owner::class.simpleName}")
        println("Guide progress: ${guideUser.guideProgress}")
    }

    @Test
    fun `should save ThreadTimeline after loading - verifies Jackson abstract type mapping`() {
        val threadUuid = UUID.randomUUID()
        val ownerUuid = UUID.randomUUID()

        // Create test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (t:Thread {
                        uuid: ${'$'}threadUuid,
                        title: 'Original Title',
                        createdBy: 'interface-test'
                    })
                    CREATE (u:User:GuideUser {
                        uuid: ${'$'}ownerUuid,
                        displayName: 'Test User',
                        guideProgress: 42,
                        createdBy: 'interface-test'
                    })
                    CREATE (t)-[:OWNED_BY]->(u)
                """.trimIndent())
                .bind(mapOf(
                    "threadUuid" to threadUuid.toString(),
                    "ownerUuid" to ownerUuid.toString()
                ))
        )

        // Load the timeline
        val timeline = graphObjectManager.load(
            threadUuid.toString(),
            InterfaceNodeFragmentTests.ThreadTimeline::class.java
        )
        assertNotNull(timeline)

        // Modify and save - this exercises SessionManager.getSnapshot() which needs
        // Jackson to deserialize the interface type (ThreadOwner) to the concrete type (GuideUser)
        val updatedTimeline = timeline.copy(
            thread = timeline.thread.copy(title = "Updated Title")
        )

        // This should NOT throw: "Cannot construct instance of ThreadOwner (no Creators, like default constructor, exist)"
        graphObjectManager.save(updatedTimeline)

        // Verify the change was persisted
        val reloaded = graphObjectManager.load(
            threadUuid.toString(),
            InterfaceNodeFragmentTests.ThreadTimeline::class.java
        )
        assertNotNull(reloaded)
        assertEquals("Updated Title", reloaded.thread.title)
        assertEquals("Test User", reloaded.owner.displayName)

        println("Successfully saved and reloaded ThreadTimeline with interface-typed owner")
    }
}