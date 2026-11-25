package org.drivine.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sql.SqlGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.cypher.api.Store
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.IndexKey
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.ValueLayout
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The spec's `store.yaml` `indexKeys` on each SQL store (liberation-data/drivine-cypher-spec#286): each value stored under
 * each key, as the store holds it at rest, gives through the store's own index expressions ([IndexKey.column]) the class and
 * key the module works out of it as a statement compiles ([IndexKey.of]), which a lookup binds and compares with them. A
 * module that works out another key than the store's index holds would find nothing where the node is there: the drift guard.
 */
abstract class IndexKeysContract {

    abstract val store: Store

    abstract fun manager(graph: String): NonTransactionalPersistenceManager

    internal abstract fun graph(name: String): SqlGraph

    abstract fun drop(graph: String)

    /** A JSON value of [marker], a bound text, as the store reads JSON. */
    abstract fun json(marker: String): String

    private val table: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/cypher/store.yaml")) { "No spec file cypher/store.yaml" })["indexKeys"]

    private val mapper = ObjectMapper()

    /** [node] with each `{$repeat: text, times: n}` in it written out, as the spec's runners read it. */
    private fun expanded(node: JsonNode): JsonNode = when {
        node.isObject && node.has("\$repeat") -> mapper.nodeFactory.textNode(node["\$repeat"].asText().repeat(node["times"].asInt()))
        node.isObject -> mapper.createObjectNode().also { out -> node.fields().forEach { (k, v) -> out.set<JsonNode>(k, expanded(v)) } }
        node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(expanded(it)) } }
        else -> node
    }

    /** [value] as the store holds it at rest: a tag with its sort key `s`, as ValueLayout.tag writes it. */
    private fun atRest(value: JsonNode): JsonNode {
        if (!value.isObject || !value.has(ValueLayout.KIND) || value.has(ValueLayout.KEY)) return value
        val kind = ValueLayout.kindOf(value[ValueLayout.KIND].asText()) ?: return value
        val key = Temporals.key(kind, value[ValueLayout.TEXT].asText()) ?: return value
        return (value.deepCopy<ObjectNode>()).put(ValueLayout.KEY, key)
    }

    @Test
    fun `each value is held by the store's index expressions as the module keys it`() {
        val name = "cy_indexkeys"
        drop(name)
        val pm = manager(name)
        graph(name).ensure(pm)
        val nodes = graph(name).store.nodes
        val wrong = mutableListOf<String>()
        var checked = 0
        for (row in table["values"]) {
            // Postgres's index is over the property's JSON: it is checked for each value Oracle works a key out of.
            val pinned = row[if (store == Store.POSTGRES) Store.ORACLE.id else store.id]?.takeUnless { it.isNull }?.let(::expanded) ?: continue
            val value = expanded(row["value"])
            val text = mapper.writeValueAsString(value)
            for (key in table["keys"]) {
                val stored = key["stored"]?.asText() ?: key["key"].asText()
                pm.execute(QuerySpecification.withStatement(sqlStatement("DELETE FROM $nodes")))
                val props = mapper.createObjectNode().set<JsonNode>(stored, atRest(value))
                pm.execute(QuerySpecification.withStatement(sqlStatement("INSERT INTO $nodes (labels, props) VALUES (${json("\$labels")}, ${json("\$props")})"))
                    .bind(mapOf("labels" to "[\"K\"]", "props" to mapper.writeValueAsString(props))))
                val columns = IndexKey.column(store, "n", stored)
                if (store == Store.POSTGRES) {
                    // The value as a statement binds it, a tag with its sort key, compared with the index's JSON as a lookup does.
                    val equal = pm.query(QuerySpecification.withStatement(sqlStatement("SELECT CASE WHEN ${columns.single()} = CAST(\$v AS jsonb) THEN 1 ELSE 0 END AS e FROM $nodes n"))
                        .bind(mapOf("v" to mapper.writeValueAsString(atRest(value))))).single().let { (it as? Map<*, *>)?.values?.single() ?: it }
                    if (equal.toString() != "1") wrong += "${text.take(60)} under $stored: the index's JSON is not the bound value's"
                } else {
                    val (kind, held) = columns
                    val got = columns(pm.query(QuerySpecification.withStatement(sqlStatement("SELECT $kind AS c, $held AS k FROM $nodes n"))).single())
                    val want = listOf(pinned[0].asText(), pinned[1].asText())
                    if (got != want) wrong += "${text.take(60)} under $stored: index holds ${got.map { it?.take(40) }}, pinned ${want.map { it.take(40) }}"
                }
                checked++
            }
            // The SQL the module wrote over a bound value before (#286), which a lookup still compares where it works out no key.
            // Bound as a statement binds a given value: a tag with its sort key, as the writer gives it.
            extra(pm, mapper.writeValueAsString(atRest(value)))?.let { if (it != listOf(pinned[0].asText(), pinned[1].asText())) wrong += "${text.take(60)} over the bound value: $it" }
        }
        assertTrue(checked >= 40, "indexKeys checked only $checked")
        assertTrue(wrong.isEmpty(), "${wrong.size} of $checked differ:\n${wrong.joinToString("\n")}")
    }

    /** A row of two columns as the manager gives it, a list or a map of them, as texts. */
    protected fun columns(row: Any?): List<String?> = ((row as? Map<*, *>)?.values?.toList() ?: row as List<*>).map { it?.toString() }

    /** The class and key the store's SQL works out over the bound JSON [text], where it writes one; null where it doesn't. */
    open fun extra(pm: NonTransactionalPersistenceManager, text: String): List<String?>? = null
}

@OracleTests
class OracleIndexKeysTest : IndexKeysContract() {
    override val store = Store.ORACLE
    override fun manager(graph: String) = OracleTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = OracleGraph(name)
    override fun drop(graph: String) {
        val store = OracleGraph(graph).store
        OracleTestDatabase.drop("TABLE ${store.edges}", "TABLE ${store.nodes}", "TABLE ${store.catalog}", "TABLE ${store.lock}")
    }
    override fun json(marker: String) = "JSON($marker)"
    override fun extra(pm: NonTransactionalPersistenceManager, text: String): List<String?> {
        return columns(pm.query(QuerySpecification.withStatement(
            sqlStatement("SELECT ${OracleSql.valueClass("JSON(\$v)")} AS c, ${OracleSql.valueKey("JSON(\$v)")} AS k FROM dual",)
        ).bind(mapOf("v" to text))).single())
    }
}

@PostgresTests
class PostgresIndexKeysTest : IndexKeysContract() {
    override val store = Store.POSTGRES
    override fun manager(graph: String) = PostgresTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = PostgresGraph(name)
    override fun drop(graph: String) {
        val store = PostgresGraph(graph).store
        PostgresTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}, ${store.nodes}, ${store.catalog}, ${store.lock} CASCADE")
    }
    override fun json(marker: String) = "CAST($marker AS jsonb)"
}

class SqliteIndexKeysTest : IndexKeysContract() {
    override val store = Store.SQLITE
    override fun manager(graph: String) = SqliteTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = SqliteGraph(name)
    override fun drop(graph: String) {
        val store = SqliteGraph(graph).store
        SqliteTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}", "DROP TABLE IF EXISTS ${store.nodes}", "DROP TABLE IF EXISTS ${store.catalog}", "DROP TABLE IF EXISTS ${store.lock}")
    }
    override fun json(marker: String) = "json($marker)"
}
