package org.drivine.backend

import org.drivine.backend.sql.NodeIds
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.TemporalValue
import org.drivine.mapper.HeldTemporals
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.junit.jupiter.api.Test
import sample.idkind.KeyedAny
import sample.idkind.KeyedInt
import sample.idkind.KeyedLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * An id given to find a node by, made the value a save writes for the `@NodeId` field ([GivenIds]), as a graph engine binds
 * it (liberation-data/drivine4j#806).
 */
class GivenIdsTest {

    private fun <T> valueOf(type: Class<T>, given: Any?): Any? = GivenIds.valueOf(Neo4jObjectMapper.instance, FragmentModel.from(type), given)

    @Test
    fun `text for a number id is the number, a number of another width is the id's own, and text that is none is null`() {
        assertEquals(5L, valueOf(KeyedLong::class.java, "5"))
        assertEquals(5L, valueOf(KeyedLong::class.java, 5))
        assertEquals(5, valueOf(KeyedInt::class.java, "5"))
        assertNull(valueOf(KeyedLong::class.java, "abc"))
        assertNull(valueOf(KeyedLong::class.java, null))
    }

    /**
     * A text a SQL store's load read from a temporal tag is that tag again only on a SQL store, which holds the tag. A graph
     * engine holds no tag: a save there writes the text, so the text is what finds the node.
     */
    @Test
    fun `a text read from a SQL store's temporal tag, given for an Any id, is the text on a graph engine and the tag on a SQL store`() {
        val read = HeldTemporals.read(mapOf("labels" to listOf("Keyed"), "id" to mapOf("\$t" to "date", "v" to String("2026-01-05".toCharArray())))) as Map<*, *>
        val text = read["id"] as String
        val model = FragmentModel.from(KeyedAny::class.java)

        assertSame(text, GivenIds.valueOf(Neo4jObjectMapper.instance, model, text))
        assertIs<TemporalValue>(NodeIds(Neo4jObjectMapper.instance, Store.POSTGRES).of(model, text))
    }
}
