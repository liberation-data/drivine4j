package org.drivine.cli

/**
 * A parameter's assignment, as `-P` and `:param` take one in the contract's "Parameters": [Named] (`NAME => EXPRESSION`,
 * or `NAME=EXPRESSION`), or [Map] (`{NAME: EXPRESSION, …}`), the expression Cypher that the store evaluates.
 */
sealed interface Assignment {
    /** The Cypher the store runs to evaluate it, as `RETURN expression AS value`. */
    val cypher: String

    data class Named(val name: String, val expression: String) : Assignment {
        override val cypher: String get() = "RETURN $expression AS value"
    }

    data class Map(val expression: String) : Assignment {
        override val cypher: String get() = "RETURN $expression AS value"
    }

    companion object {
        /** A parameter's name: Cypher's, as `limit`, or one in backticks, as `` `a b` ``, which is its text between them. */
        private val NAME = Regex("""[\p{L}_][\p{L}\p{N}_]*|`(?:[^`]|``)+`""")

        /**
         * [text] as an assignment, or null where it is of none of the three forms. A text that starts with `{` is a map,
         * whatever `=` it holds; else the first `=` splits the name from the expression, as `NAME => EXPRESSION` where
         * a `>` follows it, as `NAME=EXPRESSION` where none does. The spaces around either aren't part of the name or
         * the expression, and neither may be empty.
         */
        fun parse(text: String): Assignment? {
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) return Map(trimmed)
            val equals = trimmed.indexOf('=')
            if (equals < 0) return null
            val arrow = trimmed.getOrNull(equals + 1) == '>'
            val name = trimmed.substring(0, equals).trim()
            val expression = trimmed.substring(equals + if (arrow) 2 else 1).trim()
            if (!NAME.matches(name) || expression.isEmpty()) return null
            val bare = if (name.startsWith("`")) name.substring(1, name.length - 1).replace("``", "`") else name
            return Named(bare, expression)
        }
    }
}
