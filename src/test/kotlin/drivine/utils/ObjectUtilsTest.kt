package drivine.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectUtilsTest {

    enum class Role { ADMIN, USER }

    data class Address(val city: String, val postcode: Int)

    data class Person(
        val name: String,
        val age: Int,
        val tags: List<String>,
        val role: Role,
        val address: Address?,               // non-primitive → should be excluded
        val nested: List<Address>,           // collection of non-primitive → excluded
        val numbers: List<Int>,              // collection of primitives → included
        val bytes: ByteArray,                // primitive array → included
        val misc: Array<String>,             // object array (primitives-like) → included
        val nickname: String? = null         // nullable → included only when includeNulls=true
    )

    @Test
    fun `kotlin data class - default filtering`() {
        val p = Person(
            name = "Jasper",
            age = 49,
            tags = listOf("dev", "mtb"),
            role = Role.ADMIN,
            address = Address("Brisbane", 4000),
            nested = listOf(Address("Ipswich", 4305)),
            numbers = listOf(1, 2, 3),
            bytes = byteArrayOf(7, 8),
            misc = arrayOf("a", "b"),
            nickname = null
        )

        val map = ObjectUtils.primitiveProps(p)

        // Present
        assertEquals("Jasper", map["name"])
        assertEquals(49, map["age"])
        assertEquals(listOf("dev", "mtb"), map["tags"])
        assertEquals(Role.ADMIN, map["role"])
        assertEquals(listOf(1, 2, 3), map["numbers"])
        assertTrue(map["bytes"] is ByteArray)
        assertEquals(listOf("a", "b"), map["misc"] as List<*>)

        // Absent: non-primitive or null
        assertFalse("address" in map, "non-primitive Address should be excluded")
        assertFalse("nested" in map, "collection of non-primitive should be excluded")
        assertTrue("nickname" in map, "null should be included by default")
    }

    @Test
    fun `kotlin data class - include nulls`() {
        val p = Person(
            name = "Jasper",
            age = 49,
            tags = emptyList(),
            role = Role.USER,
            address = null,
            nested = emptyList(),
            numbers = emptyList(),
            bytes = byteArrayOf(),
            misc = emptyArray(),
            nickname = null
        )

        val map = ObjectUtils.primitiveProps(p, includeNulls = true)
        assertTrue("nickname" in map, "nulls should be included when includeNulls=true")
        assertEquals(null, map["nickname"])
    }

    @Test
    fun `java bean - getter path`() {
        val j = JavaUser().apply {
            username = "janice"
            isActive = true
            scores = intArrayOf(10, 20)
        }

        val map = ObjectUtils.primitiveProps(j)

        assertEquals("janice", map["username"])
        assertEquals(true, map["active"])
        assertTrue(map["scores"] is IntArray)

        // Sanity: only primitive-like entries present
        assertEquals(setOf("username", "active", "scores"), map.keys)
    }
}
