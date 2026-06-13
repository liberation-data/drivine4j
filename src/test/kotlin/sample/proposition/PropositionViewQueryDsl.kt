package sample.proposition

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