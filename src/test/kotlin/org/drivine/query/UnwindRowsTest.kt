package org.drivine.query

import org.drivine.manager.NullPolicy
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.graphproperty.ChunkNode
import sample.graphproperty.WidgetNode
import sample.nodelabels.MemberNode
import sample.nullpolicy.VecSaveNode
import sample.propertybag.BaggedNode
import sample.stateless.Claim
import sample.stateless.Human
import sample.stateless.Thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which fragments an `UNWIND` can save as rows, what a row's `props` hold, and how a stored name is spelt in a statement. */
class UnwindRowsTest {

    private fun model(type: Class<*>) = FragmentModel.from(type)

    private val neo4j = CypherDialect.NEO4J_5.grammar()
    private val falkor = CypherDialect.FALKORDB.grammar()

    // ----- savedByUnwind -----

    @Test
    fun `a plain fragment with an id is saved by UNWIND, on any engine and with no grammar at all`() {
        for (grammar in listOf(neo4j, falkor, null)) {
            assertTrue(model(Human::class.java).savedByUnwind(grammar))
            assertTrue(model(Claim::class.java).savedByUnwind(grammar), "a stamp does not stand in the way")
        }
    }

    @Test
    fun `a fragment with no id, a property bag or a labels field is not`() {
        assertFalse(model(Adrift::class.java).savedByUnwind(neo4j), "no id to merge a row on")
        assertFalse(model(BaggedNode::class.java).savedByUnwind(neo4j), "a bag's stale keys are each object's own")
        assertFalse(model(MemberNode::class.java).savedByUnwind(neo4j), "labels are written into the statement")
    }

    @Test
    fun `a fragment with a vector is saved by UNWIND only where the engine takes a vector as it is`() {
        assertTrue(model(VecSaveNode::class.java).savedByUnwind(neo4j))
        assertFalse(model(VecSaveNode::class.java).savedByUnwind(falkor))
        // With no grammar nothing says a vector is wrapped.
        assertTrue(model(VecSaveNode::class.java).savedByUnwind(null))
    }

    // ----- unwindProps -----

    @Test
    fun `the id and the stamp are left out of a row's props`() {
        val props = model(Claim::class.java).unwindProps(mapOf("id" to "c1", "text" to "t", "note" to "n", "stamp" to "a:b"), NullPolicy.IGNORE)

        assertEquals(mapOf<String, Any?>("text" to "t", "note" to "n"), props)
    }

    @Test
    fun `a null is left out when nulls are ignored, and kept to clear the property when they clear`() {
        val values = mapOf("id" to "c1", "text" to "t", "note" to null)

        assertEquals(mapOf<String, Any?>("text" to "t"), model(Claim::class.java).unwindProps(values, NullPolicy.IGNORE))
        assertEquals(mapOf("text" to "t", "note" to null), model(Claim::class.java).unwindProps(values, NullPolicy.CLEAR))
    }

    @Test
    fun `a null id or stamp is never written, even when nulls clear`() {
        val props = model(Claim::class.java).unwindProps(mapOf("id" to null, "text" to null, "stamp" to null), NullPolicy.CLEAR)

        assertEquals(mapOf<String, Any?>("text" to null), props)
    }

    @Test
    fun `props are keyed by the stored name of each field, the id's own stored name left out with it`() {
        val chunk = model(ChunkNode::class.java).unwindProps(
            mapOf("id" to "k1", "text" to "t", "containerSectionId" to "s1", "sequenceNumber" to 4L, "rootDocumentId" to null),
            NullPolicy.CLEAR,
        )
        val widget = model(WidgetNode::class.java).unwindProps(mapOf("key" to "w1", "name" to "n"), NullPolicy.IGNORE)

        assertEquals(mapOf("text" to "t", "container_section_id" to "s1", "sequence_number" to 4L, "root_document_id" to null), chunk)
        assertEquals(mapOf<String, Any?>("name" to "n"), widget)
    }

    @Test
    fun `what the mapper gives that is no field of the fragment is not written`() {
        val props = model(Thread::class.java).unwindProps(mapOf("id" to "t1", "title" to "hello", "shout" to "HELLO", "unknown" to 1), NullPolicy.CLEAR)

        assertEquals(mapOf<String, Any?>("title" to "hello"), props)
    }

    @Test
    fun `no values is no props`() {
        assertTrue(model(Claim::class.java).unwindProps(emptyMap(), NullPolicy.CLEAR).isEmpty())
        assertTrue(model(Claim::class.java).unwindProps(mapOf("id" to "c1"), NullPolicy.CLEAR).isEmpty())
    }

    @Test
    fun `a row changes a node that has no stamp, or holds another value, or holds a value the row clears`() {
        assertEquals(
            "(n.`__drivine.stamp` IS NULL OR any(k IN keys(row.props) WHERE\n" +
                "    CASE WHEN row.props[k] IS NULL THEN n[k] IS NOT NULL ELSE NOT coalesce(n[k] = row.props[k], false) END))",
            ROW_CHANGES_NODE,
        )
    }

    // ----- Identifiers -----

    @Test
    fun `a name written into a statement is always quoted, and a backtick in it cannot end the quotes`() {
        assertEquals("`Person`", quotedIdentifier("Person"))
        assertEquals("`has space`", quotedIdentifier("has space"))
        assertEquals("`a``b`", quotedIdentifier("a`b"))
        assertEquals("````", quotedIdentifier("`"))
        assertEquals("``", quotedIdentifier(""))
    }

    @Test
    fun `the escape of a backtick is doubled as one, however many u it is written with`() {
        assertEquals("`a``b`", quotedIdentifier("a\\u0060b"))
        assertEquals("`a``b`", quotedIdentifier("a\\uuu0060b"))
        // Another escape is no backtick, and is left as it is.
        assertEquals("`a\\u0061b`", quotedIdentifier("a\\u0061b"))
    }

    @Test
    fun `a field's stored property is bare when it is a plain identifier and quoted when it is not, and the stamp's is quoted already`() {
        val claim = model(Claim::class.java).fields.associateBy { it.name }
        val chunk = model(ChunkNode::class.java).fields.associateBy { it.name }

        assertEquals("text", claim.getValue("text").storedReference)
        assertEquals("container_section_id", chunk.getValue("containerSectionId").storedReference)
        assertEquals("`__drivine.stamp`", claim.getValue("stamp").storedReference)
        assertFalse(claim.getValue("stamp").storedReference.startsWith("``"), "not quoted twice")
    }

    @Test
    fun `the id is referred to by its stored property, and a fragment with no id has none to refer to`() {
        assertEquals("id", model(Claim::class.java).nodeIdReference)
        assertEquals("widget_key", model(WidgetNode::class.java).nodeIdReference)
        assertNull(model(Adrift::class.java).nodeIdReference)
    }
}
