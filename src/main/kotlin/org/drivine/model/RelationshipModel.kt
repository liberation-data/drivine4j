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
     * For relationship fragments, this is the @GraphRelationshipFragment annotated class.
     */
    val elementType: Class<*>,

    /**
     * Whether this relationship is a collection (List, Set, etc.).
     */
    val isCollection: Boolean,

    /**
     * Whether this relationship allows null values.
     * For Kotlin, detected from type nullability (e.g., Person vs Person?).
     * For Java, defaults to true (nullable) unless explicitly marked.
     *
     * When false (non-nullable, required), the generated query will filter out
     * root nodes that don't have this relationship, preventing null pointer exceptions.
     */
    val isNullable: Boolean = true,

    /**
     * Whether this relationship uses a @GraphRelationshipFragment (rich relationship pattern).
     * If true, the relationship has properties stored on the relationship itself,
     * plus a target field pointing to the target node.
     */
    val isRelationshipFragment: Boolean = false,

    /**
     * For relationship fragments: the name of the field pointing to the target node.
     * For example, if the fragment has "val target: Person", this would be "target".
     * Null for direct target references.
     */
    val targetFieldName: String? = null,

    /**
     * For relationship fragments: the type of the target node.
     * This is the element type of the target field in the relationship fragment.
     * Null for direct target references.
     */
    val targetNodeType: Class<*>? = null,

    /**
     * For relationship fragments: the list of relationship property field names.
     * These are the fields on the relationship itself (not the target node).
     * Empty list for direct target references.
     */
    val relationshipProperties: List<String> = emptyList(),

    /**
     * Client-side sorting property path.
     * When specified, the collection will be sorted after deserialization.
     * Supports nested property paths using dot notation (e.g., "person.name").
     * Null if no client-side sorting is configured.
     */
    val sortBy: String? = null,

    /**
     * Sort direction for client-side sorting.
     * True for ascending (default), false for descending.
     * Only used when sortBy is not null.
     */
    val sortAscending: Boolean = true
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