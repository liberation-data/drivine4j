package sample.vector

import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/** Hand-written DSL for [DocNode], mirroring the codegen shape (test source isn't KSP-processed), for a filtered `loadNearest`. */
class DocNodeQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")

    companion object {
        val INSTANCE = DocNodeQueryDsl()
    }
}
