package org.drivine.query.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.proposition.PropositionViewQueryDsl

/** Unit coverage for the `limit` / `skip` DSL methods on [GraphQuerySpec]. */
class LimitSkipDslTest {

    private fun spec() = GraphQuerySpec(PropositionViewQueryDsl.INSTANCE)

    @Test
    fun `limit and skip store their values`() {
        val s = spec()
        assertNull(s.limit)
        assertNull(s.skip)
        s.limit(20)
        s.skip(40)
        assertEquals(20, s.limit)
        assertEquals(40, s.skip)
    }

    @Test
    fun `negative limit or skip is rejected`() {
        assertThrows<IllegalArgumentException> { spec().limit(-1) }
        assertThrows<IllegalArgumentException> { spec().skip(-1) }
    }

    @Test
    fun `limit(0) is allowed`() {
        assertEquals(0, spec().apply { limit(0) }.limit)
    }
}
