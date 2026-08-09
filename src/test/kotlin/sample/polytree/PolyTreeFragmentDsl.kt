package sample.polytree

import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Hand-written fragment query DSLs mirroring exactly what `drivine4j-codegen` emits for a bare
 * `@NodeFragment` (a [NodeReference] at the fragment root alias `"n"` with the node's property refs +
 * `INSTANCE`). Used to verify the generated shape's runtime behaviour cross-engine, since the test
 * source set is not KSP-processed.
 */
class ContentElementNodeQueryDsl : NodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")

    companion object {
        val INSTANCE = ContentElementNodeQueryDsl()
    }
}

class ChunkQueryDsl : NodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    val text = StringPropertyReference("n", "text")

    // @GraphProperty on-disk name — exactly as the generator emits it.
    val containerSectionId = StringPropertyReference("n", "container_section_id")

    companion object {
        val INSTANCE = ChunkQueryDsl()
    }
}
