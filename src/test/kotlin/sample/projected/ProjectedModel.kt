package sample.projected

import org.drivine.annotation.Direction
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.model.Stamps
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.schema.SimilarityFunction

/**
 * Fixtures for a field whose stored property is not its field name, read after a view has projected
 * it: a `@GraphProperty` field and a `@NodeStamp` field, on the root of a view and on the target of
 * a relationship. The root is searchable both ways, so the scored-search filter can be exercised.
 */
@NodeFragment(labels = ["Passage"])
data class Passage(
    @NodeId val id: String,
    @FullTextIndex val text: String,
    @GraphProperty("sequence_number") val sequenceNumber: Long? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
    @NodeStamp val stamp: String? = null,
)

@NodeFragment(labels = ["Marker"])
data class Marker(
    @NodeId val id: String,
    @GraphProperty("display_name") val displayName: String? = null,
    @NodeStamp val stamp: String? = null,
)

@GraphView
data class PassageView(
    @Root val passage: Passage,
    @GraphRelationship(type = "MARKED", direction = Direction.OUTGOING)
    val markers: List<Marker> = emptyList(),
)

/** A root with a property bag, which a view projects with `.*`: its keys are the stored names. */
@NodeFragment(labels = ["Ledger"])
data class Ledger(
    @NodeId val id: String,
    @GraphProperty("entry_rank") val rank: Long? = null,
    @PropertyBag(prefix = "meta") val meta: Map<String, Any?> = emptyMap(),
)

@GraphView
data class LedgerView(@Root val ledger: Ledger)

/** A node filtered by keys known only at runtime. */
@NodeFragment(labels = ["Oddity"])
data class Oddity(@NodeId val id: String, val title: String)

/** A node whose `@NodeId` is stored under another name, loaded by itself and as the root of a view. */
@NodeFragment(labels = ["Keyed"])
data class Keyed(
    @NodeId @GraphProperty("keyed_id") val key: String,
    val name: String,
)

@GraphView
data class KeyedView(
    @Root val keyed: Keyed,
    @GraphRelationship(type = "MARKED", direction = Direction.OUTGOING)
    val markers: List<Marker> = emptyList(),
)

/** Fields stored under names that are not plain identifiers: one dotted, one hyphenated. */
@NodeFragment(labels = ["Dotted"])
data class Dotted(
    @NodeId val id: String,
    @GraphProperty("meta.rank") val rank: Long? = null,
    @GraphProperty("display-name") val label: String? = null,
)

@GraphView
data class DottedView(
    @Root val dotted: Dotted,
    @GraphRelationship(type = "LINKS", direction = Direction.OUTGOING)
    val links: List<Dotted> = emptyList(),
)

/** A view of views: a binder and its entries, each a view whose root has a renamed field. */
@NodeFragment(labels = ["Binder"])
data class Binder(@NodeId val id: String)

@NodeFragment(labels = ["Entry"])
data class Entry(
    @NodeId val id: String,
    val title: String,
    @GraphProperty("entry_order") val order: Long? = null,
)

@GraphView
data class EntryView(
    @Root val entry: Entry,
    @GraphRelationship(type = "MARKED", direction = Direction.OUTGOING)
    val markers: List<Marker> = emptyList(),
)

@GraphView
data class BinderView(
    @Root val binder: Binder,
    @GraphRelationship(type = "HOLDS", direction = Direction.OUTGOING)
    val entries: List<EntryView> = emptyList(),
)

class KeyedProperties(override val nodeAlias: String) : NodeReference {
    val key = StringPropertyReference(nodeAlias, "keyed_id")
    val name = StringPropertyReference(nodeAlias, "name")

    companion object {
        /** The DSL of the fragment loaded by itself, whose node is `n`. */
        val INSTANCE = KeyedProperties("n")
    }
}

class KeyedViewQueryDsl {
    val keyed = KeyedProperties("keyed")

    companion object {
        val INSTANCE = KeyedViewQueryDsl()
    }
}

class DottedProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val rank = PropertyReference<Long>(nodeAlias, "meta.rank")
    val label = StringPropertyReference(nodeAlias, "display-name")

    companion object {
        /** The DSL of the fragment loaded by itself, whose node is `n`. */
        val INSTANCE = DottedProperties("n")
    }
}

class DottedViewQueryDsl {
    val dotted = DottedProperties("dotted")
    val links = DottedProperties("links")

    companion object {
        val INSTANCE = DottedViewQueryDsl()
    }
}

/** The properties of a nested [EntryView] are those of its root, under the relationship's alias. */
class EntryViewProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val order = PropertyReference<Long>(nodeAlias, "entry_order")
}

class BinderViewQueryDsl {
    val binder = OddityQueryDsl("binder")
    val entries = EntryViewProperties("entries")

    companion object {
        val INSTANCE = BinderViewQueryDsl()
    }
}

// Hand-written query DSLs, mirroring what `drivine4j-codegen` emits (the main test source set does
// not run KSP): a reference is bound to the stored property, not to the field name.

class PassageProperties(override val nodeAlias: String = "passage") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val text = StringPropertyReference(nodeAlias, "text")
    val sequenceNumber = PropertyReference<Long>(nodeAlias, "sequence_number")
    val stamp = StringPropertyReference(nodeAlias, Stamps.PROPERTY)
}

class MarkerProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val displayName = StringPropertyReference(nodeAlias, "display_name")
    val stamp = StringPropertyReference(nodeAlias, Stamps.PROPERTY)
}

class PassageViewQueryDsl {
    val passage = PassageProperties("passage")
    val markers = MarkerProperties("markers")

    companion object {
        val INSTANCE = PassageViewQueryDsl()
    }
}

class LedgerProperties(override val nodeAlias: String = "ledger") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val rank = PropertyReference<Long>(nodeAlias, "entry_rank")
}

class LedgerViewQueryDsl {
    val ledger = LedgerProperties("ledger")

    companion object {
        val INSTANCE = LedgerViewQueryDsl()
    }
}

class OddityQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")

    companion object {
        val INSTANCE = OddityQueryDsl()
    }
}
