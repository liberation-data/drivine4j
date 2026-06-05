package org.drivine.manager

import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import org.drivine.query.dsl.RaisedAndAssignedIssueQueryDsl
import org.drivine.query.dsl.query
import sample.mapped.fragment.Issue
import sample.mapped.view.RaisedAndAssignedIssue
import sample.simple.TestAppContext
import kotlin.test.assertEquals

/**
 * End-to-end tests for [GraphObjectManager.count].
 *
 * The key behaviour: counting a `@GraphView` counts only roots that satisfy the view's *required*
 * relationships — so it matches `loadAll(...).size`, not a naive node count. [RaisedAndAssignedIssue]
 * has a required single `raisedBy` (RAISED_BY) plus a collection `assignedTo`, so an Issue without a
 * RAISED_BY edge is counted as an `Issue` fragment but **not** as the view.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class CountTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.createdBy = 'count-test' DETACH DELETE n")
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                // A: open, unlocked, HAS raisedBy
                CREATE (a:Issue {uuid: 'c0000000-0000-0000-0000-000000000001', id: 1, state: 'open',
                                 locked: false, title: 'A', body: 'a', createdBy: 'count-test'})
                // B: closed, locked, HAS raisedBy
                CREATE (b:Issue {uuid: 'c0000000-0000-0000-0000-000000000002', id: 2, state: 'closed',
                                 locked: true, title: 'B', body: 'b', createdBy: 'count-test'})
                // C: open, unlocked, NO raisedBy
                CREATE (c:Issue {uuid: 'c0000000-0000-0000-0000-000000000003', id: 3, state: 'open',
                                 locked: false, title: 'C', body: 'c', createdBy: 'count-test'})
                CREATE (p1:Person:Mapped {uuid: 'c0000000-0000-0000-0000-0000000000a1', name: 'Raiser1',
                                          createdBy: 'count-test'})
                CREATE (p2:Person:Mapped {uuid: 'c0000000-0000-0000-0000-0000000000a2', name: 'Raiser2',
                                          createdBy: 'count-test'})
                CREATE (a)-[:RAISED_BY]->(p1)
                CREATE (b)-[:RAISED_BY]->(p2)
                CREATE (a)-[:ASSIGNED_TO]->(p1)
                """.trimIndent()
            )
        )
    }

    private fun countCypher(cypher: String): Long =
        persistenceManager.getOne(QuerySpecification.withStatement(cypher).transform(Long::class.java))

    // ----- Fragment -----

    @Test
    fun `count of a fragment is a straight node count`() {
        assertEquals(3, graphObjectManager.count(Issue::class.java))
        // Consistent with raw Cypher (scoped to this test's data)
        assertEquals(3, countCypher("MATCH (n:Issue) WHERE n.createdBy = 'count-test' RETURN count(n)"))
    }

    @Test
    fun `count of a fragment with a where clause filters`() {
        assertEquals(2, graphObjectManager.count(Issue::class.java, "n.state = 'open'"))
        assertEquals(1, graphObjectManager.count(Issue::class.java, "n.locked = true"))
    }

    // ----- View: required relationship constrains the count -----

    @Test
    fun `count of a view counts only roots satisfying required relationships`() {
        // A and B have RAISED_BY; C does not. The view requires raisedBy, so C is excluded —
        // even though it IS an Issue node.
        assertEquals(3, graphObjectManager.count(Issue::class.java))
        assertEquals(2, graphObjectManager.count(RaisedAndAssignedIssue::class.java))

        // count == loadAll size, by construction
        assertEquals(
            graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java).size.toLong(),
            graphObjectManager.count(RaisedAndAssignedIssue::class.java),
        )
    }

    @Test
    fun `count of a view with a where clause combines the user filter and the required relationship`() {
        // Open issues: A and C. With raisedBy: A and B. Intersection (open AND has raisedBy) = A only.
        assertEquals(1, graphObjectManager.count(RaisedAndAssignedIssue::class.java, "issue.state = 'open'"))
    }

    // ----- View: DSL -----

    @Test
    fun `count of a view via the query DSL`() {
        val open = graphObjectManager.count(
            RaisedAndAssignedIssue::class.java,
            RaisedAndAssignedIssueQueryDsl.INSTANCE,
        ) {
            where { query.issue.state eq "open" }
        }
        assertEquals(1, open)
    }
}