package org.drivine.test

/**
 * Cuts a fixture's text into the tokens [MiniCypherParser] reads: words, names in backticks, string
 * literals with their escapes read, numbers and symbols. Space between tokens is dropped, and each
 * token keeps where it stood, so a refusal can say where.
 *
 * It knows no grammar. A word is a word whether the parser will take it for a keyword, a variable
 * or a function, and a character it has no other use for is a symbol the parser refuses where it
 * meets it. A `-` is a symbol on its own, the sign of a number as much as the dash of a
 * relationship, and so is the `<` of an arrow that points left: which it is depends on where it
 * stands, and that is the parser's to say. What the lexer itself refuses is what cannot be cut at
 * all: a string or a quoted name that does not end, an escape Cypher does not have, and digits that
 * are no number.
 */
internal class MiniCypherLexer(private val text: String) {

    /** What a [Token] is, as far as its characters say. */
    enum class Kind {
        /** Letters, digits and underscores, not opening with a digit: a keyword, a name, `true`. */
        WORD,

        /** A name written in backticks, which may hold any character and is never a keyword. */
        QUOTED_NAME,

        /** A string literal; the token's text is the string itself, its quotes gone and its escapes read. */
        STRING,

        /** The digits of a number, with any point or exponent, and without a sign. */
        NUMBER,

        /** A character with a meaning of its own, or one of `<>`, `<=` and `>=`. */
        SYMBOL,

        /** The end of the text; the last token, and there is always one. */
        END,
    }

    /** One piece of a fixture: its [kind], its [text], and the [position] of its first character in the fixture. */
    data class Token(val kind: Kind, val text: String, val position: Int) {

        /** Whether this is the word [word], as a keyword is written; a name in backticks is never one. */
        fun isWord(word: String): Boolean = kind == Kind.WORD && text == word

        fun isSymbol(symbol: String): Boolean = kind == Kind.SYMBOL && text == symbol

        /** Whether this can name a variable, a label, a property or a function. */
        val isName: Boolean get() = kind == Kind.WORD || kind == Kind.QUOTED_NAME
    }

    private var at = 0

    /** The tokens of the text in order, closed by one of [Kind.END]. */
    fun tokens(): List<Token> = buildList {
        while (true) {
            while (at < text.length && text[at].isWhitespace()) at++
            val start = at
            val c = text.getOrNull(at) ?: break
            add(
                when {
                    c == '\'' || c == '"' -> Token(Kind.STRING, string(c), start)
                    c == '`' -> Token(Kind.QUOTED_NAME, quotedName(), start)
                    c.isDigit() -> Token(Kind.NUMBER, number(), start)
                    c.isLetter() || c == '_' -> Token(Kind.WORD, word(), start)
                    else -> Token(Kind.SYMBOL, symbol(), start)
                }
            )
        }
        add(Token(Kind.END, "", text.length))
    }

    /** A string literal, its escapes read as Cypher reads them. */
    private fun string(quote: Char): String {
        val start = at++
        val out = StringBuilder()
        while (true) {
            val c = text.getOrNull(at++) ?: expected("the end of a string", start)
            when {
                c == quote -> return out.toString()
                c != '\\' -> out.append(c)
                else -> when (val escaped = text.getOrNull(at++) ?: expected("an escape", at - 2)) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'u' -> {
                        val digits = text.substring(at, minOf(at + 4, text.length))
                        if (digits.length < 4 || digits.any { it.digitToIntOrNull(16) == null }) expected("four hex digits", at - 2)
                        out.append(digits.toInt(16).toChar())
                        at += 4
                    }
                    '\\', '\'', '"' -> out.append(escaped)
                    else -> expected("a string escape", at - 2)
                }
            }
        }
    }

    private fun quotedName(): String {
        val end = text.indexOf('`', at + 1)
        if (end < 0) expected("the end of a quoted name", at)
        return text.substring(at + 1, end).also { at = end + 1 }
    }

    /** The digits of a number, taken with every point and exponent mark that follows them, and refused if they are no number. */
    private fun number(): String {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in ".eE")) at++
        return text.substring(start, at).also { numberOf(it) ?: expected("a number", start) }
    }

    private fun word(): String {
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        return text.substring(start, at)
    }

    private fun symbol(): String {
        val two = TWO_CHARACTERS.firstOrNull { text.startsWith(it, at) }
        at += two?.length ?: 1
        return two ?: text[at - 1].toString()
    }

    private fun expected(what: String, position: Int): Nothing = throw miniCypherRefusal(text, position, "Expected $what")

    private companion object {
        /** The comparisons written with two characters. No arrow is among them: `<-1` is a comparison with a negative number. */
        val TWO_CHARACTERS = listOf("<>", "<=", ">=")
    }
}

/** The number [digits] write: a `Long` when they are a whole number, a `Double` when not, and null when they are no number. */
internal fun numberOf(digits: String): Number? = digits.toLongOrNull() ?: digits.toDoubleOrNull()

/**
 * The refusal of a fixture, saying what is wrong, the [position] in [text] it is wrong at, the
 * text that stands there, and the whole statement.
 */
internal fun miniCypherRefusal(text: String, position: Int, message: String): IllegalArgumentException =
    IllegalArgumentException("$message at $position: ${text.substring(position.coerceAtMost(text.length)).take(40)}… in: $text")
