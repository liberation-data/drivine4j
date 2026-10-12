package org.drivine.backend.oracle

/**
 * A full-text query as Oracle is asked it. Without a text index it is the terms that score a node,
 * those it must hold and those it must not, each a regular expression that matches the term as a
 * whole word in lower-cased text. Through an Oracle Text index it is [oracleText].
 *
 * It reads the plain core of the Lucene syntax the graph engines take: words, a `"quoted phrase"`,
 * `+must`, `-not` and `NOT not`, a `prefix*`, and `field:word` as the word. `AND` makes the word
 * after it one a node must hold; `OR` is the default between words. Anything else is read as text.
 *
 * A backslash makes the character after it text, as it does in Lucene: `\+must` is not required,
 * `prefix\*` is not the start of a word, `\"` does not open or close a phrase, and the colon of
 * `field\:word` does not end a field.
 */
internal class TextQuery private constructor(
    val scored: List<String>,
    val required: List<String>,
    val excluded: List<String>,
    /**
     * The same query for an Oracle Text index: the scoring terms accumulated, each required one
     * conjoined and each excluded one taken away. A word is in braces, so it is a word and never one
     * of Oracle Text's operators.
     */
    val oracleText: String,
) {

    companion object {
        /**
         * A sign, a field the term is scoped to (which is read as the term), then a quoted phrase or a
         * word. A backslash and the character after it are one character of text wherever they stand.
         */
        private val TOKEN = Regex("""([+-]?)(?:(?:\\.|[^\s:"\\])+:)?(?:"((?:\\.|[^"\\])*)"|((?:\\.|\S)+))""")

        /** What a word still holds up to its first colon that no backslash precedes: a second field. */
        private val FIELD = Regex("""^(?:\\.|[^:\\])*:""")

        /** A `*` that ends the text and that no backslash makes text. An even run of backslashes before it is backslashes. */
        private val STAR_AT_END = Regex("""(?<!\\)(?:\\\\)*\*$""")

        /** A backslash and the character it makes text. */
        private val ESCAPED = Regex("""\\(.)""", RegexOption.DOT_MATCHES_ALL)

        private const val BOUNDARY_BEFORE = "(^|[^[:alnum:]_])"
        private const val BOUNDARY_AFTER = "($|[^[:alnum:]_])"

        /**
         * Reads [query]. It must name at least one term that scores: a query of operators and
         * punctuation alone is refused with an [IllegalArgumentException].
         */
        fun parse(query: String): TextQuery {
            val scored = mutableListOf<String>()
            val required = mutableListOf<String>()
            val excluded = mutableListOf<String>()
            val scoredText = mutableListOf<String>()
            val requiredText = mutableListOf<String>()
            val excludedText = mutableListOf<String>()
            var must = false
            var not = false
            TOKEN.findAll(query).forEach { token ->
                val (sign, phrase, word) = token.destructured
                when (word) {
                    "AND", "&&" -> { must = true; return@forEach }
                    "OR", "||" -> return@forEach
                    "NOT", "!" -> { not = true; return@forEach }
                }
                val term = Term.of(if (token.groups[2] != null) phrase else word.replaceFirst(FIELD, "")) ?: return@forEach
                val pattern = term.pattern
                when {
                    sign == "-" || not -> { excluded += pattern; excludedText += term.oracleText }
                    sign == "+" || must -> { required += pattern; scored += pattern; requiredText += term.oracleText; scoredText += term.oracleText }
                    else -> { scored += pattern; scoredText += term.oracleText }
                }
                must = false
                not = false
            }
            require(scored.isNotEmpty()) { "A full-text query needs a word to look for: '$query' has none." }
            val text = (listOf("(" + scoredText.joinToString(" ACCUM ") + ")") + requiredText).joinToString(" AND ") +
                excludedText.joinToString("") { " NOT $it" }
            return TextQuery(scored, required, excluded, text)
        }

    }

    /** One term of a query: its [words] in turn, the last of them the start of a word when [prefix]. */
    private class Term(private val words: List<String>, private val prefix: Boolean) {

        /** The term for Oracle Text: its words as a phrase, or when [prefix] the last as the start of a word. */
        val oracleText: String
            get() = if (prefix) {
                (words.dropLast(1).map { "{$it}" } + "${words.last()}%").joinToString(" ")
            } else {
                "{${words.joinToString(" ")}}"
            }

        /** The expression for the term as whole words; when [prefix], the last as the start of one. */
        val pattern: String
            get() = BOUNDARY_BEFORE + words.joinToString("[^[:alnum:]_]+") + (if (prefix) "" else BOUNDARY_AFTER)

        companion object {
            /**
             * Reads [text], a phrase's content or a word. Null when it holds no word.
             *
             * Whether the term is a prefix is decided before the backslashes are taken out, because
             * afterwards `prefix\*` and `prefix*` are the same text.
             */
            fun of(text: String): Term? {
                val prefix = STAR_AT_END.containsMatchIn(text)
                val words = words(text.replace(ESCAPED) { it.groupValues[1] })
                return if (words.isEmpty()) null else Term(words, prefix)
            }

            /** The words of [text], in lower case: its runs of letters, digits and underscores. */
            private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotEmpty() }
        }
    }
}
