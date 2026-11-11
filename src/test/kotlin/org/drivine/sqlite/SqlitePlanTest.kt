package org.drivine.sqlite

import org.drivine.DrivineException
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `PersistenceManager.plan` on SQLite: `EXPLAIN QUERY PLAN` of what a Cypher statement compiles to, each row indented by its depth. */
class SqlitePlanTest {

    private val pm = SqliteTestDatabase.graphManager("planned")

    private fun plan(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
        pm.plan(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))

    @Test
    fun `each row's detail is indented two spaces for each row above it, and nothing runs`() {
        val plan = plan("MATCH (n:Planned) WHERE n.x = \$x RETURN n, COUNT { (n)-->() } AS c", mapOf("x" to 1))
        assertNull(plan.none)
        assertTrue(plan.lines.isNotEmpty())
        // The first row is at the top, and each row is at most one level below the one before it.
        assertEquals(0, plan.lines.first().takeWhile { it == ' ' }.length, plan.lines.joinToString("\n"))
        plan.lines.zipWithNext().forEach { (above, line) ->
            val depth = line.takeWhile { it == ' ' }.length
            assertTrue(depth % 2 == 0 && depth <= above.takeWhile { it == ' ' }.length + 2, plan.lines.joinToString("\n"))
        }
        assertTrue(plan.lines.any { it.startsWith("  ") }, plan.lines.joinToString("\n"))

        plan("CREATE (:Planned {x: 1})")
        assertEquals(0L, pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:Planned) RETURN count(n)"))).single())
    }

    @Test
    fun `SQL as it is is refused`() {
        assertFailsWith<DrivineException> { pm.plan(QuerySpecification.withStatement("SELECT 1")) }
    }
}
