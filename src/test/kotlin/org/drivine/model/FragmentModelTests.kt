package org.drivine.model

import org.drivine.annotation.GraphFragment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person
import sample.mapped.fragment.GithubPerson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragmentModelTests {

    @Test
    fun `should extract FragmentModel from Person class`() {
        val model = FragmentModel.from(Person::class.java)

        assertEquals("sample.mapped.fragment.Person", model.className)
        assertEquals(Person::class.java, model.clazz)
        assertEquals(listOf("Person", "Mapped"), model.labels)

        // Verify nodeIdField
        assertEquals("uuid", model.nodeIdField)

        // Verify fields
        assertEquals(3, model.fields.size)

        val nameField = model.fields.find { it.name == "name" }
        assertNotNull(nameField)
        assertEquals(String::class.java, nameField.type)
        assertFalse(nameField.nullable)

        val bioField = model.fields.find { it.name == "bio" }
        assertNotNull(bioField)
        assertEquals(String::class.java, bioField.type)
        assertTrue(bioField.nullable)

        val uuidField = model.fields.find { it.name == "uuid" }
        assertNotNull(uuidField)
        assertEquals(java.util.UUID::class.java, uuidField.type)
        assertFalse(uuidField.nullable)
    }

    @Test
    fun `should extract FragmentModel from GithubPerson with multiple labels`() {
        val model = FragmentModel.from(GithubPerson::class.java)

        assertEquals("sample.mapped.fragment.GithubPerson", model.className)
        assertEquals(listOf("Person", "Mapped", "GithubPerson"), model.labels)

        // Verify nodeIdField
        assertEquals("uuid", model.nodeIdField)

        // Verify it has all fields including inherited
        assertEquals(4, model.fields.size)

        val githubIdField = model.fields.find { it.name == "githubId" }
        assertNotNull(githubIdField)
        assertEquals(String::class.java, githubIdField.type)
        assertFalse(githubIdField.nullable)
    }

    @Test
    fun `should extract FragmentModel from Issue class with various types`() {
        val model = FragmentModel.from(Issue::class.java)

        assertEquals("sample.mapped.fragment.Issue", model.className)
        assertEquals(listOf("Issue"), model.labels)
        assertEquals(7, model.fields.size)

        // Verify nodeIdField
        assertEquals("uuid", model.nodeIdField)

        // Check a few specific fields
        val lockedField = model.fields.find { it.name == "locked" }
        assertNotNull(lockedField)
        assertEquals(Boolean::class.javaPrimitiveType ?: Boolean::class.javaObjectType, lockedField.type)

        val idField = model.fields.find { it.name == "id" }
        assertNotNull(idField)
        assertEquals(Long::class.javaPrimitiveType ?: Long::class.javaObjectType, idField.type)

        val stateField = model.fields.find { it.name == "state" }
        assertNotNull(stateField)
        assertEquals(String::class.java, stateField.type)
        assertTrue(stateField.nullable)
    }

    @Test
    fun `should work with Kotlin KClass parameter`() {
        val model = FragmentModel.from(Person::class)

        assertEquals("sample.mapped.fragment.Person", model.className)
        assertEquals(listOf("Person", "Mapped"), model.labels)
    }

    @Test
    fun `should throw exception for class without GraphFragment annotation`() {
        data class NotAnnotated(val name: String)

        val exception = assertThrows<IllegalArgumentException> {
            FragmentModel.from(NotAnnotated::class.java)
        }

        assertTrue(exception.message!!.contains("not annotated with @GraphFragment"))
    }

    @Test
    fun `field types should include kotlinType for Kotlin classes`() {
        val model = FragmentModel.from(Person::class.java)

        val nameField = model.fields.find { it.name == "name" }
        assertNotNull(nameField)
        assertNotNull(nameField.kotlinType)
        assertEquals(String::class, nameField.kotlinType)
    }

    @Test
    fun `fields should be sorted by name`() {
        val model = FragmentModel.from(Person::class.java)

        val fieldNames = model.fields.map { it.name }
        assertEquals(fieldNames.sorted(), fieldNames)
    }

    @Test
    fun `should return null for nodeIdField when no GraphNodeId annotation present`() {
        // Create a test fragment without @GraphNodeId
        @GraphFragment(labels = ["TestFragment"])
        data class TestFragment(val name: String, val value: Int)

        val model = FragmentModel.from(TestFragment::class.java)

        assertNull(model.nodeIdField)
    }
}