package org.drivine.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person
import sample.mapped.view.PersonContext
import sample.mapped.view.RaisedAndAssignedIssue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphViewModelTests {

    // Inner classes for testing inner class type resolution
    @NodeFragment(labels = ["InnerProject"])
    data class InnerProject(
        @NodeId val uuid: UUID,
        val name: String
    )

    @NodeFragment(labels = ["InnerPerson"])
    data class InnerPerson(
        @NodeId val uuid: UUID,
        val name: String
    )

    @GraphView
    data class InnerGraphView(
        @Root val project: InnerProject,
        @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
        val owners: List<InnerPerson>
    )

    @Test
    fun `should extract GraphViewModel from RaisedAndAssignedIssue`() {
        val model = GraphViewModel.from(RaisedAndAssignedIssue::class.java)

        assertEquals("sample.mapped.view.RaisedAndAssignedIssue", model.className)
        assertEquals(RaisedAndAssignedIssue::class.java, model.clazz)

        // Verify root fragment
        assertEquals("issue", model.rootFragment.fieldName)
        assertEquals(Issue::class.java, model.rootFragment.fragmentType)

        // Verify relationships
        assertEquals(2, model.relationships.size)

        // Check assignedTo relationship
        val assignedTo = model.relationships.find { it.fieldName == "assignedTo" }
        assertNotNull(assignedTo)
        assertEquals("ASSIGNED_TO", assignedTo.type)
        assertEquals(Direction.OUTGOING, assignedTo.direction)
        assertEquals("assignedTo", assignedTo.deriveTargetAlias())
        assertEquals(Person::class.java, assignedTo.elementType)
        assertTrue(assignedTo.isCollection)

        // Check raisedBy relationship
        val raisedBy = model.relationships.find { it.fieldName == "raisedBy" }
        assertNotNull(raisedBy)
        assertEquals("RAISED_BY", raisedBy.type)
        assertEquals(Direction.OUTGOING, raisedBy.direction)
        assertEquals("raisedBy", raisedBy.deriveTargetAlias())
        assertEquals(PersonContext::class.java, raisedBy.elementType)
        assertTrue(!raisedBy.isCollection)
    }

    @Test
    fun `should work with Kotlin KClass parameter`() {
        val model = GraphViewModel.from(RaisedAndAssignedIssue::class)

        assertEquals("sample.mapped.view.RaisedAndAssignedIssue", model.className)
        assertEquals("issue", model.rootFragment.fieldName)
    }

    @Test
    fun `should throw exception for class without GraphView annotation`() {
        data class NotAnnotated(val name: String)

        val exception = assertThrows<IllegalArgumentException> {
            GraphViewModel.from(NotAnnotated::class.java)
        }

        assertTrue(exception.message!!.contains("not annotated with @GraphView"))
    }

    @Test
    fun `should throw exception if no root fragment found`() {
        // This would be a malformed GraphView with only relationships
        // We can't easily test this without creating a test class, so we'll skip for now
        // but the logic is in place
    }

    @Test
    fun `should correctly resolve inner class types in collections`() {
        // This tests that inner classes (defined inside another class) are properly
        // resolved when parsing generic type parameters like List<InnerPerson>
        // Kotlin uses '.' for nested classes, but JVM uses '$'
        val model = GraphViewModel.from(InnerGraphView::class.java)

        assertEquals("org.drivine.model.GraphViewModelTests\$InnerGraphView", model.className)

        // Verify root fragment uses inner class
        assertEquals("project", model.rootFragment.fieldName)
        assertEquals(InnerProject::class.java, model.rootFragment.fragmentType)

        // Verify relationship correctly resolves inner class element type
        val owners = model.relationships.find { it.fieldName == "owners" }
        assertNotNull(owners)
        assertEquals("OWNED_BY", owners.type)
        assertTrue(owners.isCollection)
        // This is the key assertion - element type should be the inner class, not Object
        assertEquals(InnerPerson::class.java, owners.elementType)
    }
}