package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.MemgraphGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.grammar.NeptuneCypherGrammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.conditiondepth.CdHolderView
import sample.conditiondepth.CdHolderViewQueryDsl
import sample.conditiondepth.CdWindowView
import sample.conditiondepth.CdWindowViewQueryDsl

/**
 * A condition on a list of fragments read over several hops walks as many as the load reads, a depth
 * given for the query included (liberation-data/drivine4j#461). Neptune has no container to run on, so
 * what it is given is checked here; the other engines run it in `ConditionDepthCrossEngineTest`.
 */
class ConditionDepthRenderingTest {

    private val neo4j = Neo4j5Grammar(ApocSortMapsEmitter())
    private val openCypher = listOf(
        MemgraphGrammar(CallSubqueryEmitter()), FalkorDbCypherGrammar(CallSubqueryEmitter()), NeptuneCypherGrammar(CallSubqueryEmitter()),
    )

    private fun window(grammar: CypherGrammar, depths: Map<String, Int> = emptyMap(), spec: GraphQuerySpec<CdWindowViewQueryDsl>.() -> Unit) =
        CypherGenerator.buildWhereClause(
            GraphQuerySpec(CdWindowViewQueryDsl.INSTANCE).apply(spec).conditions, GraphViewModel.from(CdWindowView::class.java), grammar,
            projectedCollectionMode = false, startIndex = 0, depthOverrides = depths,
        )

    private fun holder(grammar: CypherGrammar, depths: Map<String, Int> = emptyMap(), spec: GraphQuerySpec<CdHolderViewQueryDsl>.() -> Unit) =
        CypherGenerator.buildWhereClause(
            GraphQuerySpec(CdHolderViewQueryDsl.INSTANCE).apply(spec).conditions, GraphViewModel.from(CdHolderView::class.java), grammar,
            projectedCollectionMode = false, startIndex = 0, depthOverrides = depths,
        )

    /** The statement a check is: its condition, and the CALL an openCypher engine counts it in. */
    private fun WhereClauseResult.text() = (prologs + listOfNotNull(whereClause)).joinToString("\n")

    @Test
    fun `a condition walks the field's maxDepth on every engine`() {
        assertEquals(
            "EXISTS { (node)-[:NEXT*1..2]->(following:CdNode) WHERE following.text = \$param_following_text_0 }",
            window(neo4j) { where { query.following.any { text eq "c" } } }.whereClause,
        )
        openCypher.forEach { grammar ->
            val text = window(grammar) { where { query.following.any { text eq "c" } } }.text()
            assertTrue(text.contains("(node)-[:NEXT*1..2]->(following:CdNode)"), "${grammar::class.simpleName}: $text")
        }
    }

    @Test
    fun `an empty block and none walk it too`() {
        assertEquals("EXISTS { (node)-[:NEXT*1..2]->(_:CdNode) }", window(neo4j) { where { query.following.any { } } }.whereClause)
        assertEquals("NOT (EXISTS { (node)-[:NEXT*1..2]->(_:CdNode) })", window(neo4j) { where { query.following.none { } } }.whereClause)
        openCypher.forEach { grammar ->
            val text = window(grammar) { where { query.following.none { } } }.text()
            assertTrue(text.contains("(node)-[:NEXT*1..2]->(:CdNode)"), "${grammar::class.simpleName}: $text")
        }
    }

    @Test
    fun `a depth given for the query is the one walked, down to one hop`() {
        val deeper = mapOf("following" to 3)
        assertTrue(window(neo4j, deeper) { where { query.following.text eq "d" } }.text().contains("(node)-[:NEXT*1..3]->(following:CdNode)"))
        openCypher.forEach { grammar ->
            val text = window(grammar, deeper) { where { query.following.text eq "d" } }.text()
            assertTrue(text.contains("(node)-[:NEXT*1..3]->(following:CdNode)"), "${grammar::class.simpleName}: $text")
        }
        val one = window(neo4j, mapOf("following" to 1)) { where { query.following.text eq "d" } }.text()
        assertTrue(one.contains("(node)-[:NEXT]->(following:CdNode)"), one)
    }

    @Test
    fun `a condition on a field of a nested view walks that field's depth`() {
        val nested = holder(neo4j) { where { query.windows.following.text eq "c" } }.text()
        assertTrue(nested.contains("(holder)-[:HAS]->(windows:CdNode)"), nested)
        assertTrue(nested.contains("(windows)-[:NEXT*1..2]->(windows_following:CdNode)"), nested)
        openCypher.forEach { grammar ->
            val text = holder(grammar, mapOf("following" to 3)) { where { query.windows.following.text eq "c" } }.text()
            assertTrue(text.contains("-[:NEXT*1..3]->(windows_following:CdNode)"), "${grammar::class.simpleName}: $text")
        }
    }
}
