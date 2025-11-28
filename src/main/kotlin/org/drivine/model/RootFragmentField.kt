package org.drivine.model

/**
 * Represents the root fragment field in a GraphView.
 */
data class RootFragmentField(
    /**
     * The name of the field/property.
     */
    val fieldName: String,

    /**
     * The type of the fragment (must be annotated with @GraphFragment).
     */
    val fragmentType: Class<*>
)
