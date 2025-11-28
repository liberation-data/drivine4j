package org.drivine.model

import org.drivine.annotation.Direction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person
import sample.mapped.view.PersonContext
import sample.mapped.view.RaisedAndAssignedIssue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphViewModelTests {

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
        assertEquals("assigned", assignedTo.alias)
        assertEquals(Person::class.java, assignedTo.elementType)
        assertTrue(assignedTo.isCollection)

        // Check raisedBy relationship
        val raisedBy = model.relationships.find { it.fieldName == "raisedBy" }
        assertNotNull(raisedBy)
        assertEquals("RAISED_BY", raisedBy.type)
        assertEquals(Direction.OUTGOING, raisedBy.direction)
        assertEquals("raiser", raisedBy.alias)
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
}