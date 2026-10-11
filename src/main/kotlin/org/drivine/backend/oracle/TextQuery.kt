package org.drivine.backend.oracle

/**
 * A full-text query as Oracle is asked it without a text index: the terms that score a node, those
 * it must hold and those it must not, each a regular expression that matches the term as a whole
 * word in lower-cased text.
 *
 * It reads the plain core of the Lucene syntax the graph engines take: words, a `"quoted phrase"`,
 * `+must`, `-not` and `NOT not`, a `prefix*`, and `field:word` as the word. `AND` makes the word
 * after it one a node must hold; `OR` is the default between words. Anything else is read as text.
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
        private val TOKEN = Regex("""([+-]?)(?:"([^"]*)"|(\S+))""")
        private const val BOUNDARY_BEFORE = "(^|[^[:alnum:]_])"
        private const val BOUNDARY_AFTER = "($|[^[:alnum:]_])"

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
                val text = if (token.groups[2] != null) phrase else word.substringAfter(':')
                val pattern = pattern(text) ?: return@forEach
                val term = term(text)
                when {
                    sign == "-" || not -> { excluded += pattern; excludedText += term }
                    sign == "+" || must -> { required += pattern; scored += pattern; requiredText += term; scoredText += term }
                    else -> { scored += pattern; scoredText += term }
                }
                must = false
                not = false
            }
            require(scored.isNotEmpty()) { "A full-text query needs a word to look for: '$query' has none." }
            val text = (listOf("(" + scoredText.joinToString(" ACCUM ") + ")") + requiredText).joinToString(" AND ") +
                excludedText.joinToString("") { " NOT $it" }
            return TextQuery(scored, required, excluded, text)
        }

        private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotEmpty() }

        /** [text] for Oracle Text: its words as a phrase, or with a trailing `*` the last as the start of a word. */
        private fun term(text: String): String {
            val words = words(text)
            return if (text.endsWith("*")) {
                (words.dropLast(1).map { "{$it}" } + "${words.last()}%").joinToString(" ")
            } else {
                "{${words.joinToString(" ")}}"
            }
        }

        /** The expression for [text] as whole words; with a trailing `*`, as the start of one. Null when it holds no word. */
        private fun pattern(text: String): String? {
            val prefix = text.endsWith("*")
            val words = words(text)
            if (words.isEmpty()) return null
            return BOUNDARY_BEFORE + words.joinToString("[^[:alnum:]_]+") + (if (prefix) "" else BOUNDARY_AFTER)
        }
    }
}
