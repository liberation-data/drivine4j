package org.drivine.backend.sql

/**
 * A full-text query as a SQL store is asked it: the terms that score a node, those it must hold and
 * those it must not, each a regular expression that matches the term as a whole word in lower-cased
 * text, which Oracle without a text index and Postgres both count. Through an Oracle Text index it is
 * [oracleText].
 *
 * It reads the plain core of the Lucene syntax the graph engines take: words, a `"quoted phrase"`,
 * `+must`, `-not` and `NOT not`, a `prefix*`, and `field:word` as the word. `AND` makes the word
 * after it one a node must hold; `OR` is the default between words. Anything else is read as text.
 *
 * A backslash before a character that is not part of a word makes that character text: `\+must` is
 * not required, `prefix\*` is not the start of a word, `\"` does not open or close a phrase, the colon
 * of `field\:word` does not end a field, and `two\ words` is one term. Before a letter, a digit or an
 * underscore there is nothing to make text, so the backslash is itself text, and a gap between words
 * as any punctuation is: `DOMAIN\user` is the phrase `domain user`.
 *
 * A term of several words is those words in turn, however it came to hold them: `soup-graph` and
 * `soup\ graph` are each the phrase `soup graph`, where a graph engine may look for either word.
 */
internal class TextQuery private constructor(
    val scored: List<String>,
    val required: List<String>,
    val excluded: List<String>,
    /**
     * The words of each term of [scored], in turn: a node whose text matches the term holds each of them,
     * so a store may narrow what it counts to the nodes that hold them all, by an index of their text.
     */
    val scoredWords: List<List<String>>,
    /**
     * The same query for an Oracle Text index: the scoring terms accumulated, each required one
     * conjoined and each excluded one taken away. A word is in braces, so it is a word and never one
     * of Oracle Text's operators.
     */
    val oracleText: String,
) {

    companion object {
        /** What stands between two words: a run of anything but letters, digits and underscores. */
        private val GAP = Regex("[^\\p{L}\\p{N}_]+")

        /** The characters that end a term. */
        private const val SPACES = " \t\n\u000B\u000C\r"

        private const val BOUNDARY_BEFORE = "(^|[^[:alnum:]_])"
        private const val BOUNDARY_AFTER = "($|[^[:alnum:]_])"

        /** Whether [pattern], a term's expression, is a prefix term's: one whose last word is the start of a word, `kiw*`. */
        fun isPrefix(pattern: String): Boolean = !pattern.endsWith(BOUNDARY_AFTER)

        /** What [BOUNDARY_AFTER] holds to, without taking the character it is: Postgres's and Java's lookahead. */
        private const val BOUNDARY_AHEAD = "(?=\$|[^[:alnum:]_])"

        /**
         * [pattern], a term's expression, ending where its last word does, the character after it held to but not taken:
         * counted, a term written again at once still has the character before it (drivine4j#607). Taken, "kiwi kiwi kiwi"
         * counted twice. For a store whose expressions look ahead.
         */
        fun lookingAhead(pattern: String): String =
            if (pattern.endsWith(BOUNDARY_AFTER)) pattern.removeSuffix(BOUNDARY_AFTER) + BOUNDARY_AHEAD else pattern

        /**
         * One character between words, as a group: for a store whose expressions don't look ahead, Oracle's. A term's
         * expression takes the character after it, which a term written again at once needs before it (drivine4j#607). Each
         * such character of the text written twice (`\1\1`), one is there for each, so the term's expression counts in it as
         * [lookingAhead] counts in the text. It is the slow way, a fifth as fast as [counted]'s over 100,000 texts on Oracle,
         * kept for the terms [counted] can't count.
         */
        const val SEPARATOR = "([^[:alnum:]_])"

        /**
         * For a store whose expressions don't look ahead, Oracle's: [pattern] ending where its last word does, and that
         * expression holding a letter, a digit or an underscore after it. The term occurs as many times as the first matches
         * less the second, and neither takes the character before the next, so it counts as [lookingAhead] does: twice as
         * slow as the one count that took it, where [SEPARATOR]'s way is five times. Null for the second where the term is
         * the start of a word, which may go on.
         *
         * Null where the term is a phrase that repeats a word, or whose last word starts its first. There an occurrence of
         * it may begin inside another, "la la" in "la la land" or "kiwis kiwi" in "kiwis kiwis kiwi", and the two counts take
         * different ones: the second took the "la la" of "la land", the first did not, and the term counted none. Any other
         * term begins inside none of its occurrences: one that did would begin at a word of it, which its first word is not.
         *
         * [pattern] itself, with null for the second, where a word holds a number that isn't a digit, the "²" of "b²b": a
         * word's to [GAP] but not to `[[:alnum:]]`, so an occurrence may begin at it, "b²b" in "b²b²bx", and neither way
         * above counts it. The one count undercounts the term only where it is written again at once, as it did before.
         */
        fun counted(pattern: String): Pair<String, String?>? {
            if (!pattern.endsWith(BOUNDARY_AFTER)) return pattern to null
            val ended = pattern.removeSuffix(BOUNDARY_AFTER)
            val words = ended.removePrefix(BOUNDARY_BEFORE).split(BETWEEN)
            if (words.any { word -> word.codePoints().anyMatch { !Character.isLetter(it) && !Character.isDigit(it) && it != '_'.code } }) {
                return pattern to null
            }
            val overlaps = words.toSet().size < words.size || words.size > 1 && words.first().startsWith(words.last())
            return if (overlaps) null else ended to "$ended[[:alnum:]_]"
        }

        /** What stands between the words of a phrase in a term's expression. */
        private const val BETWEEN = "[^[:alnum:]_]+"

        /**
         * Reads [query]. It must name at least one term that scores: a query of operators and
         * punctuation alone is refused with an [IllegalArgumentException].
         */
        fun parse(query: String): TextQuery {
            val scored = mutableListOf<String>()
            val scoredWords = mutableListOf<List<String>>()
            val required = mutableListOf<String>()
            val excluded = mutableListOf<String>()
            val scoredText = mutableListOf<String>()
            val requiredText = mutableListOf<String>()
            val excludedText = mutableListOf<String>()
            var must = false
            var not = false
            Scanner(query).tokens().forEach { token ->
                when (token.operator) {
                    "AND", "&&" -> { must = true; return@forEach }
                    "OR", "||" -> return@forEach
                    "NOT", "!" -> { not = true; return@forEach }
                }
                val term = Term.of(token)
                if (term != null) {
                    when {
                        token.sign == '-' || not -> { excluded += term.pattern; excludedText += term.oracleText }
                        token.sign == '+' || must -> {
                            required += term.pattern; scored += term.pattern; scoredWords += term.words; requiredText += term.oracleText; scoredText += term.oracleText
                        }
                        else -> { scored += term.pattern; scoredWords += term.words; scoredText += term.oracleText }
                    }
                }
                // An operator applies to the token after it and to no other, whether or not that token holds a word.
                must = false
                not = false
            }
            require(scored.isNotEmpty()) { "A full-text query needs a word to look for: '$query' has none." }
            val text = (listOf("(" + scoredText.joinToString(" ACCUM ") + ")") + requiredText).joinToString(" AND ") +
                excludedText.joinToString("") { " NOT $it" }
            return TextQuery(scored, required, excluded, scoredWords, text)
        }
    }

    /** One character of a query, and whether a backslash before it made it text. */
    private class Symbol(val char: Char, val escaped: Boolean) {
        val isSpace: Boolean get() = !escaped && char in SPACES

        /** Whether this is [other] with its meaning in the syntax, which a backslash takes away. */
        fun isBare(other: Char) = !escaped && char == other
    }

    /** One term as it is written: its [sign], whether it stood in quotes, and its characters after any field. */
    private class Token(val sign: Char?, val quoted: Boolean, val symbols: List<Symbol>) {

        /** The token as written when it is a word with no backslash in it, which is what an operator is. */
        val operator: String?
            get() = if (quoted || symbols.any { it.escaped }) null else String(CharArray(symbols.size) { symbols[it].char })
    }

    /**
     * Reads a query from left to right, one character at a time. This is the one place that says what
     * a backslash, a quote, a sign and a field's colon are.
     */
    private class Scanner(query: String) {
        private val symbols = symbols(query)
        private val lastQuote = symbols.indexOfLast { it.isBare('"') }
        private val lastEscapedQuote = symbols.indexOfLast { it.escaped && it.char == '"' }
        private var at = 0

        fun tokens(): List<Token> = generateSequence(::next).toList()

        /** A sign, a field the term is scoped to (which is read as the term), then a quoted phrase or a word. */
        private fun next(): Token? {
            while (at < symbols.size && symbols[at].isSpace) at++
            if (at == symbols.size) return null
            // A sign is one only when a term follows it.
            val sign = symbols[at].takeIf { (it.isBare('+') || it.isBare('-')) && hasText(at + 1) }?.char
            if (sign != null) at++
            at = pastField(at)
            phrase(sign)?.let { return it }
            val start = at
            while (hasText(at)) at++
            return Token(sign, quoted = false, symbols.subList(start, at))
        }

        private fun hasText(index: Int) = index < symbols.size && !symbols[index].isSpace

        /**
         * Where the term starts when a field stands at [from]: after the colon that ends the field.
         * [from] itself when no field does, which is when nothing stands before the colon, a quote
         * does, or nothing follows it.
         */
        private fun pastField(from: Int): Int {
            var colon = from
            while (hasText(colon) && !symbols[colon].isBare(':') && !symbols[colon].isBare('"')) colon++
            val isField = colon > from && hasText(colon) && symbols[colon].isBare(':') && hasText(colon + 1)
            return if (isField) colon + 1 else from
        }

        /** The phrase that opens where the scanner stands. Null when no quote opens one there or none closes it. */
        private fun phrase(sign: Char?): Token? {
            if (at >= symbols.size || !symbols[at].isBare('"')) return null
            val open = at
            val close = if (open < lastQuote) (open + 1..lastQuote).first { symbols[it].isBare('"') } else null
            if (close != null) {
                at = close + 1
                return Token(sign, quoted = true, symbols.subList(open + 1, close))
            }
            // No quote closes the phrase while `\"` is a quote of text. The first `\"` is then read as
            // a backslash of text and the quote that closes the phrase, so `"C:\"` is the phrase `C:\`.
            // This is read only when no quote without a backslash stands later in the query. When one
            // does, it closes the phrase, wherever it is: in `"C:\" and "D:\"` the phrase runs to the
            // quote before `D`.
            if (open >= lastEscapedQuote) return null
            val escaped = (open + 1..lastEscapedQuote).first { symbols[it].escaped && symbols[it].char == '"' }
            at = escaped + 1
            return Token(sign, quoted = true, symbols.subList(open + 1, escaped) + Symbol('\\', escaped = false))
        }

        companion object {
            /**
             * The characters of [query]. A backslash and a character after it that is not part of a
             * word are that one character, as text. Any other backslash is a character itself.
             */
            private fun symbols(query: String): List<Symbol> = buildList {
                var at = 0
                while (at < query.length) {
                    val next = query.getOrNull(at + 1)
                    if (query[at] == '\\' && next != null && GAP.matches(next.toString())) {
                        add(Symbol(next, escaped = true))
                        at += 2
                    } else {
                        add(Symbol(query[at], escaped = false))
                        at++
                    }
                }
            }
        }
    }

    /** One term of a query: its [words] in turn, the last of them the start of a word when [prefix]. */
    private class Term(val words: List<String>, prefix: Boolean) {

        /** The term for Oracle Text: its words as a phrase, or when [prefix] the last as the start of a word. */
        val oracleText: String = if (prefix) {
            (words.dropLast(1).map { "{$it}" } + "${words.last()}%").joinToString(" ")
        } else {
            "{${words.joinToString(" ")}}"
        }

        /** The expression for the term as whole words; when [prefix], the last as the start of one. */
        val pattern: String = BOUNDARY_BEFORE + words.joinToString(BETWEEN) + (if (prefix) "" else BOUNDARY_AFTER)

        companion object {
            /**
             * Reads [token]. Null when it holds no word.
             *
             * A word still holds a field up to its first colon that no backslash precedes: the second
             * field of `a:b:word`. The term is the start of a word when a `*` that no backslash
             * precedes is its last character.
             */
            fun of(token: Token): Term? {
                val text = if (token.quoted) token.symbols else token.symbols.drop(token.symbols.indexOfFirst { it.isBare(':') } + 1)
                val words = String(CharArray(text.size) { text[it].char }).lowercase().split(GAP).filter { it.isNotEmpty() }
                return if (words.isEmpty()) null else Term(words, prefix = text.last().isBare('*'))
            }
        }
    }
}
