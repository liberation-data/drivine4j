package org.drivine.schema

/**
 * A uniqueness constraint over one or more properties.
 *
 * All supported engines allow multiple nulls under uniqueness — property absence does not violate.
 *
 * @param label node label, e.g. `"ChatSession"`
 * @param properties one (single) or more (composite) properties
 * @param name explicit constraint name; null derives one from label and properties
 */
data class UniquenessConstraintSpec(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
) : ConstraintSpec {

    /** Single-property convenience constructor. */
    constructor(label: String, property: String, name: String? = null) : this(label, listOf(property), name)

    init {
        require(properties.isNotEmpty()) { "UniquenessConstraintSpec requires at least one property" }
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.UNIQUENESS_CONSTRAINT

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_unique"
}