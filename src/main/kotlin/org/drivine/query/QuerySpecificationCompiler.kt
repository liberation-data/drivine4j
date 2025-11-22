package org.drivine.query

abstract class QuerySpecificationCompiler(protected val spec: QuerySpecification<*>) {

    fun compile(): CompiledQuery {
        return CompiledQuery(
            statement = formattedStatement().trim(),
            parameters = this.formattedParams()
        )
    }

    protected open fun appliedStatement(): String {
        return "${spec.statement!!.text} ${skipClause()} ${limitClause()}"
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
