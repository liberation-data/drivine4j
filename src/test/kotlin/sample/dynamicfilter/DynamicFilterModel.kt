package sample.dynamicfilter

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.query.dsl.PropertyBagReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.ResolvableNodeReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * A fragment mixing a promoted `@GraphProperty` field (`sectionId` → on-disk `section_id`) with
 * free-form `@PropertyBag(prefix = "metadata")` (stored as flat node properties named `metadata.<key>`)
 * — the shape a consumer filters on with arbitrary runtime keys. Used to verify both the dynamic
 * `property(path)` / `predicate(path, op, value)` escape hatch and the model-aware `field(key)` /
 * `predicateOn(key, …)` resolution cross-engine.
 */
@NodeFragment(labels = ["Record"])
data class RecordNode(
    @NodeId val id: String,
    val title: String,
    @GraphProperty("section_id") val sectionId: String? = null,
    val tags: List<String> = emptyList(),
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
)

/**
 * Hand-written DSL mirroring the codegen shape (test source isn't KSP-processed) — now a
 * [ResolvableNodeReference], carrying the same `fieldKeyPaths` / `bagPrefixes` the generator emits.
 */
class RecordNodeQueryDsl : ResolvableNodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    val title = StringPropertyReference("n", "title")
    val sectionId = StringPropertyReference("n", "section_id")

    /** A typed list reference — the typed `hasItem` twin of the dynamic `HAS_ELEMENT`. */
    val tags = PropertyReference<List<String>>("n", "tags")

    /** The `@PropertyBag` accessor — the typed way to reach a bag key, for the equivalence check. */
    val metadata = PropertyBagReference("n", "metadata.")

    // Both the Kotlin name and the @GraphProperty on-disk name resolve to the on-disk name.
    override val fieldKeyPaths: Map<String, String> = mapOf(
        "id" to "id",
        "title" to "title",
        "sectionId" to "section_id",
        "section_id" to "section_id",
        "tags" to "tags",
    )
    override val bagPrefixes: List<String> = listOf("metadata.")

    companion object {
        val INSTANCE = RecordNodeQueryDsl()
    }
}
