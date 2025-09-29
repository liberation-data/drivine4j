package drivine.query

import drivine.mapper.FilterPostProcessor
import drivine.mapper.MapPostProcessor
import drivine.mapper.ResultPostProcessor
import drivine.mapper.TransformPostProcessor

class QuerySpecification<T> private constructor(
    var statement: Statement? = null,
    var parameters: Map<String, Any> = emptyMap<String, Any>(),
    var postProcessors: MutableList<ResultPostProcessor<Any, Any>> = mutableListOf(),
    var _skip: Int? = null,
    var _limit: Int? = null,
    var originalSpec: QuerySpecification<*>? = null
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

    fun bind(parameters: Map<String, Any>): QuerySpecification<T> {
        this.parameters = parameters
        return this
    }

    fun addPostProcessors(vararg postProcessors: ResultPostProcessor<Any, Any>): QuerySpecification<T> {
        this.postProcessors.addAll(postProcessors)
        return this
    }

    // Map from current type T to new type U - returns new QuerySpecification<U>
    fun <U> map(mapper: (T) -> U): QuerySpecification<U> {
        val newSpec = QuerySpecification<U>(
            statement = this.statement,
            parameters = this.parameters,
            postProcessors = mutableListOf(),
            _skip = this._skip,
            _limit = this._limit,
            originalSpec = this
        )
        newSpec.postProcessors.add(MapPostProcessor<Any, Any> { input ->
            mapper(input as T) as Any
        })
        return newSpec
    }

    // Transform from Any to new type U - returns new QuerySpecification<U>
    fun <U> transform(type: Class<U>): QuerySpecification<U> {
        val newSpec = QuerySpecification<U>(
            statement = this.statement,
            parameters = this.parameters,
            postProcessors = mutableListOf(),
            _skip = this._skip,
            _limit = this._limit,
            originalSpec = this
        )
        newSpec.postProcessors.add(TransformPostProcessor<Any, Any>(type as Class<Any>))
        return newSpec
    }

    // Filter on current type T - returns same QuerySpecification<T>
    fun filter(predicate: (T) -> Boolean): QuerySpecification<T> {
        this.postProcessors.add(FilterPostProcessor<Any> { input ->
            predicate(input as T)
        })
        return this
    }

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
            statement = toPlatformDefault(language, this.statement!!),
            parameters = this.parameters,
            postProcessors = this.postProcessors.toMutableList(),
            _skip = this._skip,
            _limit = this._limit,
            originalSpec = this.originalSpec
        )
    }
}
