package org.drivine.annotation

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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// =========================================================================
// Test data classes (outside the test class for proper type resolution)
// =========================================================================

@NodeFragment(labels = ["SortProject"])
data class SortProject(
    @NodeId val uuid: UUID,
    val name: String
)

@NodeFragment(labels = ["SortContributor"])
data class SortContributor(
    @NodeId val uuid: UUID,
    val name: String,
    val role: String
)

/**
 * GraphView with @SortedBy on a simple property.
 */
@GraphView
data class ProjectWithSortedContributors(
    @Root val project: SortProject,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("name")  // Sort by contributor name
    val contributors: List<SortContributor>
)

/**
 * GraphView with @SortedBy descending.
 */
@GraphView
data class ProjectWithContributorsSortedDesc(
    @Root val project: SortProject,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("name", ascending = false)  // Sort by name descending
    val contributors: List<SortContributor>
)

/**
 * GraphView with @SortedBy on a nested property (via nested GraphView).
 */
@NodeFragment(labels = ["SortTask"])
data class SortTask(
    @NodeId val uuid: UUID,
    val title: String
)

@GraphView
data class ContributorWithTasks(
    @Root val contributor: SortContributor,
    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    val tasks: List<SortTask>
)

@GraphView
data class ProjectWithNestedSortedContributors(
    @Root val project: SortProject,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("contributor.name")  // Sort by nested property
    val contributors: List<ContributorWithTasks>
)

// =========================================================================
// Tests
// =========================================================================

/**
 * Tests for the @SortedBy annotation (client-side sorting).
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class SortedByTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setup() {
        // Clean up test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.createdBy = 'sorted-by-test' DETACH DELETE n"
            )
        )
    }

    @Test
    fun `should sort collection by simple property ascending`() {
        val projectUuid = UUID.randomUUID()

        // Create project with contributors in random order
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:SortProject {uuid: ${'$'}projectUuid, name: 'Test Project', createdBy: 'sorted-by-test'})
                    CREATE (c1:SortContributor {uuid: ${'$'}uuid1, name: 'Zara', role: 'Dev', createdBy: 'sorted-by-test'})
                    CREATE (c2:SortContributor {uuid: ${'$'}uuid2, name: 'Alice', role: 'Lead', createdBy: 'sorted-by-test'})
                    CREATE (c3:SortContributor {uuid: ${'$'}uuid3, name: 'Mike', role: 'QA', createdBy: 'sorted-by-test'})
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c1)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c2)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c3)
                """.trimIndent())
                .bind(mapOf(
                    "projectUuid" to projectUuid.toString(),
                    "uuid1" to UUID.randomUUID().toString(),
                    "uuid2" to UUID.randomUUID().toString(),
                    "uuid3" to UUID.randomUUID().toString()
                ))
        )

        // Load with @SortedBy annotation
        val result = graphObjectManager.load(
            projectUuid.toString(),
            ProjectWithSortedContributors::class.java
        )!!

        // Verify sorted order (ascending by name)
        assertEquals(3, result.contributors.size)
        assertEquals("Alice", result.contributors[0].name)
        assertEquals("Mike", result.contributors[1].name)
        assertEquals("Zara", result.contributors[2].name)

        println("Contributors sorted ascending: ${result.contributors.map { it.name }}")
    }

    @Test
    fun `should sort collection by simple property descending`() {
        val projectUuid = UUID.randomUUID()

        // Create project with contributors
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:SortProject {uuid: ${'$'}projectUuid, name: 'Test Project', createdBy: 'sorted-by-test'})
                    CREATE (c1:SortContributor {uuid: ${'$'}uuid1, name: 'Alice', role: 'Dev', createdBy: 'sorted-by-test'})
                    CREATE (c2:SortContributor {uuid: ${'$'}uuid2, name: 'Zara', role: 'Lead', createdBy: 'sorted-by-test'})
                    CREATE (c3:SortContributor {uuid: ${'$'}uuid3, name: 'Mike', role: 'QA', createdBy: 'sorted-by-test'})
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c1)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c2)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c3)
                """.trimIndent())
                .bind(mapOf(
                    "projectUuid" to projectUuid.toString(),
                    "uuid1" to UUID.randomUUID().toString(),
                    "uuid2" to UUID.randomUUID().toString(),
                    "uuid3" to UUID.randomUUID().toString()
                ))
        )

        // Load with @SortedBy descending
        val result = graphObjectManager.load(
            projectUuid.toString(),
            ProjectWithContributorsSortedDesc::class.java
        )!!

        // Verify sorted order (descending by name)
        assertEquals(3, result.contributors.size)
        assertEquals("Zara", result.contributors[0].name)
        assertEquals("Mike", result.contributors[1].name)
        assertEquals("Alice", result.contributors[2].name)

        println("Contributors sorted descending: ${result.contributors.map { it.name }}")
    }

    @Test
    fun `should sort by nested property path`() {
        val projectUuid = UUID.randomUUID()

        // Create project with contributors (via nested GraphView)
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:SortProject {uuid: ${'$'}projectUuid, name: 'Test Project', createdBy: 'sorted-by-test'})
                    CREATE (c1:SortContributor {uuid: ${'$'}uuid1, name: 'Zara', role: 'Dev', createdBy: 'sorted-by-test'})
                    CREATE (c2:SortContributor {uuid: ${'$'}uuid2, name: 'Alice', role: 'Lead', createdBy: 'sorted-by-test'})
                    CREATE (c3:SortContributor {uuid: ${'$'}uuid3, name: 'Mike', role: 'QA', createdBy: 'sorted-by-test'})
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c1)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c2)
                    CREATE (p)-[:HAS_CONTRIBUTOR]->(c3)
                """.trimIndent())
                .bind(mapOf(
                    "projectUuid" to projectUuid.toString(),
                    "uuid1" to UUID.randomUUID().toString(),
                    "uuid2" to UUID.randomUUID().toString(),
                    "uuid3" to UUID.randomUUID().toString()
                ))
        )

        // Load with nested property sorting
        val result = graphObjectManager.load(
            projectUuid.toString(),
            ProjectWithNestedSortedContributors::class.java
        )!!

        // Verify sorted order by nested contributor.name
        assertEquals(3, result.contributors.size)
        assertEquals("Alice", result.contributors[0].contributor.name)
        assertEquals("Mike", result.contributors[1].contributor.name)
        assertEquals("Zara", result.contributors[2].contributor.name)

        println("Contributors sorted by nested property: ${result.contributors.map { it.contributor.name }}")
    }

    @Test
    fun `should handle empty collection with SortedBy`() {
        val projectUuid = UUID.randomUUID()

        // Create project without contributors
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:SortProject {uuid: ${'$'}projectUuid, name: 'Empty Project', createdBy: 'sorted-by-test'})
                """.trimIndent())
                .bind(mapOf("projectUuid" to projectUuid.toString()))
        )

        // Load with @SortedBy - should handle empty list gracefully
        val result = graphObjectManager.load(
            projectUuid.toString(),
            ProjectWithSortedContributors::class.java
        )!!

        assertTrue(result.contributors.isEmpty())
        println("Empty collection handled correctly")
    }
}
