package org.drivine.tck

import org.drivine.DrivineException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The store's refusal of a fraction of a second past the microsecond is refused on purpose (drivine4j#421, until
 * drivine-cypher-spec#302): by its words on SQLite and Postgres, and on Oracle, which reports only its first letter, by the
 * query's giving such a fraction.
 */
class RefusalTest {

    private val words = "A fraction of a second past the microsecond isn't kept by the store: 12:31:14.645876123"
    private val oracle = "ORA-01722: unable to convert string value containing 'A' to a number: "

    @Test
    fun `a query gives a fraction past the microsecond by a nanosecond field or a text of more than six places`() {
        assertTrue(subMicrosecond("RETURN localtime({hour: 12, nanosecond: 645876123})"))
        assertTrue(subMicrosecond("RETURN duration({nanoseconds: 1})"))
        assertTrue(subMicrosecond("RETURN duration({seconds: -2, nanoseconds: -1})"), "a negative field")
        assertFalse(subMicrosecond("RETURN duration({nanoseconds: -2000})"), "a negative whole number of microseconds")
        assertTrue(subMicrosecond("RETURN duration({nanoseconds: 99999999999999999999})"), "a field past a Long")
        assertTrue(subMicrosecond("RETURN localtime('12:31:14.645876123')"))
        assertTrue(subMicrosecond("RETURN duration('PT0.000000001S')"))
        assertFalse(subMicrosecond("RETURN localtime({hour: 12, nanosecond: 645876000})"))
        assertFalse(subMicrosecond("RETURN localtime('12:31:14.645876000')"))
        assertFalse(subMicrosecond("RETURN localtime('12:31:14.645876')"))
        assertTrue(subMicrosecond("RETURN localtime('123114.645876123')"), "a time's basic form")
        assertTrue(subMicrosecond("RETURN datetime('2015-07-21T21:40:32.142000001+0100')"))
        assertTrue(subMicrosecond("RETURN duration('P1DT2H3M14.645876123S')"))
        assertTrue(subMicrosecond("RETURN localtime(\$t)  PARAMS {t=12:31:14.645876123}"), "a parameter's text")
        assertFalse(subMicrosecond("RETURN duration('PT0.000001S')"))
        assertFalse(subMicrosecond("RETURN 3.1415926535"), "a number's places of no temporal value")
        assertFalse(subMicrosecond("UNWIND [0.0999999, 0.00000001, 10.12345678] AS f RETURN f"), "a number's seven to nine places")
        assertFalse(subMicrosecond("RETURN '0.0999999'"), "a text that is no time")
        assertFalse(subMicrosecond("RETURN 1234567.1234567"), "a number of seven digits before its places")
        assertFalse(subMicrosecond("RETURN {num:12.1234567}"), "a number after a map key's colon")
    }

    @Test
    fun `the refusal is refused on purpose by its words or on Oracle by its letter beside such a query`() {
        assertTrue(isRefusal(DrivineException(words)))
        assertTrue(
            isRefusal(DrivineException("[SQLITE_ERROR] SQL error or missing database ('12:31:14.645876 past the microsecond (drivine4j#421)' is no temporal value")),
            "SQLite's read of the words written in place of the fraction",
        )
        assertTrue(isRefusal(DrivineException(oracle), "RETURN localtime({hour: 12, nanosecond: 645876123})"))
        assertFalse(isRefusal(DrivineException(oracle), "RETURN localtime({hour: 12, nanosecond: 645876000})"), "another failure by name")
        assertFalse(isRefusal(DrivineException(oracle)), "no query to tell it by")
        assertFalse(isRefusal(DrivineException(oracle), "UNWIND [0.00000001] AS f RETURN toInteger(f)"), "a number's places")
    }

    @Test
    fun `a setup query or a statement that the store refuses so is refused on purpose, by its own query`() {
        val query = "CREATE ({t: localtime({hour: 12, nanosecond: 645876123})})"
        assertEquals("refused-setup", errorVerdict(DrivineException(oracle), "setup", query).kind)
        assertEquals(Outcome.REFUSED, errorVerdict(DrivineException(words), "query").outcome)
        assertEquals(Outcome.WRONG_ERROR, errorVerdict(DrivineException(oracle), "query", "RETURN 1").outcome, "another failure by name")
    }
}
