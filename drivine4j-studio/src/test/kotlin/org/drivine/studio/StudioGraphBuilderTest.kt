package org.drivine.studio

import org.drivine.sample.fragment.IssueCore
import org.drivine.sample.fragment.Organization
import org.drivine.sample.fragment.Person
import org.drivine.sample.fragment.WorkHistory
import org.drivine.sample.view.PersonCareer
import org.drivine.sample.view.PersonContext
import org.drivine.sample.view.RaisedAndAssignedIssue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioGraphBuilderTest {

    private val acme = Organization(UUID.randomUUID(), "Acme Corp")
    private val alice = Person(UUID.randomUUID(), "Alice", null)
    private val bob = Person(UUID.randomUUID(), "Bob", "Reviewer")

    private fun issue(id: Long, assignedTo: List<Person>, raisedBy: Person) = RaisedAndAssignedIssue(
        issue = IssueCore(UUID.randomUUID(), id, "open", null, "Issue $id", null, false),
        assignedTo = assignedTo,
        raisedBy = PersonContext(raisedBy, listOf(acme)),
    )

    @Test
    fun `a node met under two roots is one node`() {
        val first = issue(1, listOf(alice, bob), raisedBy = alice)
        val second = issue(2, listOf(alice), raisedBy = bob)

        val graph = StudioGraphBuilder().add(first).add(second).build()

        // two issues, two people, one organisation
        assertEquals(5, graph.nodes.size)
        assertEquals(listOf(first.issue.uuid.toString(), second.issue.uuid.toString()), graph.roots)
        assertEquals(1, graph.nodes.count { it.id == alice.uuid.toString() })
    }

    @Test
    fun `a relationship is an edge from the root, and a nested view's relationships are edges from its own root`() {
        val loaded = issue(1, listOf(bob), raisedBy = alice)

        val graph = StudioGraphBuilder().add(loaded).build()

        val edges = graph.edges.map { Triple(it.from, it.type, it.to) }.toSet()
        assertEquals(
            setOf(
                Triple(loaded.issue.uuid.toString(), "ASSIGNED_TO", bob.uuid.toString()),
                Triple(loaded.issue.uuid.toString(), "RAISED_BY", alice.uuid.toString()),
                Triple(alice.uuid.toString(), "WORKS_FOR", acme.uuid.toString()),
            ),
            edges,
        )
    }

    @Test
    fun `a node carries its fragment, labels and properties`() {
        val graph = StudioGraphBuilder().add(bob).build()

        val node = graph.nodes.single()
        assertEquals("Person", node.fragment)
        assertEquals(listOf("Person", "Mapped"), node.labels)
        assertEquals("Bob", node.properties["name"])
        assertEquals("Reviewer", node.properties["bio"])
    }

    @Test
    fun `a relationship fragment's properties are the edge's`() {
        val career = PersonCareer(alice, listOf(WorkHistory(LocalDate.of(2021, 3, 1), "Engineer", null, acme)))

        val graph = StudioGraphBuilder().add(career).build()

        val edge = graph.edges.single()
        assertEquals(alice.uuid.toString() to acme.uuid.toString(), edge.from to edge.to)
        assertEquals("Engineer", edge.properties["role"])
        assertEquals(LocalDate.of(2021, 3, 1), edge.properties["startDate"])
        assertTrue(edge.directed)
    }
}
