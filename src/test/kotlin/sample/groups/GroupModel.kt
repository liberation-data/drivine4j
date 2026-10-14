package sample.groups

import java.time.Instant
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/** A piece of feedback on a docs page: what a grouped read by a property groups. */
@NodeFragment(labels = ["GroupFeedback"])
data class Feedback(
    @NodeId val id: String,
    val page: String,
    val helpful: Boolean? = null,
    val createdAt: Instant = Instant.parse("2026-03-01T00:00:00Z"),
    /** A number key, and what `sum`, `avg`, `min` and `max` read. */
    val stars: Long? = null,
    val weight: Double? = null,
    /** A key that may have no value. */
    val section: String? = null,
    val tags: List<String>? = null,
)

/** Who a message is from. */
enum class Role { USER, ASSISTANT }

@NodeFragment(labels = ["GroupMessage"])
data class Message(
    @NodeId val id: String,
    val role: Role,
    val createdAt: Instant = Instant.parse("2026-03-01T00:00:00Z"),
    val tokens: Long? = null,
    val tags: List<String>? = null,
)

/** The node between a message and its owner, which a path key walks over. */
@NodeFragment(labels = ["GroupSession"])
data class Session(@NodeId val id: String)

/** The node a path key ends at: two labels, and a property stored under another name. */
@NodeFragment(labels = ["GroupUser", "GroupPerson"])
data class User(
    @NodeId val id: String,
    @GraphProperty("user_name") val username: String? = null,
)

/** A key that is one of several classes, and carries a bag. */
@NodeFragment(labels = ["GroupOwner"])
sealed interface Owner {
    @get:NodeId val id: String
}

@NodeFragment(labels = ["GroupOwner", "GroupTeam"])
data class Team(
    override val id: String,
    @GraphProperty("team_name") val name: String = "",
    @PropertyBag val extra: Map<String, Any?> = emptyMap(),
) : Owner

@NodeFragment(labels = ["GroupOwner", "GroupBot"])
data class Bot(override val id: String, val model: String = "") : Owner

/** A view, which a grouped read refuses as its source. */
@GraphView
data class SessionView(
    @Root val session: Session,
    @GraphRelationship(type = "HAS_MESSAGE") val messages: List<Message> = emptyList(),
)

/** Hand-written DSLs mirroring the codegen shape (the test source set does not run KSP). */
class FeedbackQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val page = StringPropertyReference(nodeAlias, "page")
    val helpful = PropertyReference<Boolean>(nodeAlias, "helpful")
    val createdAt = PropertyReference<Instant>(nodeAlias, "createdAt")
    val stars = PropertyReference<Long>(nodeAlias, "stars")
    val weight = PropertyReference<Double>(nodeAlias, "weight")
    val section = StringPropertyReference(nodeAlias, "section")
    val tags = PropertyReference<List<String>>(nodeAlias, "tags")

    companion object {
        @JvmField
        val INSTANCE = FeedbackQueryDsl()
    }
}

class MessageQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val role = PropertyReference<Role>(nodeAlias, "role")
    val createdAt = PropertyReference<Instant>(nodeAlias, "createdAt")
    val tokens = PropertyReference<Long>(nodeAlias, "tokens")
    val tags = PropertyReference<List<String>>(nodeAlias, "tags")

    companion object {
        val INSTANCE = MessageQueryDsl()
    }
}

class SessionViewQueryDsl {
    val session = StringPropertyReference("session", "id")
    val messages = MessageQueryDsl("messages")

    companion object {
        val INSTANCE = SessionViewQueryDsl()
    }
}
