package org.drivine.studio

import org.drivine.sample.fragment.Person
import org.drivine.sample.fragment.WebUser
import org.drivine.sample.view.PersonCareer
import org.drivine.sample.view.RaisedAndAssignedIssue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioCatalogTest {

    private val catalog = StudioCatalog.scan(listOf("org.drivine.sample"))

    @Test
    fun `a view is described by its root and its relationships`() {
        val view = assertNotNull(catalog.view(RaisedAndAssignedIssue::class.java.name))

        assertEquals("issue", view.root.field)
        assertEquals(listOf("assignedTo", "raisedBy"), view.relationships.map { it.field })
        val assignedTo = view.relationships.first()
        assertEquals("ASSIGNED_TO", assignedTo.type)
        assertTrue(assignedTo.collection)
        assertEquals(Person::class.java.name, assignedTo.filterOn)
    }

    @Test
    fun `a relationship to a view is filtered on that view's root`() {
        val raisedBy = catalog.view(RaisedAndAssignedIssue::class.java.name)!!.relationships.single { it.field == "raisedBy" }

        assertTrue(raisedBy.targetIsView)
        assertEquals(Person::class.java.name, raisedBy.filterOn)
    }

    @Test
    fun `a relationship fragment gives the edge its properties and is not yet filtered on`() {
        val history = catalog.view(PersonCareer::class.java.name)!!.relationships.single()

        assertTrue(history.edgeProperties.containsAll(listOf("startDate", "role")))
        assertNull(history.filterOn)
    }

    @Test
    fun `a fragment is described by its labels, its id and its properties`() {
        val person = assertNotNull(catalog.fragment(Person::class.java.name))

        assertEquals(listOf("Person", "Mapped"), person.labels)
        assertEquals("uuid", person.idProperty)
        assertEquals(listOf("uuid", "bio", "name"), person.properties.map { it.name })
        assertEquals("UUID", person.properties.first().type)
        assertTrue(person.properties.single { it.name == "bio" }.nullable)
    }

    @Test
    fun `a sealed fragment is in the catalogue`() {
        assertNotNull(catalog.fragment(WebUser::class.java.name))
    }
}
