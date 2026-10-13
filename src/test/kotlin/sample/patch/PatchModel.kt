package sample.patch

import java.time.Instant
import org.drivine.annotation.JsonPacked
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.model.Stamps
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyBagReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/** A fragment whose id may be null, for the refusal of a patch that has no node to find. */
@NodeFragment(labels = ["LooseNote"])
data class LooseNote(@NodeId val id: String?, val text: String? = null)

/** How a session is going: an enum, which a patch stores as a save does. */
enum class Pace { SLOW, BRISK }

/**
 * A chat session as a patch writes it: a moment that only moves forward, and fields of the kinds a
 * lone value must be stored as a save stores them.
 */
@NodeFragment(labels = ["PatchSession"])
data class PatchSession(
    @NodeId val id: String,
    val lastActivityAt: Instant? = null,
    val pace: Pace? = null,
    @JsonPacked val tags: List<String>? = null,
    val turns: Long? = null,
    @NodeStamp val stamp: String? = null,
)

/** Hand-written DSLs mirroring the codegen shape (the test source set does not run KSP). */
class PatchSessionQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val lastActivityAt = PropertyReference<Instant>(nodeAlias, "lastActivityAt")
    val pace = PropertyReference<Pace>(nodeAlias, "pace")
    val tags = PropertyReference<List<String>>(nodeAlias, "tags")
    val turns = PropertyReference<Long>(nodeAlias, "turns")
    val stamp = StringPropertyReference(nodeAlias, Stamps.PROPERTY)

    companion object {
        val INSTANCE = PatchSessionQueryDsl()
    }
}

class ClaimQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val text = StringPropertyReference(nodeAlias, "text")
    val note = StringPropertyReference(nodeAlias, "note")
    val stamp = StringPropertyReference(nodeAlias, Stamps.PROPERTY)

    companion object {
        val INSTANCE = ClaimQueryDsl()
    }
}

class HumanProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val name = StringPropertyReference(nodeAlias, "name")
}

class ClaimViewQueryDsl {
    val claim = ClaimQueryDsl("claim")
    val people = HumanProperties("people")

    companion object {
        val INSTANCE = ClaimViewQueryDsl()
    }
}

class DocNodeQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val embedding = PropertyReference<List<Float>>(nodeAlias, "embedding")

    companion object {
        val INSTANCE = DocNodeQueryDsl()
    }
}

class BaggedNodeQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val title = StringPropertyReference(nodeAlias, "title")
    val metadata = PropertyBagReference(nodeAlias, "metadata.")

    companion object {
        val INSTANCE = BaggedNodeQueryDsl()
    }
}
