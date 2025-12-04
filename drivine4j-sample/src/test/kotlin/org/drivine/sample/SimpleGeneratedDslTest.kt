package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.view.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals

/**
 * Simple test to verify generated DSL works.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class SimpleGeneratedDslTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'simple-test' DETACH DELETE n")
        )

        val issueUuid = UUID.randomUUID()
        val personUuid = UUID.randomUUID()
        val orgUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (i:Issue {
                        uuid: '$issueUuid',
                        id: 1,
                        title: 'Test Issue',
                        body: 'Test body',
                        state: 'open',
                        locked: false,
                        createdBy: 'simple-test'
                    })
                    CREATE (p:Person:Mapped {
                        uuid: '$personUuid',
                        name: 'Test Person',
                        bio: 'Test bio',
                        createdBy: 'simple-test'
                    })
                    CREATE (i)-[:ASSIGNED_TO]->(p)
                    CREATE (i)-[:RAISED_BY]->(p)
                    CREATE (p)-[:WORKS_FOR]->(:Organization:Mapped {
                        uuid: '$orgUuid',
                        name: 'Test Org',
                        createdBy: 'simple-test'
                    })
                """.trimIndent())
        )
    }

    @Test
    fun `load all issues without filter`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(1, results.size)
    }

    @Test
    fun `filter using manual DSL syntax`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                 query.issue.state eq "open"
            }
        }
        assertEquals(1, results.size)
    }
}
