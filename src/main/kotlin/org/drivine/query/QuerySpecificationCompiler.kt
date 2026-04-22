package org.drivine.query

abstract class QuerySpecificationCompiler(protected val spec: QuerySpecification<*>) {

    fun compile(): CompiledQuery {
        return CompiledQuery(
            statement = formattedStatement().trim(),
            parameters = this.formattedParams()
        )
    }

    protected open fun appliedStatement(): String {
        val rendered = renderTemplate(spec.statement!!.text, spec.renderParameters)
        return "$rendered ${skipClause()} ${limitClause()}"
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

    protected open fun skipClause(): String {
        return if (spec._skip != null) {
            "${if (spec.statement!!.language == QueryLanguage.CYPHER) "SKIP" else "OFFSET"} ${spec._skip}"
        } else {
            ""
        }
    }

    protected open fun limitClause(): String {
        return spec._limit?.let { "LIMIT $it" } ?: ""
    }

    abstract fun formattedStatement(): String

    abstract fun formattedParams(): Map<String, Any?>
}
