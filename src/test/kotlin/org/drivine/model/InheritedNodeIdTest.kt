package org.drivine.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sample.polytree.ChunkNode
import sample.polytree.DocumentNode

/**
 * A sealed subtype whose `@NodeId` is declared on the interface getter (`@get:NodeId`) resolves its
 * id field — Kotlin does not propagate the annotation onto the `override`, so [FragmentModel] must
 * look through supertypes. Without this, `save` / `load(id)` / snapshot of a concrete subtype (and
 * rooting a view on one) fail with "no @GraphNodeId field".
 */
class InheritedNodeIdTest {

    @Test
    fun `sealed subtype inherits @NodeId from the interface getter`() {
        assertEquals("id", FragmentModel.from(ChunkNode::class.java).nodeIdField)
        assertEquals("id", FragmentModel.from(DocumentNode::class.java).nodeIdField)
    }

    @NodeFragment(labels = ["Direct"])
    data class DirectIdNode(@NodeId val id: String, val name: String = "")

    @Test
    fun `a directly-annotated @NodeId still resolves`() {
        assertEquals("id", FragmentModel.from(DirectIdNode::class.java).nodeIdField)
    }
}
