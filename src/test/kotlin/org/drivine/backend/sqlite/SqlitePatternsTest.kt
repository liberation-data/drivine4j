package org.drivine.backend.sqlite

import org.drivine.cypher.api.FullTextSearch
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.SearchPlan
import org.drivine.cypher.api.SearchRequest
import org.drivine.cypher.api.SearchStore
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * The expressions the shared module's full-text search binds on SQLite are Postgres's, and the function drivine4j registers
 * ([SqliteFunctions.REGEXP_COUNT], through [SqlitePatterns]) counts them as Postgres does: a term written again at once each
 * time, and a word in any script as a word.
 */
class SqlitePatternsTest {

    /** How often the one term of [query] occurs in [text], by the count the module's search asks of SQLite for it. */
    private fun counted(query: String, text: String): Int {
        val plan = FullTextSearch.plan(SearchStore.SQLITE, GraphOptions("g"), SearchRequest(listOf("Doc"), listOf("body"), query)) as SearchPlan
        // The term's count is the first value the module binds, before whether a node holds it.
        val term = plan.parameters.getValue("_cy_ft1") as String
        return DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            SqliteFunctions.register(connection)
            connection.prepareStatement("SELECT ${SqliteFunctions.REGEXP_COUNT}(?, ?)").use { statement ->
                statement.setString(1, text)
                statement.setString(2, term)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    @Test
    fun `a term written again at once is counted each time, a word that goes on from it not at all`() {
        assertEquals(3, counted("kiwi", "kiwi,kiwi.kiwi!"))
        assertEquals(3, counted("kiwi", "kiwi kiwi kiwi"))
        assertEquals(0, counted("kiwi", "kiwis kiwi_kiwi kiwi2 akiwi"))
        assertEquals(2, counted("\"of soup\"", "of  soup, of -- soup."))
        assertEquals(3, counted("kiw*", "kiwi kiwis kiw akiwi"))
    }

    @Test
    fun `a word in any script is a word, so a letter after a term in another script goes on with it`() {
        assertEquals(2, counted("café", "café café cafés caféé"))
        assertEquals(2, counted("кот", "кот кот котик"))
    }
}
