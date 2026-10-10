package org.drivine.query

/**
 * Compiles a [QuerySpecification] into a [CompiledQuery] — a statement string and parameter map
 * ready to be sent to a backend driver.
 *
 * Currently backend-agnostic. If a future backend needs dialect-specific compilation behavior,
 * reintroduce subclasses and keep this as the common case.
 */
class SpecCompiler(private val spec: QuerySpecification<*>) {

    fun compile(): CompiledQuery {
        return CompiledQuery(
            statement = appliedStatement().trim(),
            parameters = spec.parameters
        )
    }

    private fun appliedStatement(): String {
        val rendered = renderTemplate(spec.statement!!.text, spec.renderParameters)
        return "$rendered ${skipClause()} ${limitClause()}"
    }

    /** SQL pages with the standard `OFFSET … ROWS FETCH NEXT … ROWS ONLY`, which Oracle and Postgres both read. */
    private val sql: Boolean
        get() = spec.statement!!.language == QueryLanguage.SQL

    private fun skipClause(): String {
        val skip = spec._skip ?: return ""
        return if (sql) "OFFSET $skip ROWS" else "SKIP $skip"
    }

    private fun limitClause(): String {
        val limit = spec._limit ?: return ""
        return if (sql) "FETCH NEXT $limit ROWS ONLY" else "LIMIT $limit"
    }

    private fun renderTemplate(text: String, renderParams: Map<String, Any>): String {
        if (renderParams.isEmpty()) return text
        return RENDER_PATTERN.replace(text) { match ->
            val key = match.groupValues[1]
            if (renderParams.containsKey(key)) {
                coerceRenderValue(renderParams[key])
            } else {
                match.value
            }
        }
    }

    private fun coerceRenderValue(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is List<*> -> value.joinToString(":") { it?.toString() ?: "" }
            else -> value.toString()
        }
    }

    companion object {
        private val RENDER_PATTERN = Regex("""\$\(\$([A-Za-z_][A-Za-z0-9_]*)\)""")
    }
}