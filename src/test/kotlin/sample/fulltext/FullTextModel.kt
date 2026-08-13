package sample.fulltext

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Fixtures for full-text search ([org.drivine.manager.GraphObjectManager.loadMatching]) cross-engine
 * tests: a single-property index ([ArticleNode] / [ArticleView]), a class-level multi-property index
 * ([EntityNode]), and a sealed hierarchy with a shared full-text-indexed property ([NoteNode]) that
 * exercises polymorphic per-node dispatch.
 */

/** Single-property `@FullTextIndex` on [body] — the common inferred case (no property argument). */
@NodeFragment(labels = ["Article"])
data class ArticleNode(
    @NodeId val id: String,
    val title: String,
    @FullTextIndex val body: String,
)

/**
 * Carries a [PropertyBag]. The bag's open `metadata.` properties have no single column for an explicit
 * projection to map, so a search that does not take the `properties(n).*` path returns the bag empty —
 * while `load` returns it populated. Filtering on a bag key keeps working either way, which is what
 * makes the difference silent, so the fixture exists to assert the bag comes back.
 */
@NodeFragment(labels = ["Memo"])
data class MemoNode(
    @NodeId val id: String,
    @FullTextIndex val body: String,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
)

/** A view rooted on [ArticleNode] — exercises the view full-text path (projection over the hit). */
@GraphView
data class ArticleView(
    @Root val article: ArticleNode,
)

/** Class-level multi-property index — one full-text index spanning `name` + `description`. */
@FullTextIndex(properties = ["name", "description"])
@NodeFragment(labels = ["Entity"])
data class EntityNode(
    @NodeId val id: String,
    val name: String,
    val description: String,
)

/**
 * A sealed fragment whose subtypes share one full-text-indexed property (`text`), declared as a
 * class-level index on the base label `Note`. `loadMatching<NoteNode>` searches the shared index and
 * dispatches each hit to its concrete subtype.
 */
@FullTextIndex(properties = ["text"])
@NodeFragment(labels = ["Note"])
sealed interface NoteNode {
    @get:NodeId val id: String
    val text: String
}

@NodeFragment(labels = ["Note", "PublicNote"])
data class PublicNote(override val id: String, override val text: String) : NoteNode

@NodeFragment(labels = ["Note", "PrivateNote"])
data class PrivateNote(override val id: String, override val text: String) : NoteNode

/**
 * Hand-written fragment query DSL mirroring exactly what `drivine4j-codegen` emits for [ArticleNode]
 * (a [NodeReference] at alias `"n"` with the node's property refs + `INSTANCE`) — the test source set
 * isn't KSP-processed, so the filtered `loadMatching(class, Q, …)` form is exercised through this.
 */
class ArticleNodeQueryDsl : NodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    val title = StringPropertyReference("n", "title")
    val body = StringPropertyReference("n", "body")

    companion object {
        val INSTANCE = ArticleNodeQueryDsl()
    }
}