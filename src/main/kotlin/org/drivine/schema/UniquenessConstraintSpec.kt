package org.drivine.schema

/**
 * A uniqueness constraint over one or more properties.
 *
 * All supported engines allow multiple nulls under uniqueness — property absence does not violate.
 *
 * @param label node label, e.g. `"ChatSession"`
 * @param properties one (single) or more (composite) properties
 * @param name explicit constraint name; null derives one from label and properties
 * @param nodeIdProperty the property a node is identified by in a violation's sample; set from the
 *   fragment's `@NodeId` when the spec is read from one
 */
data class UniquenessConstraintSpec @JvmOverloads constructor(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
    override val nodeIdProperty: String? = null,
) : ConstraintSpec {

    /** Single-property convenience constructor. */
    @JvmOverloads
    constructor(label: String, property: String, name: String? = null, nodeIdProperty: String? = null) :
        this(label, listOf(property), name, nodeIdProperty)

    init {
        require(properties.isNotEmpty()) { "UniquenessConstraintSpec requires at least one property" }
        requireNameNotWhitespace(name, "UniquenessConstraintSpec")
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.UNIQUENESS_CONSTRAINT

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_unique"
}