package org.drivine.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sql.SqlGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.connection.JacksonEncoder
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec's `fragments.yaml` run on each SQL store (liberation-data/drivine-cypher-spec#161): each case's fragment, asked of
 * the module as the object manager asks it, run in SQL of the test's own over `setup`'s nodes, keeps or orders the nodes
 * Neo4j does for the same expression.
 */
abstract class FragmentsContract {

    /** A new manager of [graph]. */
    abstract fun manager(graph: String): NonTransactionalPersistenceManager

    /** The store's graph of [name], for its table of nodes. */
    internal abstract fun graph(name: String): SqlGraph

    /** Drops [graph]'s tables, so a test starts from none. */
    abstract fun drop(graph: String)

    /** The store's compiler of [graph], given the [indexed] properties, as the object manager builds it. */
    abstract fun compiler(graph: String, indexed: Set<Pair<String, String>>): SqlCypherCompiler

    /** The text of the plain text property [key] of the node [row], as the store reads it. */
    abstract fun text(row: String, key: String): String

    private val spec: JsonNode =
        ObjectMapper(YAMLFactory()).readTree(requireNotNull(javaClass.getResource("/cypher/fragments.yaml")) { "No spec file cypher/fragments.yaml" })

    /** A parameter as the case writes it, the JSON entry's way: a temporal value as `{"$temporal", "text"}`. */
    private fun value(node: JsonNode): Any? = when {
        node.isObject && node.has("\$temporal") -> node["text"].asText().let { text ->
            when (val kind = node["\$temporal"].asText()) {
                "DATE" -> LocalDate.parse(text)
                "LOCAL_DATE_TIME" -> LocalDateTime.parse(text)
                "DATE_TIME" -> ZonedDateTime.parse(text)
                else -> error("No temporal kind $kind in the runner")
            }
        }
        node.isArray -> node.map(::value)
        node.isTextual -> node.asText()
        node.isIntegralNumber -> node.asLong()
        node.isNumber -> node.asDouble()
        node.isBoolean -> node.asBoolean()
        node.isNull -> null
        else -> error("No value $node in the runner")
    }

    @Test
    fun `each fragment keeps or orders the nodes Neo4j does`() {
        val name = "cy_fragments"
        drop(name)
        val pm = manager(name)
        spec["setup"].forEach { pm.query(QuerySpecification.withStatement(CypherStatement(it.asText()))) }
        val indexed = spec["indexed"].map { it[0].asText() to it[1].asText() }.toSet()
        val compiler = compiler(name, indexed)
        val variables = spec["variables"].fields().asSequence().associate { (variable, row) ->
            variable to FragmentVariable.Node(row["node"].asText(), row["labels"].map { it.asText() })
        }
        val row = variables.getValue("n").row
        val labelled = compiler.condition("n:Person", variables)
        val id = text(row, "id")
        var checked = 0
        val wrong = mutableListOf<String>()
        for (case in spec["cases"]) {
            val expected = case["rows"]?.map { it.asText() } ?: continue
            val parameters = case["parameters"]?.fields()?.asSequence()?.associate { (k, v) -> k to value(v) } ?: emptyMap()
            val sql = StringBuilder("SELECT $id AS id FROM ${graph(name).store.nodes} $row WHERE ${labelled.sql}")
            val fragment = when {
                case.has("condition") -> compiler.condition(case["condition"].asText(), variables, parameters).also { sql.append(" AND (${it.sql})").append(" ORDER BY $id") }
                else -> compiler.order(case["order"].asText(), case["descending"]?.asBoolean() == true, variables, parameters)
                    .also { sql.append(" ORDER BY ${(it.terms + id).joinToString()}") }
            }
            val got = runCatching {
                pm.query(QuerySpecification.withStatement(sql.toString()).bind(labelled.parameters + fragment.parameters)).map { (it as? Map<*, *>)?.values?.single() ?: it }.map { it.toString() }
            }.fold({ it }, { listOf("ERROR ${it.message?.take(300)}") })
            if (got != expected) wrong += "${case["name"].asText()}: expected $expected, got $got\n    $sql"
            checked++
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of $checked cases differ from Neo4j:\n${wrong.joinToString("\n")}")
        assertEquals(25, checked, "fragments.yaml's cases with rows")
    }
}

@PostgresTests
class PostgresFragmentsTest : FragmentsContract() {
    override fun manager(graph: String) = PostgresTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = PostgresGraph(name)
    override fun drop(graph: String) {
        val store = PostgresGraph(graph).store
        PostgresTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}, ${store.nodes}, ${store.catalog}, ${store.lock} CASCADE")
    }
    override fun compiler(graph: String, indexed: Set<Pair<String, String>>) = PostgresCypherCompiler(graph, JacksonEncoder)
    override fun text(row: String, key: String) = "($row.props ->> '$key')"
}

@OracleTests
class OracleFragmentsTest : FragmentsContract() {
    override fun manager(graph: String) = OracleTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = OracleGraph(name)
    override fun drop(graph: String) {
        val store = OracleGraph(graph).store
        OracleTestDatabase.drop("TABLE ${store.edges}", "TABLE ${store.nodes}", "TABLE ${store.catalog}", "TABLE ${store.lock}")
    }
    override fun compiler(graph: String, indexed: Set<Pair<String, String>>) =
        OracleCypherCompiler(graph, nullsEqualStored = true, eraStored = true, indexedProperties = indexed, encoder = JacksonEncoder)
    override fun text(row: String, key: String) = "JSON_VALUE($row.props, '$.$key')"
}

class SqliteFragmentsTest : FragmentsContract() {
    override fun manager(graph: String) = SqliteTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = SqliteGraph(name)
    override fun drop(graph: String) {
        val store = SqliteGraph(graph).store
        SqliteTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}", "DROP TABLE IF EXISTS ${store.nodes}", "DROP TABLE IF EXISTS ${store.catalog}", "DROP TABLE IF EXISTS ${store.lock}")
    }
    override fun compiler(graph: String, indexed: Set<Pair<String, String>>) = SqliteCypherCompiler(graph, JacksonEncoder)
    override fun text(row: String, key: String) = "($row.props ->> '$.$key')"
}
