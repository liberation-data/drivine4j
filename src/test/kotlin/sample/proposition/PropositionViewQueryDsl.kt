package sample.proposition

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.Scored
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Hand-written query DSL for [PropositionView], mirroring what `drivine4j-codegen` emits (the main
 * test source set does not run KSP). Each `Properties` class implements [NodeReference] and binds its
 * property references to the relationship/root alias, exactly as generated code does.
 */
class PropositionNodeProperties(override val nodeAlias: String = "proposition") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val contextId = StringPropertyReference(nodeAlias, "contextId")
    val status = StringPropertyReference(nodeAlias, "status")
    val level = PropertyReference<Int>(nodeAlias, "level")
    val grounding = PropertyReference<List<String>>(nodeAlias, "grounding")
}

class MentionProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val resolvedId = StringPropertyReference(nodeAlias, "resolvedId")
    val role = StringPropertyReference(nodeAlias, "role")
}

class PropositionViewQueryDsl {
    val proposition = PropositionNodeProperties("proposition")
    val mentions = MentionProperties("mentions")

    companion object {
        val INSTANCE = PropositionViewQueryDsl()
    }
}

/**
 * Mirrors the codegen-emitted filtered vector-search wrapper for [PropositionView] (the generation
 * test `GeneratedLoadNearestTest` asserts the codegen produces exactly this shape). Exercised by the
 * round-trip in `FilteredVectorSearchNeo4jTest`.
 */
inline fun <reified T : PropositionView> GraphObjectManager.loadNearest(
    vector: List<Float>,
    topK: Int,
    threshold: Double? = null,
    noinline spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit,
): List<Scored<T>> = loadNearest(T::class.java, PropositionViewQueryDsl.INSTANCE, vector, topK, threshold, spec)

/** Mirrors the codegen-emitted `count(spec)` wrapper for [PropositionView]. */
inline fun <reified T : PropositionView> GraphObjectManager.count(
    noinline spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit,
): Long = count(T::class.java, PropositionViewQueryDsl.INSTANCE, spec)