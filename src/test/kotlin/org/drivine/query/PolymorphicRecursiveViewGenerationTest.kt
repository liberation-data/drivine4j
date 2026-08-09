package org.drivine.query

import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.mapped.view.LocationHierarchy
import sample.polytree.FolderWithHead
import sample.polytree.TypedContentTreeView

/**
 * Generation-level guards for the polymorphic-recursive-view fix: the nested projection of a
 * polymorphic root fragment must be a comprehension-safe map projection on the node variable carrying
 * `__labels`, never a bare `{ .* }` (the original invalid Cypher). The concrete recursive view's
 * generated Cypher must be unchanged (no `__labels`, no `.*` in the root projection).
 */
class PolymorphicRecursiveViewGenerationTest {

    private val neo4j = CypherDialect.NEO4J_5.grammar()

    @Test
    fun `polymorphic recursive root fragment projects a comprehension-safe map projection with __labels`() {
        val query = GraphViewQueryBuilder.forView(TypedContentTreeView::class, neo4j).buildQuery()

        // The bug: a bare `element: { .* }` (map literal, no source var) is invalid Cypher.
        assertFalse(
            Regex("""element:\s*\{\s*\.\*\s*}""").containsMatchIn(query),
            "must not emit a bare `element: { .* }`:\n$query",
        )
        // The fix: `element: <nodeVar> { .*, __labels: labels(<nodeVar>) }` at each nested depth.
        assertTrue(
            Regex("""element:\s*children_d1\s*\{\s*\.\*,\s*__labels:\s*labels\(children_d1\)\s*}""").containsMatchIn(query),
            "expected comprehension-safe projection at depth 1:\n$query",
        )
        assertTrue(query.contains("__labels: labels(children_d2)"), "expected the pattern at depth 2:\n$query")
    }

    @Test
    fun `polymorphic relationship-target fragment projects labels for dispatch`() {
        val query = GraphViewQueryBuilder.forView(FolderWithHead::class, neo4j).buildQuery()
        // Relationship-target polymorphic fragments carry labels (existing `.*, labels:` shape).
        assertTrue(query.contains(".*"), query)
        assertTrue(Regex("""labels:\s*labels\(head\)""").containsMatchIn(query), query)
    }

    @Test
    fun `concrete recursive view Cypher is unchanged - no __labels, explicit fields`() {
        val query = GraphViewQueryBuilder.forView(LocationHierarchy::class, neo4j).buildQuery()
        assertFalse(query.contains("__labels"), "concrete view must not gain __labels:\n$query")
        // Still the explicit-field map literal at depth.
        assertTrue(query.contains("name: subLocations_d1.name"), query)
        assertFalse(
            Regex("""location:\s*subLocations_d1\s*\{\s*\.\*""").containsMatchIn(query),
            "concrete root fragment must not switch to a `.*` map projection:\n$query",
        )
    }
}
