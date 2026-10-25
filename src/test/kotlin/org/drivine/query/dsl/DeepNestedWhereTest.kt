package org.drivine.query.dsl

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.mapped.fragment.Location
import sample.mapped.view.LocationHierarchy
import sample.mapped.view.TopLevelWithNestedGraphViews
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A view that holds itself through a relationship whose name holds an underscore. */
@GraphView
data class UnderscoredRegion(
    @Root val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.OUTGOING, maxDepth = 3)
    val sub_regions: List<UnderscoredRegion>,
)

/**
 * A `where` on a level of nested views below the second is rendered as a check of each level in
 * turn, on every grammar, with its parameters as they are bound: a view that holds itself is reached
 * as deep as its DSL goes.
 */
class DeepNestedWhereTest {

    private val hierarchy = GraphViewModel.from(LocationHierarchy::class.java)

    private fun eq(path: String, value: Any) = WhereCondition.PropertyCondition(path, ComparisonOperator.EQUALS, value)

    private fun rendered(conditions: List<WhereCondition>, dialect: CypherDialect, view: GraphViewModel = hierarchy): String {
        val result = CypherGenerator.buildWhereClause(conditions, view, dialect.grammar())
        return (result.prologs + result.whereClause).joinToString("\n")
    }

    private val dialects = listOf(CypherDialect.NEO4J_5, CypherDialect.FALKORDB, CypherDialect.MEMGRAPH)

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
    fun `a where three levels down is one pattern of three hops on FalkorDB`() {
        val cypher = rendered(threeDown, CypherDialect.FALKORDB)
        assertTrue(
            "(location)-[:HAS_LOCATION]->(subLocations)-[:HAS_LOCATION]->(subLocations_subLocations)" +
                "-[:HAS_LOCATION]->(subLocations_subLocations_subLocations)" in cypher,
            cypher,
        )
    }

    @Test
    fun `a where three levels down is a comprehension within a comprehension for each level on Memgraph`() {
        val cypher = rendered(threeDown, CypherDialect.MEMGRAPH)
        assertTrue("[(location)-[:HAS_LOCATION]->(subLocations) WHERE size([(subLocations)-[:HAS_LOCATION]->(subLocations_subLocations)" in cypher, cypher)
        assertTrue("[(subLocations_subLocations)-[:HAS_LOCATION]->(subLocations_subLocations_subLocations) WHERE" in cypher, cypher)
        assertEquals(3, Regex("size\\(\\[").findAll(cypher).count(), cypher)
    }

