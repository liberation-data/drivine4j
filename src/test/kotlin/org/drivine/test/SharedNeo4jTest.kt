package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What a class that shares [SharedNeo4j] finds: none of the nodes, indexes or constraints another class left. */
class SharedNeo4jTest {

    @Test
    fun `a class is handed the shared Neo4j with no data, index or constraint of an earlier class`() {
        val container = SharedNeo4j.forClass()
        val provider = Neo4jConnectionProvider(
            name = "shared-neo4j-test", type = DatabaseType.NEO4J,
            host = container.host, port = container.getMappedPort(7687),
            user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
            config = emptyMap(), subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.NEO4J_5,
        )
        val pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, SubtypeRegistry())
        try {
            // One of each kind a test makes, a name that needs quoting among them.
            listOf(
                "CREATE INDEX range_a FOR (n:A) ON (n.x)",
                "CREATE TEXT INDEX text_a FOR (n:A) ON (n.y)",
                "CREATE POINT INDEX point_a FOR (n:A) ON (n.loc)",
                "CREATE FULLTEXT INDEX fulltext_a FOR (n:A) ON EACH [n.y]",
                "CREATE VECTOR INDEX vector_a FOR (n:A) ON (n.e) OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.similarity_function`: 'cosine'}}",
                "CREATE INDEX `range r` FOR ()-[r:R]-() ON (r.w)",
                "CREATE CONSTRAINT unique_b FOR (n:B) REQUIRE n.id IS UNIQUE",
                "CREATE (:A {x: 1})-[:R {w: 2}]->(:B {id: 'b'})",
            ).forEach { pm.execute(QuerySpecification.withStatement(it)) }

            SharedNeo4j.forClass()

            fun count(statement: String) = pm.getOne(QuerySpecification.withStatement(statement).transform(Int::class.java))
            assertEquals(0, count("SHOW CONSTRAINTS YIELD name RETURN count(name)"))
            assertEquals(0, count("SHOW INDEXES YIELD type WHERE type <> 'LOOKUP' RETURN count(type)"))
            assertEquals(0, count("MATCH (n) RETURN count(n)"))
            // Neo4j's own token lookups stay: a label scan needs them.
            assertEquals(2, count("SHOW INDEXES YIELD type WHERE type = 'LOOKUP' RETURN count(type)"))
        } finally {
            provider.end()
        }
    }
}
