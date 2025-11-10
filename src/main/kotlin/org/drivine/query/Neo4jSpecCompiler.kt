package org.drivine.query
import kotlin.assert

class Neo4jSpecCompiler<T>(spec: QuerySpecification<T>) : QuerySpecificationCompiler(spec) {

    init {
        assert(this.spec.statement!!.language == QueryLanguage.CYPHER) {
            "${this.spec.statement!!.language} is not supported on Neo4j."
        }
    }

    override fun formattedStatement(): String {
        return appliedStatement()
    }

    override fun formattedParams(): Map<String, Any> {
        return this.spec.parameters
    }
}
