package sample.relatedrows

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Fixtures for conditions on related nodes whose properties may be missing, reached each way a
 * relationship can lead: out, in, either way, and along a path of two hops.
 */
@NodeFragment(labels = ["RrShelf"])
data class RrShelf(@NodeId val id: String)

@NodeFragment(labels = ["RrBook"])
data class RrBook(@NodeId val id: String, val title: String? = null, val pages: Long? = null)

@NodeFragment(labels = ["RrTag"])
data class RrTag(@NodeId val id: String, val name: String? = null)

@NodeFragment(labels = ["RrReader"])
data class RrReader(@NodeId val id: String, val name: String? = null)

@GraphView
data class RrBookView(
    @Root val book: RrBook,
    @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING)
    val tags: List<RrTag> = emptyList(),
)

@GraphView
data class RrShelfView(
    @Root val shelf: RrShelf,
    @GraphRelationship(type = "HOLDS", direction = Direction.OUTGOING)
    val books: List<RrBookView> = emptyList(),
    @GraphRelationship(type = "NEXT_TO", direction = Direction.UNDIRECTED)
    val neighbours: List<RrShelf> = emptyList(),
    @GraphRelationship(type = "LIKES", direction = Direction.INCOMING)
    val fans: List<RrReader> = emptyList(),
    /** The tags of the books the shelf holds. */
    @GraphPath([
        Hop("HOLDS", Direction.OUTGOING, label = "RrBook"),
        Hop("TAGGED", Direction.OUTGOING),
    ])
    val marks: List<RrTag> = emptyList(),
)

// Hand-written query DSL, mirroring what `drivine4j-codegen` emits.

class RrNamed(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val name = StringPropertyReference(nodeAlias, "name")
}

class RrBookProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val pages = PropertyReference<Long>(nodeAlias, "pages")
    val tags = RrNamed("${nodeAlias}_tags")
}

class RrShelfViewQueryDsl {
    val shelf = RrNamed("shelf")
    val books = RrBookProperties("books")
    val neighbours = RrNamed("neighbours")
    val fans = RrNamed("fans")
    val marks = RrNamed("marks")

    companion object {
        val INSTANCE = RrShelfViewQueryDsl()
    }
}
