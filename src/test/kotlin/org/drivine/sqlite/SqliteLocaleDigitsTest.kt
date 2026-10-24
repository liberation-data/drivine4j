package org.drivine.sqlite

import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

/**
 * A temporal value's text is written in ASCII digits whatever the JVM's default locale: a locale of its own digits,
 * as Thai's `nu-thai`, would write a text no store reads as a moment.
 */
class SqliteLocaleDigitsTest {

    private val pm = SqliteTestDatabase.graphManager("localedigits")

    @Test
    fun `a moment's text and drivine_to_char write ASCII digits under a locale of other digits`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"))
        try {
            assertEquals(
                listOf(listOf("10:30:05.500", 5L)),
                pm.query(QuerySpecification.withStatement(CypherStatement("RETURN [toString(localtime('10:30:05.5')), localtime('10:30:05.5').second]"))),
            )
            assertEquals(
                "10:30:05.123456",
                pm.query(QuerySpecification.withStatement(CypherStatement("RETURN toString(localtime('10:30:05.123456'))"))).single(),
            )
            assertEquals(
                "2024-03-15T10:30:05",
                pm.query(QuerySpecification.withStatement("SELECT drivine_to_char('2024-03-15T10:30:05.000000', 'YYYY-MM-DD\"T\"HH24:MI:SS')")).single(),
            )
            assertEquals(
                "2024-11-075",
                pm.query(QuerySpecification.withStatement("SELECT drivine_to_char('2024-03-15T10:30:05.000000', 'IYYY-IW-DDD')")).single(),
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
