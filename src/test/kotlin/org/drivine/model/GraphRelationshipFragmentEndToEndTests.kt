package org.drivine.model

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphNodeId
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphRelationshipFragment
import org.drivine.annotation.GraphView
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for @GraphRelationshipFragment.
 * Tests the complete flow: save with properties -> load back -> verify.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class GraphRelationshipFragmentEndToEndTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'rel-fragment-test' DETACH DELETE n")
        )
    }

    @Test
    fun `should save and load relationship fragment with properties`() {
        // Create test data
        val taskId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()

        val assignee = Developer(
            uuid = assigneeId,
            name = "Alice Smith",
            email = "alice@example.com"
        )

        val assignment = TaskAssignment(
            assignedAt = Instant.parse("2025-01-15T10:30:00Z"),
            priority = "HIGH",
            estimatedHours = 8,
            target = assignee
        )

        val task = TaskWithAssignments(
            task = Task(
                uuid = taskId,
                title = "Implement feature X",
                description = "Add new feature"
            ),
            assignedTo = listOf(assignment)
        )

        // Save the view
        graphObjectManager.save(task)

        // Load it back
        val loaded = graphObjectManager.load(taskId.toString(), TaskWithAssignments::class.java)

        // Verify
        assertNotNull(loaded)
        assertEquals("Implement feature X", loaded.task.title)
        assertEquals(1, loaded.assignedTo.size)

        val loadedAssignment = loaded.assignedTo[0]
        assertEquals("HIGH", loadedAssignment.priority)
        assertEquals(8, loadedAssignment.estimatedHours)
        assertEquals(Instant.parse("2025-01-15T10:30:00Z"), loadedAssignment.assignedAt)

        val loadedAssignee = loadedAssignment.target
        assertEquals("Alice Smith", loadedAssignee.name)
        assertEquals("alice@example.com", loadedAssignee.email)
        assertEquals(assigneeId, loadedAssignee.uuid)
    }

    @Test
    fun `should handle multiple assignments to different people`() {
        val taskId = UUID.randomUUID()
        val assignee1Id = UUID.randomUUID()
        val assignee2Id = UUID.randomUUID()

        val assignee1 = Developer(
            uuid = assignee1Id,
            name = "Bob Jones",
            email = "bob@example.com"
        )

        val assignee2 = Developer(
            uuid = assignee2Id,
            name = "Carol White",
            email = "carol@example.com"
        )

        val task = TaskWithAssignments(
            task = Task(
                uuid = taskId,
                title = "Code review",
                description = "Review PR #123"
            ),
            assignedTo = listOf(
                TaskAssignment(
                    assignedAt = Instant.parse("2025-01-15T09:00:00Z"),
                    priority = "MEDIUM",
                    estimatedHours = 2,
                    target = assignee1
                ),
                TaskAssignment(
                    assignedAt = Instant.parse("2025-01-15T14:00:00Z"),
                    priority = "LOW",
                    estimatedHours = 1,
                    target = assignee2
                )
            )
        )

        // Save
        graphObjectManager.save(task)

        // Load back
        val loaded = graphObjectManager.load(taskId.toString(), TaskWithAssignments::class.java)

        // Verify
        assertNotNull(loaded)
        assertEquals(2, loaded.assignedTo.size)

        // Find assignments by priority to avoid ordering issues
        val mediumAssignment = loaded.assignedTo.find { it.priority == "MEDIUM" }
        val lowAssignment = loaded.assignedTo.find { it.priority == "LOW" }

        assertNotNull(mediumAssignment)
        assertEquals(2, mediumAssignment.estimatedHours)
        assertEquals("Bob Jones", mediumAssignment.target.name)

        assertNotNull(lowAssignment)
        assertEquals(1, lowAssignment.estimatedHours)
        assertEquals("Carol White", lowAssignment.target.name)
    }

    @Test
    fun `should update relationship properties when saving again`() {
        val taskId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()

        val assignee = Developer(
            uuid = assigneeId,
            name = "Dave Miller",
            email = "dave@example.com"
        )

        val assignment = TaskAssignment(
            assignedAt = Instant.parse("2025-01-15T10:00:00Z"),
            priority = "LOW",
            estimatedHours = 4,
            target = assignee
        )

        val task = TaskWithAssignments(
            task = Task(
                uuid = taskId,
                title = "Bug fix",
                description = "Fix bug #456"
            ),
            assignedTo = listOf(assignment)
        )

        // Initial save
        graphObjectManager.save(task)

        // Load and verify initial state
        val loaded1 = graphObjectManager.load(taskId.toString(), TaskWithAssignments::class.java)
        assertNotNull(loaded1)
        assertEquals("LOW", loaded1.assignedTo[0].priority)
        assertEquals(4, loaded1.assignedTo[0].estimatedHours)

        // Update the relationship properties
        val updatedAssignment = TaskAssignment(
            assignedAt = Instant.parse("2025-01-15T15:00:00Z"),
            priority = "CRITICAL",
            estimatedHours = 2,
            target = assignee
        )

        val updatedTask = TaskWithAssignments(
            task = Task(
                uuid = taskId,
                title = "Bug fix",
                description = "Fix bug #456"
            ),
            assignedTo = listOf(updatedAssignment)
        )

        // Save again
        graphObjectManager.save(updatedTask)

        // Load and verify updated state
        val loaded2 = graphObjectManager.load(taskId.toString(), TaskWithAssignments::class.java)
        assertNotNull(loaded2)
        assertEquals(1, loaded2.assignedTo.size)
        assertEquals("CRITICAL", loaded2.assignedTo[0].priority)
        assertEquals(2, loaded2.assignedTo[0].estimatedHours)
        assertEquals(Instant.parse("2025-01-15T15:00:00Z"), loaded2.assignedTo[0].assignedAt)
    }
}

// Test domain model

@GraphFragment(labels = ["Task"])
data class Task(
    @GraphNodeId
    val uuid: UUID,
    val title: String,
    val description: String,
    val createdBy: String = "rel-fragment-test"
)

@GraphFragment(labels = ["Developer"])
data class Developer(
    @GraphNodeId
    val uuid: UUID,
    val name: String,
    val email: String,
    val createdBy: String = "rel-fragment-test"
)

@GraphRelationshipFragment
data class TaskAssignment(
    val assignedAt: Instant,
    val priority: String,
    val estimatedHours: Int,
    val target: Developer
)

@GraphView
data class TaskWithAssignments(
    val task: Task,
    @GraphRelationship(type = "ASSIGNED_TO")
    val assignedTo: List<TaskAssignment>
)