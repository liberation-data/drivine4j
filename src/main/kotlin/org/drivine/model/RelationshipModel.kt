package org.drivine.model

import org.drivine.annotation.Direction

/**
 * Represents metadata about a property annotated with @GraphRelationship.
 */
data class RelationshipModel(
    /**
     * The name of the field/property.
     */
    val fieldName: String,

    /**
     * The Neo4j relationship type (e.g., "HAS_HOLIDAY", "ASSIGNED_TO").
     */
    val type: String,

    /**
     * The direction of the relationship.
     */
    val direction: Direction,

    /**
     * The Java Class type of the field.
     * This will be the actual type (could be a List, single object, etc.).
     */
    val fieldType: Class<*>,

    /**
     * The element type - the actual GraphFragment or GraphView type.
     * For List<Person>, this would be Person.
     * For a single Person, this would be Person.
     */
    val elementType: Class<*>,

    /**
     * Whether this relationship is a collection (List, Set, etc.).
     */
    val isCollection: Boolean
) {
    /**
     * Returns the field name to use as the target alias in queries.
     * Simply uses the field name as-is for clarity.
     */
    fun deriveTargetAlias(): String {
        return fieldName
    }

    /**
     * Derives the relationship alias given the root alias.
     * Format: {rootAlias}_{fieldName}
     */
    fun deriveRelationshipAlias(rootAlias: String): String {
        return "${rootAlias}_${fieldName}"
    }
}