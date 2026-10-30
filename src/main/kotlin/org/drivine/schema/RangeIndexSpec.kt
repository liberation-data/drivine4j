package org.drivine.schema

import org.drivine.model.LowerCopies

/**
 * A range (b-tree / exact-match) index over one or more properties.
 *
 * A single property gives a simple index; multiple properties give a composite index.
 *
 * @param label node label, e.g. `"Proposition"`
 * @param properties one (single-property) or more (composite) properties
 * @param name explicit index name; null derives one from label and properties
 * @param ignoreCase whether the index is over the lowercased copy of each property that a
 *   `@CaseInsensitive` field keeps (see [LowerCopies]), so that it serves `eqIgnoreCase`; ensuring it
 *   fills the copies in on the nodes of [label] that have none, and records that they all have one
 */
data class RangeIndexSpec @JvmOverloads constructor(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
    val ignoreCase: Boolean = false,
) : IndexSpec {

    /** Single-property convenience constructor. */
    @JvmOverloads
    constructor(label: String, property: String, name: String? = null, ignoreCase: Boolean = false) : this(label, listOf(property), name, ignoreCase)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    constructor(label: String, properties: List<String>, name: String?, mask: Int, marker: kotlin.jvm.internal.DefaultConstructorMarker?) :
        this(label, properties, if (mask and 4 != 0) null else name)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    constructor(label: String, property: String, name: String?, mask: Int, marker: kotlin.jvm.internal.DefaultConstructorMarker?) :
        this(label, listOf(property), if (mask and 4 != 0) null else name)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    fun copy(label: String = this.label, properties: List<String> = this.properties, name: String? = this.name): RangeIndexSpec =
        copy(label = label, properties = properties, name = name, ignoreCase = ignoreCase)

    init {
        require(properties.isNotEmpty()) { "RangeIndexSpec requires at least one property" }
        requireNameNotWhitespace(name, "RangeIndexSpec")
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.RANGE_INDEX

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_${if (ignoreCase) "ci_" else ""}range"

    override val inventoryKey: String
        get() = if (ignoreCase) made().inventoryKey else super.inventoryKey

    /** The index as it is made: over the copies, when it ignores case, and named as this one is. */
    internal fun made(): RangeIndexSpec =
        if (ignoreCase) RangeIndexSpec(label, properties.map(LowerCopies::of), effectiveName) else this
}
