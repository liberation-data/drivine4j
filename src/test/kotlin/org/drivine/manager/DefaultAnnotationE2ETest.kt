package org.drivine.manager

import org.drivine.annotation.Default
import org.drivine.annotation.EmptyWhenAbsent
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@NodeFragment(labels = ["DefaultsNode"])
data class DefaultsNode(
    @NodeId val id: String,
    @Default val roles: List<String> = emptyList(),
    @Default val status: String = "active",
    @EmptyWhenAbsent val tags: List<String>,
)

/**
 * End-to-end proof that `@Default` / `@EmptyWhenAbsent` work through the real `GraphObjectManager`
 * load path — where a missing property is surfaced as present-null by the `{ roles: n.roles }`
 * projection, the exact condition the annotations handle.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class DefaultAnnotationE2ETest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    @BeforeEach
    fun clean() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.createdBy = 'defaults-test' DETACH DELETE n")
        )
    }

    @Test
    fun `missing properties resolve to declared defaults and empty collections`() {
        // Node created with only id — roles, status, tags are all absent in the graph
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "CREATE (:DefaultsNode {id: 'n1', createdBy: 'defaults-test'})"
            )
        )

        val node = graphObjectManager.load("n1", DefaultsNode::class.java)

        assertNotNull(node)
        assertEquals(emptyList(), node.roles)   // @Default → declared default
        assertEquals("active", node.status)     // @Default → declared default
        assertEquals(emptyList(), node.tags)    // @EmptyWhenAbsent → empty, no default declared
    }

    @Test
    fun `present values are loaded unchanged`() {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "CREATE (:DefaultsNode {id: 'n2', roles: ['admin', 'ops'], status: 'banned', " +
                    "tags: ['x'], createdBy: 'defaults-test'})"
            )
        )

        val node = graphObjectManager.load("n2", DefaultsNode::class.java)

        assertNotNull(node)
        assertEquals(listOf("admin", "ops"), node.roles)
        assertEquals("banned", node.status)
        assertEquals(listOf("x"), node.tags)
    }
}