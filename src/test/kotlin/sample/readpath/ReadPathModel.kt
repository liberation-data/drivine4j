package sample.readpath

import org.drivine.annotation.Direction
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.schema.SimilarityFunction
import sample.projected.EntryView
import sample.projected.MarkerProperties

/**
 * Fixtures for what a load reads after its projection, beyond a root or a fragment target: a
 * searchable root that holds a collection of nested views, a relationship that holds one node, and
 * a relationship that must be there.
 */
@NodeFragment(labels = ["Shelf"])
data class Shelf(
    @NodeId val id: String,
    @FullTextIndex val title: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
)

@NodeFragment(labels = ["Keeper"])
data class Keeper(
    @NodeId val id: String,
    @GraphProperty("display_name") val displayName: String? = null,
)

/** A shelf, the entries it holds (each a view with markers of its own), and the one who keeps it. */
@GraphView
data class ShelfView(
    @Root val shelf: Shelf,
    @GraphRelationship(type = "HOLDS", direction = Direction.OUTGOING)
    val entries: List<EntryView> = emptyList(),
    @GraphRelationship(type = "KEPT_BY", direction = Direction.OUTGOING)
    val keeper: Keeper? = null,
)

/** A shelf that is only loaded when someone keeps it. */
@GraphView
data class KeptShelfView(
    @Root val shelf: Shelf,
    @GraphRelationship(type = "KEPT_BY", direction = Direction.OUTGOING)
    val keeper: Keeper,
    @GraphRelationship(type = "STOCKS", direction = Direction.OUTGOING)
    val stock: List<Keeper> = emptyList(),
)

// Hand-written query DSLs, mirroring what `drivine4j-codegen` emits.

class ShelfProperties(override val nodeAlias: String = "shelf") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
}

class KeeperProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val displayName = StringPropertyReference(nodeAlias, "display_name")
}

/** The root of a nested [EntryView] under the relationship's alias, and its markers under theirs. */
class ShelfEntryProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val order = PropertyReference<Long>(nodeAlias, "entry_order")
    val markers = MarkerProperties("${nodeAlias}_markers")
}

class ShelfViewQueryDsl {
    val shelf = ShelfProperties("shelf")
    val entries = ShelfEntryProperties("entries")
    val keeper = KeeperProperties("keeper")

    companion object {
        val INSTANCE = ShelfViewQueryDsl()
    }
}

class KeptShelfViewQueryDsl {
    val shelf = ShelfProperties("shelf")
    val keeper = KeeperProperties("keeper")
    val stock = KeeperProperties("stock")

    companion object {
        val INSTANCE = KeptShelfViewQueryDsl()
    }
}
