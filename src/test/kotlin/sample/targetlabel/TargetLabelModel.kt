package sample.targetlabel

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.Hop
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Fixtures for a condition on a related node, which tests only the nodes the field would load: those
 * the relationship leads to that carry the field's target labels. Each field's relationship also
 * leads to decoys that do not, with properties that would meet the conditions.
 */
@NodeFragment(labels = ["TlShelf"])
data class TlShelf(@NodeId val id: String)

/** A target of two labels: a node with one of them is not one. */
@NodeFragment(labels = ["TlPerson", "TlKeeper"])
data class TlKeeper(
    @NodeId val id: String,
    @GraphProperty("display_name") val displayName: String? = null,
)

@NodeFragment(labels = ["TlEntry"])
data class TlEntry(@NodeId val id: String, val title: String? = null)

@NodeFragment(labels = ["TlMarker"])
data class TlMarker(@NodeId val id: String, val name: String? = null)

/** A nested view, whose own relationship a condition can name. */
@GraphView
data class TlEntryView(
    @Root val entry: TlEntry,
    @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING)
    val markers: List<TlMarker> = emptyList(),
)

/** A target that is one of several classes: loaded by the label they share, each as the class its labels name. */
@NodeFragment(labels = ["TlPet"])
sealed interface TlPet {
    @get:NodeId val id: String
    val name: String?
}

@NodeFragment(labels = ["TlPet", "TlDog"])
data class TlDog(override val id: String, override val name: String? = null) : TlPet

@NodeFragment(labels = ["TlPet", "TlCat"])
data class TlCat(override val id: String, override val name: String? = null) : TlPet

@GraphView
data class TlShelfView(
    @Root val shelf: TlShelf,
    @GraphRelationship(type = "HOLDS", direction = Direction.OUTGOING)
    val entries: List<TlEntryView> = emptyList(),
    @GraphRelationship(type = "KEPT_BY", direction = Direction.OUTGOING)
    val keeper: TlKeeper? = null,
    @GraphRelationship(type = "GUARDED_BY", direction = Direction.OUTGOING)
    val pets: List<TlPet> = emptyList(),
    /** A path: the markers of the entries the shelf holds, reached through a node with the entry's label only. */
    @GraphPath([
        Hop("HOLDS", Direction.OUTGOING, label = "TlEntry"),
        Hop("TAGGED", Direction.OUTGOING),
    ])
    val tagged: List<TlMarker> = emptyList(),
)

// Hand-written query DSL, mirroring what `drivine4j-codegen` emits.

class TlNamed(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val name = StringPropertyReference(nodeAlias, "name")
}

class TlKeeperProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val displayName = StringPropertyReference(nodeAlias, "display_name")
}

class TlEntryProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val markers = TlNamed("${nodeAlias}_markers")
}

class TlShelfViewQueryDsl {
    val shelf = TlNamed("shelf")
    val entries = TlEntryProperties("entries")
    val keeper = TlKeeperProperties("keeper")
    val pets = TlNamed("pets")
    val tagged = TlNamed("tagged")

    companion object {
        val INSTANCE = TlShelfViewQueryDsl()
    }
}
