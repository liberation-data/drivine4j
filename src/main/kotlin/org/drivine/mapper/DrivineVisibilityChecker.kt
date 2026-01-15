package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.introspect.AnnotatedField
import com.fasterxml.jackson.databind.introspect.VisibilityChecker
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.Root
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/**
 * Custom VisibilityChecker that makes private fields visible for Jackson
 * ONLY when they have Drivine annotations (@GraphRelationship or @Root).
 *
 * This supports the pattern of using private backing fields with public lazy properties:
 * ```kotlin
 * @GraphView
 * data class IssueWithSortedAssignees(
 *     @Root val issue: Issue,
 *     @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
 *     @field:JsonProperty("_assignees")
 *     private val _assignees: List<AssigneeWithContext>
 * ) {
 *     @get:JsonIgnore
 *     val assignees: List<AssigneeWithContext> by lazy {
 *         _assignees.sortedBy { it.person.name }
 *     }
 * }
 * ```
 *
 * Note: This pattern requires explicit Jackson annotations (@JsonProperty, @JsonIgnore)
 * on the backing field and lazy property. This checker only handles field visibility.
 *
 * All other visibility decisions are delegated to the wrapped checker.
 */
class DrivineVisibilityChecker(
    private val delegate: VisibilityChecker.Std
) : VisibilityChecker.Std(JsonAutoDetect.Visibility.DEFAULT) {

    override fun isFieldVisible(f: Field): Boolean {
        // If it's a private field with Drivine annotations, make it visible
        if (Modifier.isPrivate(f.modifiers) && hasDrivineAnnotation(f)) {
            return true
        }
        return delegate.isFieldVisible(f)
    }

    override fun isFieldVisible(f: AnnotatedField): Boolean {
        val rawField = f.annotated
        val isPrivate = Modifier.isPrivate(rawField.modifiers)
        val hasDrivine = hasDrivineAnnotation(rawField)
        val hasJacksonAnnotatedDrivine = f.hasAnnotation(GraphRelationship::class.java) || f.hasAnnotation(Root::class.java)

        if (isPrivate && (hasDrivine || hasJacksonAnnotatedDrivine)) {
            return true
        }
        return delegate.isFieldVisible(f)
    }

    /**
     * Checks if a field has Drivine annotations directly on the field.
     */
    private fun hasDrivineAnnotation(field: Field): Boolean {
        return field.isAnnotationPresent(GraphRelationship::class.java) ||
               field.isAnnotationPresent(Root::class.java)
    }

    // Delegate all other visibility checks to the wrapped checker

    override fun isGetterVisible(m: java.lang.reflect.Method): Boolean = delegate.isGetterVisible(m)
    override fun isIsGetterVisible(m: java.lang.reflect.Method): Boolean = delegate.isIsGetterVisible(m)
    override fun isSetterVisible(m: java.lang.reflect.Method): Boolean = delegate.isSetterVisible(m)
    override fun isCreatorVisible(m: Member): Boolean = delegate.isCreatorVisible(m)

    // Configuration methods - return a new instance that wraps updated delegate

    override fun with(vis: JsonAutoDetect): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.with(vis) as VisibilityChecker.Std)

    override fun withOverrides(overrides: JsonAutoDetect.Value): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withOverrides(overrides) as VisibilityChecker.Std)

    override fun with(visibility: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.with(visibility) as VisibilityChecker.Std)

    override fun withVisibility(
        forMethod: PropertyAccessor,
        v: JsonAutoDetect.Visibility
    ): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withVisibility(forMethod, v) as VisibilityChecker.Std)

    override fun withGetterVisibility(v: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withGetterVisibility(v) as VisibilityChecker.Std)

    override fun withIsGetterVisibility(v: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withIsGetterVisibility(v) as VisibilityChecker.Std)

    override fun withSetterVisibility(v: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withSetterVisibility(v) as VisibilityChecker.Std)

    override fun withCreatorVisibility(v: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withCreatorVisibility(v) as VisibilityChecker.Std)

    override fun withFieldVisibility(v: JsonAutoDetect.Visibility): DrivineVisibilityChecker =
        DrivineVisibilityChecker(delegate.withFieldVisibility(v) as VisibilityChecker.Std)
}

/**
 * Extension function to create a DrivineVisibilityChecker from any VisibilityChecker.
 */
fun VisibilityChecker<*>.withDrivineAnnotationSupport(): DrivineVisibilityChecker {
    return DrivineVisibilityChecker(this as VisibilityChecker.Std)
}