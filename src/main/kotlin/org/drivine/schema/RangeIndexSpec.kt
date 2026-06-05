package org.drivine.schema

/**
 * A range (b-tree / exact-match) index over one or more properties.
 *
 * A single property gives a simple index; multiple properties give a composite index.
 *
 * @param label node label, e.g. `"Proposition"`
 * @param properties one (single-property) or more (composite) properties
 * @param name explicit index name; null derives one from label and properties
 */
data class RangeIndexSpec(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
) : IndexSpec {

    /** Single-property convenience constructor. */
    constructor(label: String, property: String, name: String? = null) : this(label, listOf(property), name)

    init {
        require(properties.isNotEmpty()) { "RangeIndexSpec requires at least one property" }
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.RANGE_INDEX

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_range"
}