package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.mapped.view.LocationHierarchy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `where` on a level of nested views below the second is rendered as a check of each level in
 * turn, on every grammar, with its parameters as they are bound: a view that holds itself is reached
 * as deep as its DSL goes.
 */
class DeepNestedWhereTest {

    private val hierarchy = GraphViewModel.from(LocationHierarchy::class.java)

    private fun eq(path: String, value: Any) = WhereCondition.PropertyCondition(path, ComparisonOperator.EQUALS, value)

    private fun rendered(conditions: List<WhereCondition>, dialect: CypherDialect): String {
        val result = CypherGenerator.buildWhereClause(conditions, hierarchy, dialect.grammar())
        return (result.prologs + result.whereClause).joinToString("\n")
    }

    private val threeDown = listOf(
        WhereCondition.RelationshipCondition(
            "subLocations",
            listOf(eq("subLocations_subLocations_subLocations.name", "Paris")),
        ),
    )

    @Test
    fun `a where three levels down is a check of each level on Neo4j`() {
        val cypher = rendered(threeDown, CypherDialect.NEO4J_5)

        assertTrue("(location)-[:HAS_LOCATION]->(subLocations)" in cypher, cypher)
        assertTrue("(subLocations)-[:HAS_LOCATION]->(subLocations_subLocations)" in cypher, cypher)
        assertTrue("(subLocations_subLocations)-[:HAS_LOCATION]->(subLocations_subLocations_subLocations)" in cypher, cypher)
        assertTrue("subLocations_subLocations_subLocations.name = \$param_subLocations_subLocations_subLocations_name_0" in cypher, cypher)
        assertEquals(3, Regex("EXISTS \\{").findAll(cypher).count(), cypher)
    }

    @Test
    fun `a where three levels down is one pattern of three hops on the openCypher engines`() {
        listOf(CypherDialect.FALKORDB, CypherDialect.MEMGRAPH).forEach { dialect ->
            val cypher = rendered(threeDown, dialect)
            assertTrue(
                "(location)-[:HAS_LOCATION]->(subLocations)-[:HAS_LOCATION]->(subLocations_subLocations)" +
                    "-[:HAS_LOCATION]->(subLocations_subLocations_subLocations)" in cypher,
                "$dialect: $cypher",
            )
        }
    }

    @Test
    fun `a where at each of three levels binds each of its values once`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                "subLocations",
                listOf(
                    eq("subLocations.name", "France"),
                    eq("subLocations_subLocations.name", "Ile-de-France"),
                    eq("subLocations_subLocations_subLocations.name", "Paris"),
                ),
            ),
        )
        val bindings = CypherGenerator.extractBindings(conditions, hierarchy)
        assertEquals(setOf("France", "Ile-de-France", "Paris"), bindings.values.toSet())
        listOf(CypherDialect.NEO4J_5, CypherDialect.FALKORDB, CypherDialect.MEMGRAPH).forEach { dialect ->
            val cypher = rendered(conditions, dialect)
            val named = Regex("\\$(param_\\w+)").findAll(cypher).map { it.groupValues[1] }.toSet()
            assertEquals(bindings.keys, named, "$dialect: $cypher")
        }
    }
}
