package sample.mapped.view

import com.fasterxml.jackson.annotation.JsonIgnore
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
 * Example: CLIENT-SIDE SORTING with cached lazy property.
 *
 * This pattern allows in-memory sorting of nested collections while keeping
 * the GraphView immutable and compatible with Jackson deserialization.
 *
 * ## When to use this pattern:
 * - Complex sort logic (multiple fields, custom comparators)
 * - No APOC dependency required
 * - Small collections where client-side sorting is acceptable
 * - You want to cache the sorted result (sorted once on first access)
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
 * Database-side sorting requires APOC Extended but avoids transferring unsorted data.
 *
 * ## Simplest alternative: uncached method
 * If caching isn't needed, just add a method (zero annotations required):
 * ```kotlin
 * fun sortedAssignees() = assignees.sortedBy { it.person.name }
 * ```
 *
 * ## How the cached pattern works:
 * - `assignees` is the raw data from Neo4j (natural property name)
 * - `sortedAssignees` is a lazy property that caches the sorted result
 * - Drivine's ObjectMapper auto-ignores Kotlin delegate backing fields (`*$delegate`)
 *
 * ## Required Jackson annotation:
 * - `@get:JsonIgnore` - prevents the lazy property getter from being serialized
 */
@GraphView
data class IssueWithSortedAssignees(
    @Root val issue: Issue,

    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    val assignees: List<AssigneeWithContext>
) {
    /** Assignees sorted by name. Sorted once on first access, then cached. */
    @get:JsonIgnore
    val sortedAssignees: List<AssigneeWithContext> by lazy {
        assignees.sortedBy { it.person.name }
    }
}