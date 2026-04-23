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

    private fun skipClause(): String {
        return if (spec._skip != null) {
            "${if (spec.statement!!.language == QueryLanguage.CYPHER) "SKIP" else "OFFSET"} ${spec._skip}"
        } else {
            ""
        }
    }

    private fun limitClause(): String {
        return spec._limit?.let { "LIMIT $it" } ?: ""
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