    @Test
    fun `sibling relationships of a nested view each lead from the node they share`() {
        val view = GraphViewModel.from(sample.siblings.SibOfficeView::class.java)
        val conditions = listOf(eq("desks_employer.name", "Acme"), eq("desks_mentor.name", "Ann"))
        val falkor = rendered(conditions, CypherDialect.FALKORDB, view)
        assertTrue(
            "(office)-[:SEATS]->(desks)-[:WORKS_FOR]->(desks_employer), (desks)-[:MENTORED_BY]->(desks_mentor)" in falkor,
            falkor,
        )
        listOf(CypherDialect.NEO4J_5, CypherDialect.MEMGRAPH).forEach { dialect ->
            val cypher = rendered(conditions, dialect, view)
            assertTrue("(desks)-[:WORKS_FOR]->(desks_employer)" in cypher, "$dialect: $cypher")
            assertTrue("(desks)-[:MENTORED_BY]->(desks_mentor)" in cypher, "$dialect: $cypher")
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

    @Test
    fun `a where three levels down reads each level by the relationships of its own view`() {
        val view = GraphViewModel.from(TopLevelWithNestedGraphViews::class.java)
        val conditions = listOf(eq("issues_raisedBy_employer.name", "Acme"))
        val neo4j = rendered(conditions, CypherDialect.NEO4J_5, view)
        assertTrue("(issues)-[:RAISED_BY]->(issues_raisedBy)" in neo4j, neo4j)
        assertTrue("(issues_raisedBy)-[:WORKS_FOR]->(issues_raisedBy_employer)" in neo4j, neo4j)
        val falkor = rendered(conditions, CypherDialect.FALKORDB, view)
        assertTrue(
            "(root)-[:HAS_ISSUES]->(issues)-[:RAISED_BY]->(issues_raisedBy)-[:WORKS_FOR]->(issues_raisedBy_employer)" in falkor,
            falkor,
        )
        val memgraph = rendered(conditions, CypherDialect.MEMGRAPH, view)
        assertTrue("(issues)-[:RAISED_BY]->(issues_raisedBy)" in memgraph, memgraph)
        assertTrue("(issues_raisedBy)-[:WORKS_FOR]->(issues_raisedBy_employer)" in memgraph, memgraph)
    }

    @Test
    fun `a where below a relationship whose name holds an underscore is read one name at a time`() {
        val view = GraphViewModel.from(UnderscoredRegion::class.java)
        val conditions = listOf(eq("sub_regions_sub_regions_sub_regions.name", "Paris"))
        val neo4j = rendered(conditions, CypherDialect.NEO4J_5, view)
        assertTrue("(sub_regions)-[:HAS_LOCATION]->(sub_regions_sub_regions)" in neo4j, neo4j)
        assertTrue("(sub_regions_sub_regions)-[:HAS_LOCATION]->(sub_regions_sub_regions_sub_regions)" in neo4j, neo4j)
        assertEquals(3, Regex("EXISTS \\{").findAll(neo4j).count(), neo4j)
        val bindings = CypherGenerator.extractBindings(conditions, view)
        dialects.forEach { dialect ->
            val cypher = rendered(conditions, dialect, view)
            val named = Regex("\\$(param_\\w+)").findAll(cypher).map { it.groupValues[1] }.toSet()
            assertEquals(bindings.keys, named, "$dialect: $cypher")
        }
    }

    @Test
    fun `a where three levels down is a check of its own in each branch of an or`() {
        val conditions = listOf(
            WhereCondition.OrCondition(
                listOf(
                    eq("subLocations_subLocations_subLocations.name", "Paris"),
                    eq("subLocations_subLocations_subLocations.name", "Lyon"),
                ),
            ),
        )
        val neo4j = rendered(conditions, CypherDialect.NEO4J_5)
        assertTrue(" OR " in neo4j, neo4j)
        assertEquals(6, Regex("EXISTS \\{").findAll(neo4j).count(), neo4j)
        val bindings = CypherGenerator.extractBindings(conditions, hierarchy)
        assertEquals(setOf("Paris", "Lyon"), bindings.values.toSet())
        dialects.forEach { dialect ->
            val cypher = rendered(conditions, dialect)
            val named = Regex("\\$(param_\\w+)").findAll(cypher).map { it.groupValues[1] }.toSet()
            assertEquals(bindings.keys, named, "$dialect: $cypher")
        }
    }

    @Test
    fun `none of a level three down negates the check of the first`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                "subLocations",
                listOf(eq("subLocations_subLocations_subLocations.name", "Paris")),
                negate = true,
            ),
        )
        val neo4j = rendered(conditions, CypherDialect.NEO4J_5)
        assertTrue(neo4j.startsWith("NOT (EXISTS { (location)-[:HAS_LOCATION]->(subLocations)"), neo4j)
        assertEquals(3, Regex("EXISTS \\{").findAll(neo4j).count(), neo4j)
        assertTrue("NOT (" in rendered(conditions, CypherDialect.MEMGRAPH))
    }

    @Test
    fun `a scored search's where three levels down is refused, as the projection is filtered two levels deep`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            CypherGenerator.buildWhereClause(threeDown, hierarchy, CypherDialect.NEO4J_5.grammar(), projectedCollectionMode = true)
        }
        assertTrue("subLocations_subLocations" in refusal.message!!, refusal.message)
    }
}
