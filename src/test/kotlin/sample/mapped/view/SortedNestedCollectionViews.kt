package sample.mapped.view

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person

/**
 * A nested GraphView that will be collected in a list and sorted.
 */
@GraphView
data class AssigneeWithContext(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val employer: sample.mapped.fragment.Organization?
)

/**
 * Example: CLIENT-SIDE SORTING with private backing field pattern.
 *
 * This pattern allows in-memory sorting of nested collections while keeping
 * the GraphView immutable and compatible with Jackson deserialization.
 *
 * ## When to use this pattern:
 * - Complex sort logic (multiple fields, custom comparators)
 * - No APOC dependency required
 * - Small collections where client-side sorting is acceptable
 *
 * ## Alternative: DATABASE-SIDE SORTING with APOC
 * For simpler sorts on larger collections, use the DSL:
 * ```kotlin
 * graphObjectManager.loadAll(MyView::class.java, MyViewQueryDsl.INSTANCE) {
 *     orderBy {
 *         query.assignees.name.asc()  // Uses apoc.coll.sortMaps()
 *     }
 * }
 * ```
 * Database-side sorting requires APOC but avoids transferring unsorted data.
 *
 * ## How this pattern works:
 * - Private backing field `_assignees` receives the unsorted list from Neo4j
 * - Public `assignees` property provides sorted access via lazy evaluation
 *
 * ## Required Jackson annotations:
 * - `@field:JsonProperty("_assignees")` - ensures the private field is serialized with correct name
 * - `@get:JsonIgnore` - prevents the lazy property getter from being serialized
 * - `@JsonIgnoreProperties("assignees$delegate")` - ignores Kotlin's lazy delegate backing field
 */
@GraphView
@JsonIgnoreProperties("assignees\$delegate")
data class IssueWithSortedAssignees(
    @Root val issue: Issue,

    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    @field:JsonProperty("_assignees")
    private val _assignees: List<AssigneeWithContext>
) {
    /** Assignees sorted by name. Sorted once on first access. */
    @get:JsonIgnore
    val assignees: List<AssigneeWithContext> by lazy {
        _assignees.sortedBy { it.person.name }
    }

    /** Returns a copy with an additional assignee. */
    fun withAssignee(assignee: AssigneeWithContext): IssueWithSortedAssignees =
        copy(_assignees = _assignees + assignee)
}