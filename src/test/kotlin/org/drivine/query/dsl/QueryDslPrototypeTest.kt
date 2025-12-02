package org.drivine.query.dsl

import org.junit.jupiter.api.Test
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person
import sample.mapped.view.RaisedAndAssignedIssue
import java.util.*

/**
 * Manual property reference definitions (Option 1).
 * Users would define these alongside their GraphView.
 */
class ManualRaisedAndAssignedIssueQuery {
    // Root fragment properties
    val issue = ManualIssueProperties()

    // Relationship properties (for filtering on relationship targets)
    val assignedTo = ManualPersonProperties("assignedTo")

    val raisedBy = ManualPersonProperties("raisedBy")

    companion object {
        val INSTANCE = ManualRaisedAndAssignedIssueQuery()
    }
}

class ManualIssueProperties {
    val uuid = PropertyReference<UUID>("issue", "uuid")
    val id = PropertyReference<Long>("issue", "id")
    val state = StringPropertyReference("issue", "state")
    val title = StringPropertyReference("issue", "title")
    val locked = PropertyReference<Boolean>("issue", "locked")
}

class ManualPersonProperties(alias: String) {
    val name = StringPropertyReference(alias, "name")
    val bio = StringPropertyReference(alias, "bio")
}

/**
 * Prototype test showing how the query DSL would be used.
 * This demonstrates the API design before full implementation.
 */
class QueryDslPrototypeTest {

    /**
     * Option 1: Manual property references (immediate solution, no codegen needed)
     *
     * Users would define property references alongside their GraphView.
     * This is explicit but gives full type safety and IDE support.
     */
    @Test
    fun `manual property references - immediate solution`() {
        // Usage would look like:
        val queryObject = ManualRaisedAndAssignedIssueQuery.INSTANCE
        val spec = GraphQuerySpec(queryObject)
        spec.where {
            this(query.issue.state eq "open")
            this(query.issue.id gt 1000)
        }
        spec.orderBy {
            this(query.issue.id.asc())
        }

        // Eventually: graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java, queryObject) { ... }

        println("Conditions: ${spec.conditions}")
        println("Orders: ${spec.orders}")
    }

    /**
     * Option 2: Code generation (future solution, requires annotation processor)
     *
     * An annotation processor would generate property references automatically.
     * Much cleaner UX but requires build-time tooling.
     */
    @Test
    fun `code generated property references - future vision`() {
        // After code generation, users would have:
        // - RaisedAndAssignedIssueQuery object auto-generated
        // - Property references match the actual GraphView structure
        // - Full type safety with zero boilerplate

        // Generated code would look like:
        // @Generated
        // object RaisedAndAssignedIssueQuery {
        //     val issue = IssueProperties("issue")
        //     val assignedTo = PersonProperties("assignedTo")  // Collection relationship
        //     val raisedBy = PersonContextProperties("raisedBy")  // Single relationship
        // }
        //
        // @Generated
        // class IssueProperties(alias: String) {
        //     val uuid = PropertyReference<UUID>(alias, "uuid")
        //     val id = PropertyReference<Long>(alias, "id")
        //     val state = StringPropertyReference(alias, "state")
        //     // ... etc
        // }

        // Usage would be identical but without manual definition:
        // graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java) {
        //     where {
        //         this(RaisedAndAssignedIssueQuery.issue.state eq "open")
        //         this(RaisedAndAssignedIssueQuery.issue.id gt 1000)
        //     }
        //     orderBy {
        //         this(RaisedAndAssignedIssueQuery.issue.id.asc())
        //     }
        // }
    }

    /**
     * Option 3: Runtime reflection (compromise - no codegen, some magic)
     *
     * Generate property references at runtime using reflection on the GraphView model.
     * Less type-safe but zero boilerplate.
     */
    @Test
    fun `runtime reflection - compromise solution`() {
        // Runtime API could look like:
        // graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java) { query ->
        //     query.where {
        //         // 'issue' is dynamically created based on the GraphView model
        //         this(issue.property<String>("state") eq "open")
        //         this(issue.property<Long>("id") gt 1000)
        //     }
        // }

        // Pros: No codegen, no manual definition
        // Cons: String-based property names, less type safety, no IDE completion
    }

    /**
     * My recommendation: Start with Option 1 (manual) for MVP,
     * then add Option 2 (codegen) when you're working on your other codegen feature.
     *
     * This gives users something usable immediately, and the manual definitions
     * can serve as examples/templates for the code generator.
     */
}