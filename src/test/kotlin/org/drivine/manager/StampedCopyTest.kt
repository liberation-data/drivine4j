package org.drivine.manager

import java.util.IdentityHashMap
import kotlin.test.assertEquals
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test

@NodeFragment(labels = ["Hidden"])
private data class Hidden(@NodeId val id: String, val text: String, @NodeStamp val stamp: String? = null)

/** The copy of a saved object that carries its new stamp. */
class StampedCopyTest {

    @Test
    fun `a data class that is not public is copied with its stamp`() {
        val hidden = Hidden("h1", "one")

        val stamped = StampedCopy(Neo4jObjectMapper.instance).of(hidden, IdentityHashMap<Any, String>().apply { put(hidden, "a:b") })

        assertEquals(Hidden("h1", "one", "a:b"), stamped)
    }
}
