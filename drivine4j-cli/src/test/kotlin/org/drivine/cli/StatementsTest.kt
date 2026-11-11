package org.drivine.cli

import org.junit.jupiter.api.Test
import java.io.Reader
import java.io.StringReader
import kotlin.test.assertEquals

/**
 * [Statements] splits an input as the contract's "Files" says: on `;`, but on none inside a string, a name in backticks
 * or a comment; with a command where a line starts with `:` where a statement would; and with nothing run of a statement
 * that is only spaces and comments.
 */
class StatementsTest {

    private fun split(text: String): List<Input> = Statements(StringReader(text)).asSequence().toList()

    private fun statements(vararg cypher: String) = cypher.map { Input.Statement(it) }

    @Test
    fun `statements are split on a semicolon, and the text after the last is one too`() {
        assertEquals(statements("RETURN 1", "RETURN 2"), split("RETURN 1; RETURN 2;"))
        assertEquals(statements("RETURN 1", "RETURN 2"), split("RETURN 1;\nRETURN 2"))
        assertEquals(statements("CREATE (n)\nRETURN n"), split("CREATE (n)\nRETURN n;\n"))
        assertEquals(emptyList(), split(""))
    }

    @Test
    fun `a semicolon in a string, either quote, doesn't split, and a backslash escapes the quote after it`() {
        assertEquals(statements("RETURN 'a;b'", "RETURN \"c;d\""), split("RETURN 'a;b'; RETURN \"c;d\";"))
        assertEquals(statements("RETURN 'it\\'s; still'"), split("RETURN 'it\\'s; still';"))
        assertEquals(statements("RETURN \"a\\\";b\""), split("RETURN \"a\\\";b\";"))
        // A string of the other quote holds this one: 'a"b' is a string, and its " opens nothing.
        assertEquals(statements("RETURN 'a\"b'", "RETURN 1"), split("RETURN 'a\"b'; RETURN 1"))
        // A backslash before a backslash escapes it, so the quote after them ends the string.
        assertEquals(statements("RETURN 'a\\\\'", "RETURN 1"), split("RETURN 'a\\\\'; RETURN 1"))
    }

    @Test
    fun `a semicolon in a backticked name doesn't split, and a backslash there escapes nothing`() {
        assertEquals(statements("RETURN 1 AS `a;b`", "RETURN 2"), split("RETURN 1 AS `a;b`; RETURN 2"))
        assertEquals(statements("RETURN 1 AS `a\\`", "RETURN 2"), split("RETURN 1 AS `a\\`; RETURN 2"))
        // A doubled backtick inside a name is Cypher's escape of one: the name goes on.
        assertEquals(statements("RETURN 1 AS `a``;b`"), split("RETURN 1 AS `a``;b`;"))
    }

    @Test
    fun `a semicolon in a comment doesn't split, and a quote in one opens no string`() {
        assertEquals(statements("RETURN 1 // one; two", "RETURN 2"), split("RETURN 1 // one; two\n; RETURN 2"))
        assertEquals(statements("RETURN 1 /* a; b */ + 1", "RETURN 2"), split("RETURN 1 /* a; b */ + 1; RETURN 2"))
        assertEquals(statements("RETURN 1 // it's\n+ 1", "RETURN 2"), split("RETURN 1 // it's\n+ 1; RETURN 2"))
        assertEquals(statements("RETURN 1 /* it's */", "RETURN 2"), split("RETURN 1 /* it's */; RETURN 2"))
        // A block comment's end is its first */: /* */* is the comment, then a *.
        assertEquals(statements("RETURN 2 /* a **/ * 3"), split("RETURN 2 /* a **/ * 3;"))
        // A / that opens no comment is a division.
        assertEquals(statements("RETURN 4 / 2", "RETURN 1"), split("RETURN 4 / 2; RETURN 1"))
    }

    @Test
    fun `a statement that is only spaces and comments isn't run`() {
        assertEquals(statements("RETURN 1"), split("RETURN 1;\n  // the end\n"))
        assertEquals(statements("RETURN 1"), split(";;  ; RETURN 1; /* nothing */ ;\r\n"))
        assertEquals(emptyList(), split("// only\n/* comments */\n"))
    }

    @Test
    fun `a line that starts with a colon where a statement would is a command, with no line break or carriage return`() {
        assertEquals(listOf(Input.Command(":begin"), Input.Statement("CREATE (n)"), Input.Command(":commit")), split(":begin\nCREATE (n);\n:commit\n"))
        assertEquals(listOf(Input.Command(":param x => 1"), Input.Statement("RETURN \$x")), split("  :param x => 1\r\nRETURN \$x;"))
        // After a statement that holds only comments, a colon still starts a command.
        assertEquals(listOf(Input.Command(":exit")), split("// first\n:exit"))
        // Inside a statement no ; has ended, or inside a string, a colon is the statement's.
        assertEquals(statements("MATCH (n)\n:Label RETURN n"), split("MATCH (n)\n:Label RETURN n;"))
        assertEquals(statements("RETURN '\n:begin'"), split("RETURN '\n:begin';"))
        // On a line after a statement's ;, it's a command again.
        assertEquals(listOf(Input.Statement("RETURN 1"), Input.Command(":exit")), split("RETURN 1;\n:exit"))
    }

    @Test
    fun `an unclosed string or comment runs to the end, as the store then refuses it`() {
        assertEquals(statements("RETURN 'a; b"), split("RETURN 'a; b"))
        assertEquals(statements("RETURN 1 /* a; b"), split("RETURN 1 /* a; b"))
    }

    @Test
    fun `each statement is given as soon as it ends, before the rest is read`() {
        var read = 0
        val text = "RETURN 1; RETURN 2;"
        val reader = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (read >= text.length) return -1
                cbuf[off] = text[read++]
                return 1
            }
            override fun close() {}
        }
        val inputs = Statements(reader)
        assertEquals(Input.Statement("RETURN 1"), inputs.next())
        assertEquals("RETURN 1;".length, read)
    }
}
