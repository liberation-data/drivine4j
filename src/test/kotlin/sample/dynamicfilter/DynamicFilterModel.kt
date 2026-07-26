package sample.dynamicfilter

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyBagReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * A fragment with free-form `@PropertyBag(prefix = "metadata")` — the shape a consumer filters on with
 * arbitrary runtime keys (stored as flat node properties literally named `metadata.<key>`). Used to
 * verify the dynamic `property(path)` / `predicate(path, op, value)` escape hatch resolves those keys
 * cross-engine.
 */
@NodeFragment(labels = ["Record"])
data class RecordNode(
    @NodeId val id: String,
    val title: String,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
)

/** Hand-written DSL mirroring the codegen shape (test source isn't KSP-processed). */
class RecordNodeQueryDsl : NodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    val title = StringPropertyReference("n", "title")

    /** The `@PropertyBag` accessor — the typed way to reach a bag key, for the equivalence check. */
    val metadata = PropertyBagReference("n", "metadata.")

    companion object {
        val INSTANCE = RecordNodeQueryDsl()
    }
}
