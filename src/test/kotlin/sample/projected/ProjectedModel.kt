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
