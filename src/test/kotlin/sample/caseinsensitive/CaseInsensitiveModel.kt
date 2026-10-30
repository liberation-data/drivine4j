package sample.caseinsensitive

import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root
import org.drivine.annotation.Unique
import org.drivine.model.Stamps
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/** An account looked up by its email whatever its case, with a flat bag beside it that must not see the copy. */
@NodeFragment(labels = ["CiAccount"])
data class CiAccount(
    @NodeId val id: String,
    @CaseInsensitive val email: String? = null,
    val name: String? = null,
    @PropertyBag(flat = true) val extra: Map<String, Any?> = emptyMap(),
    @NodeStamp val stamp: String? = null,
)

/** Saved before there were copies: its nodes are written by Cypher of the test's own, which keeps none. */
@NodeFragment(labels = ["CiLate"])
data class CiLate(@NodeId val id: String, @CaseInsensitive val email: String? = null)

/** Recreated by a test of its own: an index another test ensures is not dropped beneath it. */
@NodeFragment(labels = ["CiRedone"])
data class CiRedone(@NodeId val id: String, @CaseInsensitive val email: String? = null)

/** A member whose email is unique whatever its case. */
@NodeFragment(labels = ["CiMember"])
data class CiMember(@NodeId val id: String, @CaseInsensitive @Unique(ignoreCase = true) val email: String? = null)

/** Its email regains `@CaseInsensitive` after a while without it, written then as [CiReturningPlain]. */
@NodeFragment(labels = ["CiReturning"])
data class CiReturning(@NodeId val id: String, @CaseInsensitive val email: String? = null)

/** [CiReturning] as it is while its email is not `@CaseInsensitive`: a save leaves the copy as it was. */
@NodeFragment(labels = ["CiReturning"])
data class CiReturningPlain(@NodeId val id: String, val email: String? = null)

/** Many of them, for a plan that reads an index. */
@NodeFragment(labels = ["CiPlanned"])
data class CiPlanned(@NodeId val id: String, @CaseInsensitive val email: String? = null)

/** An account and those it knows, each a `@CaseInsensitive` account: a lookup on the root reads the copy, one on a friend does not. */
@GraphView
data class CiAccountView(
    @Root val account: CiAccount,
    @GraphRelationship(type = "CI_KNOWS", direction = Direction.OUTGOING)
    val friends: List<CiAccount> = emptyList(),
)

/** Hand-written DSLs mirroring the codegen shape (the test source set does not run KSP). */
class CiAccountQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val email = StringPropertyReference(nodeAlias, "email")
    val name = StringPropertyReference(nodeAlias, "name")
    val stamp = StringPropertyReference(nodeAlias, Stamps.PROPERTY)

    companion object {
        val INSTANCE = CiAccountQueryDsl()
    }
}

class CiEmailQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val email = StringPropertyReference(nodeAlias, "email")

    companion object {
        val INSTANCE = CiEmailQueryDsl()
    }
}

class CiAccountViewQueryDsl {
    val account = CiAccountQueryDsl("account")
    val friends = CiAccountQueryDsl("friends")

    companion object {
        val INSTANCE = CiAccountViewQueryDsl()
    }
}
