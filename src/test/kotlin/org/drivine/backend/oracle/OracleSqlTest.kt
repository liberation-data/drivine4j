package org.drivine.backend.oracle

import org.drivine.model.Stamps
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OracleSqlTest {

    @Test
    fun `a plain key is a bare step of the path, and one with a dot is quoted so it stays one key`() {
        assertEquals("'$.name'", OracleSql.path("name"))
        assertEquals("'$.\"metadata.source\"'", OracleSql.path("metadata.source"))
        assertEquals("'$.name[*]'", OracleSql.path("name", "[*]"))
    }

    @Test
    fun `a quote or a backslash in a key is escaped as JSON escapes it`() {
        assertEquals("'$.\"a\\\"b\\\\c\"'", OracleSql.path("a\"b\\c"))
    }

    @Test
    fun `a key that JSON reads as a value is quoted so it is read as a key`() {
        assertEquals("'$.\"true\"'", OracleSql.path("true"))
        assertEquals("'$.\"false\"'", OracleSql.path("false"))
        assertEquals("'$.\"null\"'", OracleSql.path("null"))
    }

    @Test
    fun `a key with a SQL quote in it is quoted in the path and the quote doubled in the literal`() {
        assertEquals("'$.\"it''s\"'", OracleSql.path("it's"))
    }

    @Test
    fun `an empty key is no property, and is refused`() {
        assertFailsWith<IllegalArgumentException> { OracleSql.path("") }
    }

    @Test
    fun `a literal doubles its quotes`() {
        assertEquals("'plain'", OracleSql.literal("plain"))
        assertEquals("'it''s'", OracleSql.literal("it's"))
        assertEquals("''", OracleSql.literal(""))
    }

    @Test
    fun `a label is looked for in the indexable form or the serialized form, quoted as JSON writes it`() {
        assertEquals("JSON_EXISTS(n.labels, '$[*]?(@ == \"Person\")')", OracleSql.hasLabel("n", "Person"))
        assertEquals("JSON_EXISTS(n.labels, '$[*]?(@ == \"A\\\"B\")')", OracleSql.hasLabel("n", "A\"B"))
        assertEquals("INSTR(JSON_SERIALIZE(n.labels), '\"Person\"') > 0", OracleSql.carriesLabel("n", "Person"))
        assertEquals("INSTR(JSON_SERIALIZE(n.labels), '\"A\\\"B\"') > 0", OracleSql.carriesLabel("n", "A\"B"))
    }

    @Test
    fun `no labels is no predicate, several are conjoined, and a scan takes the indexable form`() {
        assertNull(OracleSql.hasLabels("n", emptyList()))
        assertEquals(
            "${OracleSql.carriesLabel("n", "A")} AND ${OracleSql.carriesLabel("n", "B")}",
            OracleSql.hasLabels("n", listOf("A", "B")),
        )
        assertEquals(
            "${OracleSql.hasLabel("n", "A")} AND ${OracleSql.hasLabel("n", "B")}",
            OracleSql.hasLabels("n", listOf("A", "B"), scanned = true),
        )
    }

    @Test
    fun `the stamp is a column whatever kind it is asked as, and any other property is read from the JSON`() {
        assertEquals("n.stamp", OracleSql.property("n", Stamps.PROPERTY))
        assertEquals("n.stamp", OracleSql.property("n", Stamps.PROPERTY, SqlKind.NUMBER))
        assertEquals("JSON_VALUE(n.props, '$.age')", OracleSql.property("n", "age"))
        assertEquals("JSON_VALUE(n.props, '$.age' RETURNING NUMBER)", OracleSql.property("n", "age", SqlKind.NUMBER))
        assertEquals("JSON_VALUE(r.props, '$.at' RETURNING TIMESTAMP WITH TIME ZONE)", OracleSql.property("r", "at", SqlKind.INSTANT))
    }

    @Test
    fun `whether a property is there is a JSON test, and for the stamp a null test on its column`() {
        assertEquals("JSON_EXISTS(n.props, '$.age')", OracleSql.hasProperty("n", "age"))
        assertEquals("n.stamp IS NOT NULL", OracleSql.hasProperty("n", Stamps.PROPERTY))
    }

    @Test
    fun `each similarity has its distance and the score the graph engines give for it`() {
        assertEquals("COSINE", OracleSql.metric(SimilarityFunction.COSINE))
        assertEquals("EUCLIDEAN_SQUARED", OracleSql.metric(SimilarityFunction.EUCLIDEAN))
        assertEquals("(2 - d) / 2", OracleSql.score(SimilarityFunction.COSINE, "d"))
        assertEquals("1 / (1 + d)", OracleSql.score(SimilarityFunction.EUCLIDEAN, "d"))
    }

    @Test
    fun `the values of a bound array are rows of one column, typed as the kind`() {
        assertEquals("SELECT v FROM JSON_TABLE(?, '$[*]' COLUMNS (v NUMBER PATH '$'))", OracleSql.values("?", SqlKind.NUMBER))
        assertEquals("SELECT v FROM JSON_TABLE(:ids, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$'))", OracleSql.values(":ids", SqlKind.TEXT))
    }

    @Test
    fun `a counted statement is a block that returns its row count as one row`() {
        val block = OracleSql.counted("DELETE FROM t WHERE a = 1")
        val lines = block.lines()
        assertEquals("DECLARE", lines[0])
        assertEquals("    v_rows  SYS_REFCURSOR;", lines[1])
        assertEquals("    v_count NUMBER := 0;", lines[2])
        assertEquals("BEGIN", lines[3])
        assertEquals("    DELETE FROM t WHERE a = 1;", lines[4])
        assertEquals("    v_count := SQL%ROWCOUNT;", lines[5])
        assertEquals("    OPEN v_rows FOR SELECT v_count FROM dual;", lines[6])
        assertEquals("    DBMS_SQL.RETURN_RESULT(v_rows);", lines[7])
        assertEquals("END;", lines[8])
        assertEquals(9, lines.size)
    }

    @Test
    fun `a counting block declares what the body needs beside the count, each on its own indented line`() {
        val block = OracleSql.counting(
            """
            v_a := 1;
            v_count := v_a;
            """,
            declarations = """
            v_a NUMBER;

            v_b VARCHAR2(10);
            """,
        )
        val lines = block.lines()
        assertEquals(listOf("DECLARE", "    v_rows  SYS_REFCURSOR;", "    v_count NUMBER := 0;", "    v_a NUMBER;", "    v_b VARCHAR2(10);", "BEGIN"), lines.take(6))
        assertEquals(listOf("    v_a := 1;", "    v_count := v_a;"), lines.subList(6, 8))
        assertTrue(block.contains("    DBMS_SQL.RETURN_RESULT(v_rows);\nEND;"))
    }

    @Test
    fun `a value is compared as the kind it is`() {
        assertEquals(SqlKind.NUMBER, SqlKind.ofValue(3))
        assertEquals(SqlKind.NUMBER, SqlKind.ofValue(BigDecimal.TEN))
        assertEquals(SqlKind.INSTANT, SqlKind.ofValue(Instant.EPOCH))
        assertEquals(SqlKind.INSTANT, SqlKind.ofValue(ZonedDateTime.now()))
        assertEquals(SqlKind.INSTANT, SqlKind.ofValue(OffsetDateTime.now()))
        assertEquals(SqlKind.INSTANT, SqlKind.ofValue(Date()))
        assertEquals(SqlKind.LOCAL_DATE_TIME, SqlKind.ofValue(LocalDateTime.MIN))
        assertEquals(SqlKind.LOCAL_DATE, SqlKind.ofValue(LocalDate.MIN))
        assertEquals(SqlKind.TEXT, SqlKind.ofValue("ada"))
        assertEquals(SqlKind.TEXT, SqlKind.ofValue(true))
        assertEquals(SqlKind.TEXT, SqlKind.ofValue(null))
    }

    @Test
    fun `a collection is compared as its first value that is not null, and an empty one as text`() {
        assertEquals(SqlKind.NUMBER, SqlKind.ofValue(listOf(null, 2, "x")))
        assertEquals(SqlKind.LOCAL_DATE, SqlKind.ofValue(setOf(LocalDate.MIN)))
        assertEquals(SqlKind.TEXT, SqlKind.ofValue(emptyList<Any>()))
        assertEquals(SqlKind.TEXT, SqlKind.ofValue(listOf(null)))
    }

    @Test
    fun `a field is ordered as its type, a primitive number as a number and a primitive boolean as text`() {
        assertEquals(SqlKind.NUMBER, SqlKind.ofType(java.lang.Long::class.java))
        assertEquals(SqlKind.NUMBER, SqlKind.ofType(Int::class.javaPrimitiveType))
        assertEquals(SqlKind.NUMBER, SqlKind.ofType(Double::class.javaPrimitiveType))
        assertEquals(SqlKind.TEXT, SqlKind.ofType(Boolean::class.javaPrimitiveType))
        assertEquals(SqlKind.TEXT, SqlKind.ofType(Char::class.javaPrimitiveType))
        assertEquals(SqlKind.INSTANT, SqlKind.ofType(Instant::class.java))
        assertEquals(SqlKind.INSTANT, SqlKind.ofType(java.sql.Timestamp::class.java))
        assertEquals(SqlKind.LOCAL_DATE_TIME, SqlKind.ofType(LocalDateTime::class.java))
        assertEquals(SqlKind.LOCAL_DATE, SqlKind.ofType(LocalDate::class.java))
        assertEquals(SqlKind.TEXT, SqlKind.ofType(String::class.java))
        assertEquals(SqlKind.TEXT, SqlKind.ofType(null))
    }

    @Test
    fun `each kind has the column type a JSON_TABLE reads it as`() {
        assertEquals("VARCHAR2(4000)", SqlKind.TEXT.column)
        assertEquals("NUMBER", SqlKind.NUMBER.column)
        assertEquals("TIMESTAMP WITH TIME ZONE", SqlKind.INSTANT.column)
        assertEquals("TIMESTAMP", SqlKind.LOCAL_DATE_TIME.column)
        assertEquals("DATE", SqlKind.LOCAL_DATE.column)
    }
}
