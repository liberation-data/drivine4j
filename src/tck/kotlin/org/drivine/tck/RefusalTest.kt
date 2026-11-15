package org.drivine.tck

import org.drivine.DrivineException
import org.junit.jupiter.api.Test
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
        assertTrue(subMicrosecond("RETURN localtime('12:31:14.645876123')"))
        assertTrue(subMicrosecond("RETURN duration('PT0.000000001S')"))
        assertFalse(subMicrosecond("RETURN localtime({hour: 12, nanosecond: 645876000})"))
        assertFalse(subMicrosecond("RETURN localtime('12:31:14.645876000')"))
        assertFalse(subMicrosecond("RETURN localtime('12:31:14.645876')"))
        assertFalse(subMicrosecond("RETURN 3.1415926535"), "a number's places of no temporal value")
    }

    @Test
    fun `the refusal is refused on purpose by its words or on Oracle by its letter beside such a query`() {
        assertTrue(isRefusal(DrivineException(words)))
        assertTrue(isRefusal(DrivineException(oracle), "RETURN localtime({hour: 12, nanosecond: 645876123})"))
        assertFalse(isRefusal(DrivineException(oracle), "RETURN localtime({hour: 12, nanosecond: 645876000})"), "another failure by name")
        assertFalse(isRefusal(DrivineException(oracle)), "no query to tell it by")
    }
}
