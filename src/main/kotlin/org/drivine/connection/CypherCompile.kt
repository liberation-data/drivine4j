package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.cypher.CompilerException
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler

/**
 * A Cypher statement compiled to a SQL store's statement over a graph's tables, as [JdbcConnection] runs it: the one
 * place drivine4j calls the compiler to compile.
 *
 * It is the boundary between the two. The parameters are written as drivine4j's mapper writes them ([JacksonEncoder]), and
 * the compiler's refusal, a [CompilerException], comes out as the [DrivineException] drivine4j has always thrown for it: the
 * same message, causes and stack trace, so a caller that catches DrivineException catches it as before.
 */
internal object CypherCompile {

    fun postgres(graph: String, cypher: String, parameters: Map<String, Any?>): CompiledCypher = refusing {
        PostgresCypherCompiler(graph, JacksonEncoder).compile(cypher, drained(parameters))
    }

    /** Its text values are JSON: see JdbcResultMapper.sqliteRows. */
    fun sqlite(graph: String, cypher: String, parameters: Map<String, Any?>): CompiledCypher = refusing {
        SqliteCypherCompiler(graph, JacksonEncoder).compile(cypher, drained(parameters)).let { it.copy(sql = "${SqliteSql.JSON_ROWS}\n${it.sql}") }
    }

    /**
     * [cypher] compiled for Oracle, calling the functions the graph's store keeps where the statement needs one and the
     * session may call it, and compiled again to call none where it may not: [nullsEqualStored] and [eraStored] are asked
     * only of a statement that calls that function. See OracleStoredFunctions.
     */
    fun oracle(
        graph: String,
        cypher: String,
        parameters: Map<String, Any?>,
        nullsEqualStored: () -> Boolean,
        eraStored: () -> Boolean,
    ): CompiledCypher = refusing {
        val values = drained(parameters)
        val compiler = OracleCypherCompiler(graph, nullsEqualStored = true, eraStored = true, indexedProperties = emptySet(), encoder = JacksonEncoder)
        val compiled = compiler.compile(cypher, values)
        // Each is asked of apart: a store an earlier version made may have the one and not the others.
        val nullsEqual = !compiler.callsStoredNullsEqual || nullsEqualStored()
        val era = !compiler.callsStoredEra || eraStored()
        if (nullsEqual && era) compiled else OracleCypherCompiler(graph, nullsEqual, era, indexedProperties = emptySet(), encoder = JacksonEncoder).compile(cypher, values)
    }

    /**
     * [parameters], with each [Iterator] in a value turned into the list of its items, at any depth in a list, an array or
     * a map. An iterator gives its items only once, and the compiler asks [JacksonEncoder] for a parameter's tree and then
     * for its text, so the text it binds would be an empty list (drivine4j#722). A value with no iterator in it is kept as
     * it is.
     */
    @Suppress("UNCHECKED_CAST")
    fun drained(parameters: Map<String, Any?>): Map<String, Any?> =
        if (holdsIterator(parameters)) drainedValue(parameters) as Map<String, Any?> else parameters

    /** Whether [value] is, or holds at any depth in a list, an array or a map, an [Iterator]. */
    private fun holdsIterator(value: Any?): Boolean = when (value) {
        is Iterator<*> -> true
        is Map<*, *> -> value.values.any(::holdsIterator)
        is Collection<*> -> value.any(::holdsIterator)
        is Array<*> -> value.any(::holdsIterator)
        else -> false
    }

    /** [value], with each iterator in it the list of its items, as [drained]; a value with none in it is itself. */
    private fun drainedValue(value: Any?): Any? = when {
        value is Iterator<*> -> value.asSequence().map(::drainedValue).toList()
        !holdsIterator(value) -> value
        value is Map<*, *> -> value.mapValues { (_, item) -> drainedValue(item) }
        value is Collection<*> -> value.map(::drainedValue)
        value is Array<*> -> value.map(::drainedValue)
        else -> value
    }

    /**
     * [compile], with the compiler's refusal thrown as drivine4j's. A refusal's [CompilerException.detail] is null until
     * the spec's refusals have one (drivine-cypher-spec#62), and DrivineException has no place for it yet.
     */
    inline fun <T> refusing(compile: () -> T): T = try {
        compile()
    } catch (refusal: CompilerException) {
        throw DrivineException(refusal.message, refusal.rootCause).also { it.stackTrace = refusal.stackTrace }
    }
}
