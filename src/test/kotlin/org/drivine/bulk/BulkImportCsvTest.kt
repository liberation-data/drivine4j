package org.drivine.bulk

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.DriverManagerDataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a bulk import (liberation-data/drivine4j#684) hands Postgres's `COPY`, with no store: each line of CSV, and the
 * reader the driver reads the lines through; and a graph it refuses before it connects.
 */
class BulkImportCsvTest {

    @Test
    fun `a graph whose name cannot name tables is refused as the import is made, before it connects`() {
        val provider = JdbcConnectionProvider("bulk", DatabaseType.POSTGRES, DriverManagerDataSource("jdbc:drivine-none:"), graphName = "no-tables")
        val refused = assertThrows<DrivineException> { BulkImport(provider) }
        assertTrue("'no-tables' cannot name a graph" in refused.message!!, refused.message)
    }

    @Test
    fun `each field is quoted, a quote in it doubled, so a comma, a line end and a backslash are its own`() {
        assertEquals(
            "\"{\"\"a\"\": \"\"x,\\\"\"y\\\\n\"\"}\",\"\",\"two\nlines\"\n",
            BulkImport.csv("{\"a\": \"x,\\\"y\\\\n\"}", "", "two\nlines"),
        )
    }

    /** Everything [reader] gives, read [size] characters at a time, and the length of each read. */
    private fun readAll(reader: java.io.Reader, size: Int): kotlin.Pair<String, List<Int>> {
        val buffer = CharArray(size + 2)
        val reads = mutableListOf<Int>()
        val text = StringBuilder()
        while (true) {
            val n = reader.read(buffer, 1, size)
            if (n < 0) break
            reads += n
            text.appendRange(buffer, 1, 1 + n)
        }
        return text.toString() to reads
    }

    @Test
    fun `the lines are read one after another, empty ones skipped, into the buffer at its offset`() {
        val (text, reads) = readAll(JdbcConnection.LinesReader(listOf("abc\n", "", "defgh\n", "i\n").iterator()), 3)
        assertEquals("abc\ndefgh\ni\n", text)
        assertEquals(listOf(3, 1, 3, 3, 2), reads)
        assertEquals(-1, JdbcConnection.LinesReader(emptyList<String>().iterator()).read(CharArray(4), 0, 4))
    }

    @Test
    fun `a read never ends between the two halves of a surrogate pair, which the driver would encode apart`() {
        val line = "ab😀c\n"
        val (text, reads) = readAll(JdbcConnection.LinesReader(listOf(line).iterator()), 3)
        assertEquals(line, text)
        assertEquals(listOf(2, 3, 1), reads)
        // Encoded read by read, as the driver encodes them, the pair is kept.
        val chunks = JdbcConnection.LinesReader(listOf(line).iterator()).let { r ->
            generateSequence { CharArray(3).let { b -> r.read(b, 0, 3).takeIf { it >= 0 }?.let { String(b, 0, it) } } }.toList()
        }
        assertEquals(line, chunks.joinToString("") { String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) })
    }
}
