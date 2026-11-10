package org.drivine.backend.sql

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How [StatementScope] binds the values of one statement: its own, and those another writer named. */
class StatementScopeTest {

    @Test
    fun `values bound under another writer's names sit beside the scope's own, which are numbered on past them`() {
        val scope = StatementScope(30)
        val first = scope.bound("a")
        scope.boundAs(mapOf("_cy_ft1" to "graph", "_cy_ft2" to 8))
        val next = scope.bound("b")

        assertEquals("\$_p0", first)
        assertEquals("\$_p3", next)
        assertEquals(mapOf<String, Any?>("_p0" to "a", "_cy_ft1" to "graph", "_cy_ft2" to 8, "_p3" to "b"), scope.bindings())
    }

    @Test
    fun `a name bound already is refused, not overwritten`() {
        val scope = StatementScope(30)
        scope.boundAs(mapOf("_cy_ft1" to "graph"))

        val refused = assertFailsWith<IllegalArgumentException> { scope.boundAs(mapOf("_cy_ft1" to "other")) }
        assertEquals("The parameter _cy_ft1 is bound already.", refused.message)
        assertEquals(mapOf<String, Any?>("_cy_ft1" to "graph"), scope.bindings())

        scope.bound("a")
        assertFailsWith<IllegalArgumentException> { scope.boundAs(mapOf("_p1" to "clash")) }
    }
}
