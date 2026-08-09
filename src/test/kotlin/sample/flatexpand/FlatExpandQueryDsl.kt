package sample.flatexpand

import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/** Hand-written DSL for [SeqExpandView], enough to filter on the anchor and drive a `depth()` override. */
class SeqNodeProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
}

class SeqExpandViewQueryDsl {
    val node = SeqNodeProperties("node")

    companion object {
        val INSTANCE = SeqExpandViewQueryDsl()
    }
}
