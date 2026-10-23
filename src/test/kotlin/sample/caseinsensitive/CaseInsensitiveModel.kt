package sample.caseinsensitive

import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
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

/** A member whose email is unique whatever its case. */
@NodeFragment(labels = ["CiMember"])
data class CiMember(@NodeId val id: String, @CaseInsensitive @Unique(ignoreCase = true) val email: String? = null)

/** Many of them, for a plan that reads an index. */
@NodeFragment(labels = ["CiPlanned"])
data class CiPlanned(@NodeId val id: String, @CaseInsensitive val email: String? = null)

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
