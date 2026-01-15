package org.drivine.mapper

import com.fasterxml.jackson.databind.introspect.AnnotatedMember
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector

/**
 * Custom AnnotationIntrospector that auto-ignores Kotlin delegate backing fields.
 *
 * Kotlin's property delegates (e.g., `by lazy`) create synthetic backing fields
 * with names ending in `$delegate`. These are implementation details and should
 * never be serialized.
 *
 * This introspector automatically marks such fields as ignored, eliminating the
 * need for `@JsonIgnoreProperties("propertyName$delegate")` on classes using
 * lazy properties.
 *
 * Example - this pattern now works without the @JsonIgnoreProperties annotation:
 * ```kotlin
 * @GraphView
 * data class IssueWithSortedAssignees(
 *     @Root val issue: Issue,
 *     @GraphRelationship(type = "ASSIGNED_TO")
 *     val assignees: List<AssigneeWithContext>
 * ) {
 *     @get:JsonIgnore
 *     val sortedAssignees: List<AssigneeWithContext> by lazy {
 *         assignees.sortedBy { it.person.name }
 *     }
 * }
 * ```
 */
class DrivineAnnotationIntrospector : NopAnnotationIntrospector() {

    /**
     * Returns true if this member should be ignored during serialization/deserialization.
     *
     * We ignore any member whose name ends with `$delegate`, which are the synthetic
     * backing fields created by Kotlin for property delegates.
     */
    override fun hasIgnoreMarker(m: AnnotatedMember): Boolean {
        val name = m.name
        if (name != null && name.endsWith("\$delegate")) {
            return true
        }
        return false
    }
}