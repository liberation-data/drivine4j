package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.drivine.connection.DatabaseType
import org.junit.jupiter.api.DynamicTest
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A file of the Cypher spec, in `src/test/resources/cypher`: the statements each store runs, and what each
 * gives. The three Drivine libraries run the same files; README.md there describes them.
 */
data class CypherSpec(
    /** The label every node the spec writes carries. */
    val label: String? = null,
    /** The Cypher that writes the graph each case starts from. */
    val graph: String? = null,
    val cases: List<Case> = emptyList(),
) {
    data class Case(val name: String, val description: String? = null, val steps: List<Step>)

    data class Step(
        val cypher: String,
        val note: String? = null,
        val parameters: Map<String, Any?> = emptyMap(),
        /** The rows expected; with no rows and no error, the statement is run and what it gives is not read. */
        val rows: List<Any?>? = null,
        /** The message expected of the statement's failure, a list of messages any one of which it may give, or true for any failure. */
        val error: Any? = null,
        /** The stores or groups the step is run on, or that it is not. */
        val only: List<String>? = null,
        val except: List<String> = emptyList(),
        /** The rows are compared in any order. */
        val unordered: Boolean = false,
        /** Each list in the rows is compared in any order: not the rows, which [unordered] is for. */
        val unorderedLists: Boolean = false,
        /** Each map in the rows has its keys in the order expected. */
        val keyOrder: Boolean = false,
    ) {
        fun runsOn(store: String): Boolean {
            fun named(names: List<String>) = names.any { it == store || GROUPS[it]?.contains(store) == true }
            return (only == null || named(only)) && !named(except)
        }
    }

    companion object {
        /** The groups a step may name for its stores: the graph engines, and the stores Cypher is compiled to SQL for. */
        val GROUPS = mapOf(
            "graph" to setOf("neo4j", "memgraph", "falkordb", "neptune"),
            "sql" to setOf("postgres", "oracle", "sqlite"),
        )

        private val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)

        fun load(file: String): CypherSpec {
            val resource = requireNotNull(CypherSpec::class.java.getResource("/cypher/$file")) { "No spec file cypher/$file" }
            return yaml.readValue(resource)
        }

        /** The name a step gives [type] by. */
        fun storeOf(type: DatabaseType): String = type.name.lowercase()
    }

    /**
     * A test for each case, run on [store]: [before] readies the graph, and [run] gives a step's rows, or fails as
     * the store fails. A step not run on [store] is passed over.
     */
    fun tests(store: String, before: () -> Unit, run: (Step) -> List<Any?>): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            before()
            case.steps.forEachIndexed { index, step ->
                if (step.runsOn(store)) check(case, index, step, store, run)
            }
        }
    }

    private fun check(case: Case, index: Int, step: Step, store: String, run: (Step) -> List<Any?>) {
        val where = "${case.name}, step ${index + 1}, on $store: ${step.cypher.trim()}"
        if (step.error != null) {
            val failure = runCatching { run(step.copy(parameters = expanded(step.parameters))) }.exceptionOrNull() ?: fail("Expected a failure: $where")
            when (val error = step.error) {
                is String -> assertEquals(error, failure.message, where)
                is List<*> -> if (failure.message !in error) fail("Expected one of $error, but was <${failure.message}>: $where")
            }
            return
        }
        val rows = run(step.copy(parameters = expanded(step.parameters)))
        val expected = step.rows ?: return
        assertEquals(canonical(expanded(expected), step, step.unordered), canonical(rows, step, step.unordered), where)
    }

    /**
     * [value] with each `{$repeat: text, times: n}` in it written out, [text] repeated [n] times, and each `{$float: "NaN"}`,
     * `{$float: "Infinity"}` and `{$float: "-Infinity"}` the double it names.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> expanded(value: T): T = when (value) {
        is Map<*, *> -> (
            when (value.keys) {
                setOf("\$repeat", "times") -> (value["\$repeat"] as String).repeat((value["times"] as Number).toInt())
                setOf("\$float") -> (value["\$float"] as String).toDouble()
                else -> value.mapValues { (_, item) -> expanded(item) }
            }
        ) as T
        is List<*> -> value.map { expanded(it) } as T
        else -> value
    }

    /**
     * [value] in a form that compares as the step says: a map as its entries in order where key order counts,
     * and a list sorted where its order does not. A whole number is a Long, whatever the driver gave.
     */
    private fun canonical(value: Any?, step: Step, sorted: Boolean = step.unorderedLists): Any? = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Float -> value.toDouble()
        is Map<*, *> -> {
            val entries = value.mapValues { (_, item) -> canonical(item, step) }
            if (step.keyOrder) entries.toList() else entries
        }
        is List<*> -> value.map { canonical(it, step) }.let { items -> if (sorted) items.sortedBy { it.toString() } else items }
        else -> value
    }
}
