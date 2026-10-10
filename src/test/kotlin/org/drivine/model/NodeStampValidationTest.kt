package org.drivine.model

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.RangeIndex
import org.drivine.schema.FragmentSchemaScanner
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@NodeFragment(labels = ["Stamped"])
data class OneStamp(@NodeId val id: String, @NodeStamp val stamp: String? = null)

@NodeFragment(labels = ["Stamped"])
data class TwoStamps(@NodeId val id: String, @NodeStamp val first: String? = null, @NodeStamp val second: String? = null)

/** A stamp that cannot be null: a new object could not leave it empty, and a node without one could not be loaded. */
@NodeFragment(labels = ["Stamped"])
data class RequiredStamp(@NodeId val id: String, @NodeStamp val stamp: String)

@NodeFragment(labels = ["Stamped"])
data class IndexedStamp(@NodeId val id: String, @RangeIndex @NodeStamp val stamp: String? = null)

@NodeFragment(labels = ["Stamped"])
data class NumericStamp(@NodeId val id: String, @NodeStamp val version: Long? = null)

@NodeFragment(labels = ["Stamped"])
data class RenamedStamp(@NodeId val id: String, @NodeStamp @GraphProperty("version") val stamp: String? = null)

/** A fragment has one `@NodeStamp` field, a `String`, with no other mapping annotation on it. */
class NodeStampValidationTest {

    private fun refusal(fragment: Class<*>): String =
        assertFailsWith<IllegalArgumentException> { FragmentModel.from(fragment) }.message.orEmpty()

    @Test
    fun `a string stamp field is stored under the stamp's own property`() {
        val stamp = FragmentModel.from(OneStamp::class.java).fields.single { it.stamp }

        assertEquals("stamp", stamp.name)
        assertEquals(Stamps.QUOTED, stamp.propertyName)
    }

    @Test
    fun `two stamp fields are refused`() {
        assertEquals(
            "TwoStamps has 2 @NodeStamp fields ('first', 'second'). A node has one stamp.",
            refusal(TwoStamps::class.java),
        )
    }

    @Test
    fun `a stamp field that is not a string is refused`() {
        assertEquals(
            "@NodeStamp field 'version' on NumericStamp must be a nullable String and carry no other mapping annotation.",
            refusal(NumericStamp::class.java),
        )
    }

    @Test
    fun `a stamp field that cannot be null is refused`() {
        assertEquals(
            "@NodeStamp field 'stamp' on RequiredStamp must be a nullable String and carry no other mapping annotation.",
            refusal(RequiredStamp::class.java),
        )
    }

    @Test
    fun `an index on a stamp field names the stamp's property as it is stored`() {
        val range = FragmentSchemaScanner.scan(IndexedStamp::class.java, null).filterIsInstance<RangeIndexSpec>().single()

        assertEquals(listOf(Stamps.PROPERTY), range.properties)
    }

    @Test
    fun `a stamp field with a graph property name is refused`() {
        val message = refusal(RenamedStamp::class.java)

        assertTrue(message.startsWith("@NodeStamp field 'stamp' on RenamedStamp also has @GraphProperty(\"version\")."), message)
        assertTrue(message.endsWith("remove @GraphProperty."), message)
    }
}
