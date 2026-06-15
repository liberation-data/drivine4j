package org.drivine.model

import kotlin.reflect.KClass

/**
 * Represents a single field/property in a fragment class.
 */
data class FragmentField(
    /**
     * The name of the field/property.
     */
    val name: String,

    /**
     * The Java Class type of the field.
     * Works with both Java and Kotlin classes.
     */
    val type: Class<*>,

    /**
     * The Kotlin KClass type of the field, if available.
     * Null for Java classes or when Kotlin reflection is not available.
     */
    val kotlinType: KClass<*>?,

    /**
     * Whether this field is nullable (primarily for Kotlin).
     * Defaults to true for Java fields (as nullability cannot be determined reliably).
     */
    val nullable: Boolean,

    /**
     * The raw type string representation.
     * Example: "java.lang.String", "java.util.UUID", "kotlin.collections.Set<java.lang.String>"
     */
    val typeString: String,

    /**
     * When non-null, this field is a `@PropertyBag` (an open map persisted as flat prefixed
     * properties). Holds the raw annotation prefix/delimiter; the resolved stored prefix lives on
     * [org.drivine.model.PropertyBagModel].
     */
    val propertyBag: PropertyBagSpec? = null,
)

/** The raw `@PropertyBag` / `@CompositeProperty` annotation values for a field. */
data class PropertyBagSpec(
    val prefix: String,
    val delimiter: String,
)