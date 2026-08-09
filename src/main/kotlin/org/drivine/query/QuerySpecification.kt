package org.drivine.query

import org.drivine.mapper.*
import org.drivine.mapper.toMap
import org.drivine.mapper.convertValueForNeo4j

class QuerySpecification<T> private constructor(
    var statement: Statement? = null,
    var parameters: Map<String, Any?> = emptyMap<String, Any?>(),
    var renderParameters: Map<String, Any> = emptyMap<String, Any>(),
    var parameterCoercers: MutableList<ParameterCoercer> = mutableListOf(),
    var postProcessors: MutableList<ResultPostProcessor<Any, Any>> = mutableListOf(),
    var _skip: Int? = null,
    var _limit: Int? = null,
    var originalSpec: QuerySpecification<*>? = null
) {

    companion object {

        /*
         * Logging bounds. A `toString()` is a diagnostic, not a data dump: an
         * embedding parameter is a few thousand floats that tell a reader
         * nothing, and one failing vector query would otherwise put a hundred
         * kilobytes of noise between them and the error that mattered.
         *
         * The `:params` block is written for copy-paste into Neo4j Browser,
         * and an abbreviated one is not pasteable — that is deliberate. The
         * marker is not valid Cypher, so a truncated paste fails loudly
         * instead of quietly running against a two-element vector. For that
         * to hold of strings too, the marker is emitted *outside* the string
         * literal: `"xxx"… (5000 chars)` is a syntax error, whereas a marker
         * inside the quotes would be a perfectly valid 500-char literal that
         * silently writes a stub.
         *
         * Bounds apply at every level of nesting. The workloads that motivate
         * this — `bindObject("props", entity)` and batch saves binding a list
         * of property maps — put the embedding one or two levels down, so a
         * top-level-only bound would not bind anything that matters.
         */
        private const val MAX_LOGGED_ELEMENTS = 10
        private const val MAX_LOGGED_STRING = 500

        @JvmStatic
        fun withStatement(statement: Statement): QuerySpecification<Any> {
            return QuerySpecification(statement = statement)
        }

        @JvmStatic
        fun withStatement(statement: String): QuerySpecification<Any> {
            return QuerySpecification(statement = Statement(statement))
        }
    }

    /**
     * Binds a map of parameters with automatic Neo4j type conversion.
     *
     * Values are converted to Neo4j-compatible types:
     * - Instant → ZonedDateTime (at UTC)
     * - UUID → String
     * - Enum → String (enum name)
     * - Date → ZonedDateTime (at UTC)
     * - Collections and Maps are recursively converted
     * - Primitives (String, Number, Boolean) pass through unchanged
     *
     * Example:
     * ```kotlin
     * QuerySpecification
     *     .withStatement("CREATE (n:Node {createdAt: \$createdAt, id: \$id})")
     *     .bind(mapOf(
     *         "createdAt" to Instant.now(),  // Converted to ZonedDateTime
     *         "id" to UUID.randomUUID()      // Converted to String
     *     ))
     * ```
     */
    fun bind(parameters: Map<String, Any?>): QuerySpecification<T> {
        this.parameters = parameters.mapValues { (_, value) ->
            Neo4jObjectMapper.instance.convertValueForNeo4j(value)
        }
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

    /**
     * Registers template values that will be inlined into the query text before it reaches the
     * connection layer. Use this for parts of Cypher that cannot be parameterized — labels,
     * relationship types, property names — on backends (FalkorDB, Neptune) that lack Neo4j 5's
     * native `$()` dynamic syntax.
     *
     * Template placeholders use the form `$($key)`. Matching keys are substituted with literal
     * text; unmatched `$(...)` expressions pass through untouched so Neo4j 5's native syntax is
     * preserved when mixed in the same query.
     *
     * Value coercion:
     * - `String` → inserted as-is
     * - `List<*>` → elements joined with `:` (for chained labels like `Chunk:Document`)
     * - Other types → `toString()`
     *
     * Render params are never sent to the database as Cypher parameters.
     *
     * Example:
     * ```kotlin
     * QuerySpecification
     *     .withStatement("MERGE (e:ContentElement {id: \$id}) SET e:\$(\$labels)")
     *     .render(mapOf("labels" to listOf("Chunk", "Document")))
     *     .bind(mapOf("id" to "abc"))
     * ```
     */
    fun render(params: Map<String, Any>): QuerySpecification<T> {
        this.renderParameters = this.renderParameters + params
        return this
    }

    /**
     * Convenience form of [render] for a single template value.
     */
    fun renderParam(key: String, value: Any): QuerySpecification<T> {
        this.renderParameters = this.renderParameters + (key to value)
        return this
    }

    fun addPostProcessors(vararg postProcessors: ResultPostProcessor<Any, Any>): QuerySpecification<T> {
        this.postProcessors.addAll(postProcessors)
        return this
    }

    /**
     * Attaches coercers that reshape the compiled parameter map before it is sent to the
     * backend driver. Runs *after* any coercers supplied by the connection (e.g. FalkorDB's
     * [TemporalCoercer]), so spec-level coercers see the connection-coerced values.
     */
    fun addParameterCoercers(vararg coercers: ParameterCoercer): QuerySpecification<T> {
        this.parameterCoercers.addAll(coercers)
        return this
    }

    // Map from current type T to new type U - returns new QuerySpecification<U>
    fun <U> map(mapper: (T) -> U): QuerySpecification<U> {
        val newSpec = QuerySpecification<U>(
            statement = this.statement,
            parameters = this.parameters,
            renderParameters = this.renderParameters,
            parameterCoercers = this.parameterCoercers.toMutableList(),
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
            renderParameters = this.renderParameters,
            parameterCoercers = this.parameterCoercers.toMutableList(),
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
            renderParameters = this.renderParameters,
            parameterCoercers = this.parameterCoercers.toMutableList(),
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
            renderParameters = this.renderParameters,
            parameterCoercers = this.parameterCoercers.toMutableList(),
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
            renderParameters = this.renderParameters,
            parameterCoercers = this.parameterCoercers.toMutableList(),
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
            // Old format for readability
            sb.append("  parameters:\n")
            parameters.forEach { (key, value) ->
                sb.append("    $key = ${renderReadable(value)}\n")
            }

            // New :params format for copy-paste into Neo4j
            sb.append("  :params ")
            sb.append("{")
            sb.append(parameters.entries.joinToString(", ") { (key, value) ->
                "$key: ${renderCypher(value)}"
            })
            sb.append("}\n")
        }
        return sb.toString()
    }

    /**
     * Human-readable rendering, bounded at every level. The abbreviation marker sits inside the
     * quotes here — this block is for reading, not for pasting, so there is nothing to break.
     */
    private fun renderReadable(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${abbreviate(value)}\""
        is Number, is Boolean -> value.toString()
        is Collection<*> ->
            "[${value.take(MAX_LOGGED_ELEMENTS).joinToString(", ") { renderReadable(it) }}${elided(value.size)}]"
        is Map<*, *> ->
            "{${value.entries.take(MAX_LOGGED_ELEMENTS)
                .joinToString(", ") { (k, v) -> "$k=${renderReadable(v)}" }}${elided(value.size)}}"
        else -> "\"${abbreviate(value.toString())}\""
    }

    /** `:params` rendering, bounded at every level, with abbreviation markers left un-pasteable. */
    private fun renderCypher(value: Any?): String = when (value) {
        null -> "null"
        is String -> cypherString(value)
        is Number, is Boolean -> value.toString()
        is Collection<*> ->
            "[${value.take(MAX_LOGGED_ELEMENTS).joinToString(", ") { renderCypher(it) }}${elided(value.size)}]"
        is Map<*, *> ->
            "{${value.entries.take(MAX_LOGGED_ELEMENTS)
                .joinToString(", ") { (k, v) -> "\"$k\": ${renderCypher(v)}" }}${elided(value.size)}}"
        else -> cypherString(value.toString())
    }

    /** The `, … +N more` marker for a collection or map printed short, or "" when printed whole. */
    private fun elided(size: Int): String =
        if (size > MAX_LOGGED_ELEMENTS) ", … +${size - MAX_LOGGED_ELEMENTS} more" else ""

    private fun abbreviate(text: String): String =
        if (text.length <= MAX_LOGGED_STRING) text
        else "${text.take(MAX_LOGGED_STRING)}… (${text.length} chars)"

    /**
     * A Cypher string literal. When abbreviated, the marker is placed after the closing quote so
     * the result is a parse error rather than a valid literal holding a silently truncated value.
     */
    private fun cypherString(text: String): String {
        val escaped = { s: String -> s.replace("\"", "\\\"") }
        return if (text.length <= MAX_LOGGED_STRING) "\"${escaped(text)}\""
        else "\"${escaped(text.take(MAX_LOGGED_STRING))}\"… (${text.length} chars)"
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

/**
 * Reified extension for transform() - allows `.transform<Int>()` instead of `.transform(Int::class.java)`
 *
 * Example:
 * ```kotlin
 * val count = persistenceManager.query(
 *     QuerySpecification
 *         .withStatement("MATCH (p:Person) RETURN count(p)")
 *         .transform<Int>()  // Much cleaner than .transform(Int::class.java)
 * )
 * ```
 */
inline fun <reified U> QuerySpecification<*>.transform(): QuerySpecification<U> {
    @Suppress("UNCHECKED_CAST")
    return (this as QuerySpecification<Any>).transform(U::class.java)
}
