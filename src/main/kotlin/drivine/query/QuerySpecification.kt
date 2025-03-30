package drivine.query

import drivine.mapper.MapPostProcessor
import drivine.mapper.ResultPostProcessor
import drivine.mapper.TransformPostProcessor

class QuerySpecification<T> private constructor(
    var statement: Statement? = null,
    var parameters: Map<String, Any> = emptyMap<String, Any>(),
    var postProcessors: MutableList<ResultPostProcessor<Any, T>> = mutableListOf(),
    var _skip: Int = 0,
    var _limit: Int = Int.MAX_VALUE
) {

    companion object {
        fun <T> withStatement(statement: Statement): QuerySpecification<T> {
            return QuerySpecification(statement = statement)
        }

        fun <T> withStatement(statement: String): QuerySpecification<T> {
            return QuerySpecification(statement = Statement(statement))
        }
    }

    fun withStatement(statement: String): QuerySpecification<T> {
        this.statement = Statement(statement)
        return this
    }

    // Bind parameters method
    fun bind(parameters: Map<String, Any>): QuerySpecification<T> {
        this.parameters = parameters
        return this
    }

    // Add post processors
    fun addPostProcessors(vararg postProcessors: ResultPostProcessor<Any, T>): QuerySpecification<T> {
        this.postProcessors.addAll(postProcessors)
        return this
    }

    fun map(mapper: (Any) -> T): QuerySpecification<T> {
        this.postProcessors.add(MapPostProcessor(mapper))
        return this
    }

    fun transform(type: Class<T>): QuerySpecification<T> {
        this.postProcessors.add(TransformPostProcessor(type))
        return this
    }

//    fun filter(filter: (Any) -> Boolean): QuerySpecification<T> {
//        this.postProcessors.add(FilterPostProcessor(filter))
//        return this
//    }

    fun skip(results: Int): QuerySpecification<T> {
        this._skip = results
        return this
    }

    fun limit(results: Int): QuerySpecification<T> {
        this._limit = results
        return this
    }

    fun finalizedCopy(language: QueryLanguage): QuerySpecification<T> {
        return QuerySpecification<T>(
            statement = toPlatformDefault(language, this.statement!!),  // Ensuring statement is not null
            parameters = this.parameters,
            postProcessors = this.postProcessors.toMutableList(),
            _skip = this._skip,
            _limit = this._limit
        )
    }
}
