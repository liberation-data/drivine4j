package org.drivine.schema

import org.drivine.model.LowerCopies

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
 * @param ignoreCase whether the constraint is over the lowercased copy of each property that a
 *   `@CaseInsensitive` field keeps (see [LowerCopies]): `A@x.com` is then refused beside `a@x.com`.
 *   Ensuring it fills the copies in on the nodes of [label] that have none, before it is made
 */
data class UniquenessConstraintSpec @JvmOverloads constructor(
    override val label: String,
    override val properties: List<String>,
    override val name: String? = null,
    override val nodeIdProperty: String? = null,
    val ignoreCase: Boolean = false,
) : ConstraintSpec {

    /** Single-property convenience constructor. */
    @JvmOverloads
    constructor(label: String, property: String, name: String? = null, nodeIdProperty: String? = null, ignoreCase: Boolean = false) :
        this(label, listOf(property), name, nodeIdProperty, ignoreCase)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    constructor(label: String, properties: List<String>, name: String?, nodeIdProperty: String?, mask: Int, marker: kotlin.jvm.internal.DefaultConstructorMarker?) :
        this(label, properties, if (mask and 4 != 0) null else name, if (mask and 8 != 0) null else nodeIdProperty)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    constructor(label: String, property: String, name: String?, nodeIdProperty: String?, mask: Int, marker: kotlin.jvm.internal.DefaultConstructorMarker?) :
        this(label, listOf(property), if (mask and 4 != 0) null else name, if (mask and 8 != 0) null else nodeIdProperty)

    @Deprecated("Binary compatibility with callers compiled before ignoreCase", level = DeprecationLevel.HIDDEN)
    fun copy(
        label: String = this.label,
        properties: List<String> = this.properties,
        name: String? = this.name,
        nodeIdProperty: String? = this.nodeIdProperty,
    ): UniquenessConstraintSpec = copy(label = label, properties = properties, name = name, nodeIdProperty = nodeIdProperty, ignoreCase = ignoreCase)

    init {
        require(properties.isNotEmpty()) { "UniquenessConstraintSpec requires at least one property" }
        requireNameNotWhitespace(name, "UniquenessConstraintSpec")
    }

    override val kind: SchemaItemKind
        get() = SchemaItemKind.UNIQUENESS_CONSTRAINT

    override fun defaultName(): String = "${label}_${properties.joinToString("_")}_${if (ignoreCase) "ci_" else ""}unique"

    override val inventoryKey: String
        get() = if (ignoreCase) made().inventoryKey else super.inventoryKey

    /** The constraint as it is made: over the copies, when it ignores case, and named as this one is. */
    internal fun made(): UniquenessConstraintSpec =
        if (ignoreCase) UniquenessConstraintSpec(label, properties.map(LowerCopies::of), effectiveName, nodeIdProperty) else this
}
