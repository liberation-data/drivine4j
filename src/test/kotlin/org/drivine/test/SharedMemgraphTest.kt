package org.drivine.test

import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What a class that shares [SharedMemgraph] finds: none of the nodes, indexes or constraints another class left. */
class SharedMemgraphTest {

    @Test
    fun `a class is handed the shared Memgraph with no data, index or constraint of an earlier class, in transactional mode`() {
        SharedMemgraph.forClass()
        SharedMemgraph.withManager { pm ->
            // One of each kind a test makes, and several constraints and a composite and a nested-property
            // index, which Memgraph's schema.assert leaves behind.
            listOf(
                "CREATE INDEX ON :A(x)",
                "CREATE INDEX ON :A",
                "CREATE INDEX ON :Message(sessionId, createdAt)",
                "CREATE INDEX ON :BagNode(`metadata.context`)",
                "CREATE CONSTRAINT ON (n:Membership) ASSERT n.tenantId, n.userId IS UNIQUE",
                "CREATE CONSTRAINT ON (n:`_Odd Label`) ASSERT n.`a b` IS UNIQUE",
                "CREATE CONSTRAINT ON (n:C) ASSERT n.x IS TYPED INTEGER",
                "CREATE EDGE INDEX ON :R(w)",
                "CREATE TEXT INDEX text_a ON :A",
                "CREATE VECTOR INDEX vector_a ON :A(e) WITH CONFIG {\"dimension\": 3, \"capacity\": 10, \"metric\": \"cos\"}",
                "CREATE POINT INDEX ON :A(loc)",
                "CREATE CONSTRAINT ON (n:B) ASSERT n.id IS UNIQUE",
                "CREATE CONSTRAINT ON (n:B) ASSERT EXISTS (n.name)",
                "CREATE (:A {x: 1})-[:R {w: 2}]->(:B {id: 'b', name: 'b'})",
            ).forEach { pm.execute(QuerySpecification.withStatement(it)) }

            SharedMemgraph.forClass()

            fun rows(statement: String) = pm.query(QuerySpecification.withStatement(statement)).size
            assertEquals(0, rows("SHOW INDEX INFO"))
            assertEquals(0, rows("SHOW CONSTRAINT INFO"))
            assertEquals(0, rows("SHOW VECTOR INDEX INFO"))
            assertEquals(0, pm.getOne(QuerySpecification.withStatement("MATCH (n) RETURN count(n)").transform(Int::class.java)))
            // Back where the tests expect it: analytical mode has no transactions to roll back.
            pm.execute(QuerySpecification.withStatement("CREATE CONSTRAINT ON (n:B) ASSERT n.id IS UNIQUE"))
        }
    }
}
