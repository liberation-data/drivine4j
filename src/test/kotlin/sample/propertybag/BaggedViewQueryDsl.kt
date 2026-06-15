package sample.propertybag

import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyBagReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Hand-written query DSL for [BaggedView], mirroring what `drivine4j-codegen` emits for a
 * `@PropertyBag` field: a [PropertyBagReference] (with `key(name)`) instead of a scalar reference.
 */
class BaggedNodeProperties(override val nodeAlias: String = "node") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val title = StringPropertyReference(nodeAlias, "title")
    val metadata = PropertyBagReference(nodeAlias, "metadata.")
}

class TaggedNodeProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val name = StringPropertyReference(nodeAlias, "name")
    val attributes = PropertyBagReference(nodeAlias, "attr.")
}

class BaggedViewQueryDsl {
    val node = BaggedNodeProperties("node")
    val tags = TaggedNodeProperties("tags")

    companion object {
        val INSTANCE = BaggedViewQueryDsl()
    }
}