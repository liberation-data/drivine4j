@file:Suppress("UNCHECKED_CAST")

package drivine.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

data class Person(
    val id: String,
    val name: String,
    val age: Int?,
    val email: String?
)

/** Non-data class, mutable bean-style properties */
class Account(
    var id: String,
    var balance: Long,
    var nickname: String?
)

class PartialTest {

    @Test
    fun `data class - apply partial via copy preserves untouched fields`() {
        val original = Person(id = "p1", name = "Jasper", age = 49, email = "jasper@foo.com")

        val patch = partial<Person> {
            set(Person::name, "J. Blues")
            set(Person::email, null) // explicit null
        }

        val updated = original.patchedWith(patch)

        assertEquals("p1", updated.id, "id must be preserved")
        assertEquals("J. Blues", updated.name)
        assertEquals(49, updated.age)
        assertNull(updated.email)
        assertNotSame(original, updated, "data class patch should produce a new instance")
    }

    @Test
    fun `non-data class - apply partial via mutable setters sets in place`() {
        val acct = Account(id = "a1", balance = 1000, nickname = "main")

        val patch = partial<Account> {
            set(Account::balance, 1500)
            set(Account::nickname, null) // explicit null should clear
        }

        val result = acct.patchedWith(patch)
        assertSame(acct, result, "non-data class patch updates in place")
        assertEquals("a1", acct.id)
        assertEquals(1500, acct.balance)
        assertNull(acct.nickname)
    }

    @Test
    fun `tri-state - Absent vs Present(null)`() {
        val original = Person(id = "p1", name = "Jasper", age = 49, email = "x@y.z")

        val setNull = partial<Person> {
            set(Person::email, null)     // Present(null) => should overwrite to null
        }
        val absent = partial<Person> {
            unset(Person::email)         // Absent => leave unchanged
        }

        val afterSetNull = original.patchedWith(setNull)
        assertNull(afterSetNull.email, "Present(null) must set to null")

        val afterAbsent = original.patchedWith(absent)
        assertEquals("x@y.z", afterAbsent.email, "Absent must not change property")
    }

    @Test
    fun `toMap returns only Present fields`() {
        val patch = partial<Person> {
            set(Person::name, "J. Blues")
            set(Person::email, null)     // still Present, value null included
        }

        val m = patch.toMap()
        assertEquals(2, m.size)
        assertTrue("name" in m)
        assertTrue("email" in m)
        assertEquals("J. Blues", m["name"])
        assertTrue(m.containsKey("email"))
        assertNull(m["email"])
    }

    @Test
    fun `diffAgainst shows only changed Present fields with old-new pairs`() {
        val p = Person(id = "p1", name = "Jasper", age = 49, email = null)

        val patch = partial<Person> {
            set(Person::name, "J. Blues")
            set(Person::email, null)     // explicit null (no-op value wise but Present)
        }

        val diff = patch.diffAgainst(p)
        assertEquals(2, diff.size)

        assertEquals("Jasper" to "J. Blues", diff["name"])
        // previous was null, setting to null (explicit). Still appears since it's Present.
        assertEquals(null to null, diff["email"])
    }

    @Test
    fun `merge - right side wins on conflicts`() {
        val left = partial<Person> { set(Person::name, "Left Name") }
        val right = partial<Person> { set(Person::name, "Right Name") }

        val merged = left + right
        val p = Person("p1", "Orig", 1, null).patchedWith(merged)

        assertEquals("Right Name", p.name)
    }

    @Test
    fun `fromMap constructs partial from dynamic payload`() {
        val original = Person(id = "p1", name = "Jasper", age = 49, email = "x@y.z")

        val patch = Partial.fromMap<Person>(
            mapOf(
                "name" to "Jasper Blues",
                "age" to 50,
                "email" to null
            )
        )

        val updated = original.patchedWith(patch)
        assertEquals("Jasper Blues", updated.name)
        assertEquals(50, updated.age)
        assertNull(updated.email)
        assertEquals("p1", updated.id)
    }

    @Test
    fun `touches and field helpers behave correctly`() {
        val patch = partial<Person> {
            set(Person::age, 50)
        }

        assertTrue(patch.touches(Person::age))
        assertFalse(patch.touches(Person::name))

        when (val f = patch.field(Person::age)) {
            is Field.Present -> assertEquals(50, f.value)
            else -> fail("age should be Present")
        }

        assertEquals(Field.Absent, patch.field(Person::name))
    }
}
