package org.drivine.query

import org.drivine.mapper.*
import org.drivine.mapper.toMap

class QuerySpecification<T> private constructor(
    var statement: Statement? = null,
    var parameters: Map<String, Any?> = emptyMap<String, Any?>(),
    var postProcessors: MutableList<ResultPostProcessor<Any, Any>> = mutableListOf(),
    var _skip: Int? = null,
    var _limit: Int? = null,
    var originalSpec: QuerySpecification<*>? = null
) {

    companion object {

        @JvmStatic
        fun withStatement(statement: Statement): QuerySpecification<Any> {
            return QuerySpecification(statement = statement)
        }

        @JvmStatic
        fun withStatement(statement: String): QuerySpecification<Any> {
            return QuerySpecification(statement = Statement(statement))
        }
    }

    fun bind(parameters: Map<String, Any?>): QuerySpecification<T> {
        this.parameters = parameters
        return this
    }

    /**
     * Binds an object as a parameter using Jackson serialization with Neo4j-aware type conversions.
     *
     * This method uses the same ObjectMapper as `.transform()` for consistency, which automatically:
     * - Converts Enum to String
     * - Converts UUID to String
     * - Converts Instant to ZonedDateTime
     * - Excludes null values
     * - Ignores unknown properties during deserialization
     *
     * Example:
     * ```kotlin
     * val task = Task(id = "1", priority = Priority.HIGH, status = Status.OPEN)
     * QuerySpecification
     *     .withStatement("CREATE (t:Task $props)")
     *     .bindObject("props", task)
     * ```
     *
     * This uses Jackson's Neo4j-aware ObjectMapper to convert objects to Neo4j-compatible types.
     *
     * @param key The parameter name to bind
     * @param value The object to serialize
     * @return This QuerySpecification for method chaining
     */
    fun bindObject(key: String, value: Any): QuerySpecification<T> {
        val converted = Neo4jObjectMapper.instance.toMap(value)
        this.parameters = this.parameters + (key to converted)
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

    /**
     * Maps query results to type U using a RowMapper instance.
     * Automatically transforms results to Map<String, Any?> first, then applies the mapper.
     *
     * This is useful when you want to manually map results similar to Spring JDBC's RowMapper.
     *
     * Example:
     * ```
     * class PersonRowMapper : RowMapper<Person> {
     *     override fun map(row: Map<String, *>): Person {
     *         return Person(
     *             uuid = row["uuid"] as String,
     *             firstName = row["firstName"] as String
     *         )
     *     }
     * }
     *
     * val people = manager.query(
     *     QuerySpecification
     *         .withStatement("MATCH (p:Person) RETURN p")
     *         .mapWith(PersonRowMapper())
     * )
     * ```
     */
    fun <U> mapWith(mapper: RowMapper<U>): QuerySpecification<U> {
        val newSpec = QuerySpecification<U>(
            statement = this.statement,
            parameters = this.parameters,
            postProcessors = mutableListOf(),
            _skip = this._skip,
            _limit = this._limit,
            originalSpec = this
        )
        // First transform to Map, then apply the RowMapper
        newSpec.postProcessors.add(TransformPostProcessor<Any, Any>(Map::class.java as Class<Any>))
        newSpec.postProcessors.add(MapPostProcessor<Any, Any> { input ->
            @Suppress("UNCHECKED_CAST")
            mapper.map(input as Map<String, Any?>) as Any
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

    /**
     * Filters results to only instances of the specified type.
     * Combines filter and map operations for type narrowing.
     *
     * Java example:
     * ```
     * QuerySpecification<Chunk> chunks = spec.filterIsInstance(Chunk.class);
     * ```
     *
     * @param type The class to filter by
     * @return A new QuerySpecification with results narrowed to type U
     */
    fun <U : Any> filterIsInstance(type: Class<U>): QuerySpecification<U> {
        val newSpec = QuerySpecification<U>(
            statement = this.statement,
            parameters = this.parameters,
            postProcessors = mutableListOf(),
            _skip = this._skip,
            _limit = this._limit,
            originalSpec = this
        )
        // Filter to only instances of the target type
        newSpec.postProcessors.add(FilterPostProcessor<Any> { input ->
            type.isInstance(input)
        })
        // Map/cast to the target type
        newSpec.postProcessors.add(MapPostProcessor<Any, Any> { input ->
            input as Any  // Already filtered, safe cast
        })
        return newSpec
    }

    /**
     * Filters results to only instances of the specified type (Kotlin reified version).
     * Combines filter and map operations for type narrowing.
     *
     * Kotlin example:
     * ```
     * val chunks = spec.filterIsInstance<Chunk>()
     * ```
     *
     * This is equivalent to:
     * ```
     * spec.filter { it is Chunk }.map { it as Chunk }
     * ```
     *
     * @return A new QuerySpecification with results narrowed to type U
     */
    inline fun <reified U : Any> filterIsInstance(): QuerySpecification<U> {
        return filterIsInstance(U::class.java)
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

    override fun toString(): String {
        val sb = StringBuilder("QuerySpecification(\n")
        sb.append("  ${statement?.text ?: "null"}\n----\n")

        sb.append(paramsToString())
        sb.append(postProcessorsToString())

        _skip?.let { sb.append("  skip: $it\n") }
        _limit?.let { sb.append("  limit: $it\n") }

        sb.append(")")
        return sb.toString()
    }

    private fun paramsToString(): String {
        val sb = StringBuilder()
        if (parameters.isEmpty()) {
            sb.append("  parameters: <empty>\n")
        } else {
            sb.append("  parameters:\n")
            parameters.forEach { (key, value) ->
                val valueStr = when {
                    value == null -> "null"
                    value is String -> "\"$value\""
                    value is Collection<*> -> "[${value.joinToString(", ")}]"
                    value is Map<*, *> -> value.toString()
                    else -> value.toString()
                }
                sb.append("    $key = $valueStr\n")
            }
        }
        return sb.toString()
    }

    private fun postProcessorsToString(): String {
        val sb = StringBuilder()
        val allProcessors = getAllPostProcessors()
        if (allProcessors.isEmpty()) {
            sb.append("  postProcessors: <none>\n")
        } else {
            sb.append("  postProcessors:\n")
            allProcessors.forEachIndexed { index, processor ->
                sb.append("    [$index] $processor\n")
            }
        }
        return sb.toString()
    }

    private fun getAllPostProcessors(): List<ResultPostProcessor<Any, Any>> {
        val chain = mutableListOf<QuerySpecification<*>>()
        var current: QuerySpecification<*>? = this

        // Build chain from current back to original
        while (current != null) {
            chain.add(0, current)  // Add to beginning to maintain order
            current = current.originalSpec
        }

        // Collect all post-processors from the chain
        return chain.flatMap { it.postProcessors }
    }
}
