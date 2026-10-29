package org.drivine.query.sort

import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.sortpath.SpOrgView
import kotlin.test.assertEquals

/**
 * With APOC, Neo4j sorts a collection at any depth: each sort is matched to its collection by the
 * whole path, so a relationship named with an underscore, at the top or beneath, and one three levels
 * down, each get theirs, and only theirs.
 */
class SortPathTest {

    private fun sortMapsOf(vararg sorts: CollectionSortSpec): Int =
        Regex("apoc\\.coll\\.sortMaps\\(").findAll(
            GraphViewQueryBuilder.forView(SpOrgView::class, CypherDialect.NEO4J_5.grammar()).buildQuery(null, null, sorts.toList())
        ).count()

    private fun query(vararg sorts: CollectionSortSpec): String =
        GraphViewQueryBuilder.forView(SpOrgView::class, CypherDialect.NEO4J_5.grammar()).buildQuery(null, null, sorts.toList())

    @Test
    fun `a sort is matched to its collection by the whole path, whatever underscores the names hold`() {
        assertEquals(0, sortMapsOf())
        for (path in listOf("sub_units", "groups_sub_teams", "groups_sub_teams_members")) {
            assertEquals(1, sortMapsOf(CollectionSortSpec(path, "name", ascending = true)), path)
        }
        // A path that names no collection sorts none: not `sub_teams`, nor `teams_members` read from the end.
        for (path in listOf("sub", "groups_sub", "sub_teams", "sub_teams_members", "groups_sub_teams_x")) {
            assertEquals(0, sortMapsOf(CollectionSortSpec(path, "name", ascending = true)), path)
        }
    }

    @Test
    fun `a sort three levels down wraps the members of each team`() {
        val cypher = query(CollectionSortSpec("groups_sub_teams_members", "name", ascending = false))
        val wrapped = cypher.substringAfter("apoc.coll.sortMaps(").substringBefore("'name')")
        assert("HAS_MEMBER" in wrapped) { cypher }
        assert("HAS_SUB_TEAM" !in wrapped) { cypher }
    }
}
