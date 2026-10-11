package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import java.util.Optional

/**
 * An in-memory stand-in for the Oracle tables [MiniCypher] reads and writes, so the interpreter can be
 * tested without a database. It answers the two load `SELECT`s with rows shaped as Oracle's JSON would
 * come back, applies the `INSERT`, `UPDATE` and `DELETE` statements the interpreter writes back, and
 * keeps every statement it was given with its parameters, so a test can say what was written.
 *
 * A node's relationships go with it when it is deleted, as the Oracle relationship table cascades.
 */
class FakeGraphStore(override val graphName: String = "g") : PersistenceManager {

    class NodeRow(val nid: Long, var labels: List<String>, var props: Map<String, Any?>, var stamp: String?)

    class EdgeRow(val eid: Long, val src: Long, val dst: Long, val type: String, var props: Map<String, Any?>)

    class Written(val statement: String, val parameters: Map<String, Any?>) {
        val verb: String get() = statement.trim().split(Regex("\\s+"))[0].uppercase()
        override fun toString(): String = "$verb ${parameters.keys}"
    }

    val nodes = linkedMapOf<Long, NodeRow>()
    val edges = linkedMapOf<Long, EdgeRow>()

    /** Every statement the interpreter wrote back, in order; the loads are not among them. */
    val written = mutableListOf<Written>()

    private var nextNid = 1L
    private var nextEid = 1L

    /** Seeds a node as a row of the store, and gives its id. Labels are stored sorted, as a save stores them. */
    fun node(vararg labels: String, props: Map<String, Any?> = emptyMap(), stamp: String? = null): Long {
        val nid = nextNid++
        nodes[nid] = NodeRow(nid, labels.sorted(), props, stamp)
        return nid
    }

    /** Seeds a relationship between two seeded nodes, and gives its id. */
    fun edge(src: Long, dst: Long, type: String, props: Map<String, Any?> = emptyMap()): Long {
        val eid = nextEid++
        edges[eid] = EdgeRow(eid, src, dst, type, props)
        return eid
    }

    fun nodes(label: String): List<NodeRow> = nodes.values.filter { label in it.labels }

    fun find(label: String, key: String, value: Any?): NodeRow =
        nodes(label).single { same(it.props[key], value) }

    fun writes(verb: String): List<Written> = written.filter { it.verb == verb }

    private fun same(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    // ----- What MiniCypher uses -----

    override val database: String = "fake"
    override val type: DatabaseType = DatabaseType.ORACLE

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val text = spec.statement!!.text.trim()
        val node = "${graphName}_node"
        val edge = "${graphName}_edge"
        return when {
            text.startsWith("SELECT JSON_OBJECT('nid'") && text.endsWith("FROM $node ORDER BY nid") ->
                nodes.values.sortedBy { it.nid }.map { row ->
                    mapOf("nid" to row.nid, "labels" to row.labels, "props" to row.props, "stamp" to row.stamp)
                }
            text.startsWith("SELECT JSON_OBJECT('eid'") && text.endsWith("FROM $edge ORDER BY eid") ->
                edges.values.sortedBy { it.eid }.map { row ->
                    mapOf("eid" to row.eid, "src" to row.src, "dst" to row.dst, "type" to row.type, "props" to row.props)
                }
            text.startsWith("DECLARE") && "INSERT INTO $node (labels, props, stamp)" in text && "RETURNING nid" in text -> {
                written += Written(text, spec.parameters)
                val nid = nextNid++
                nodes[nid] = NodeRow(nid, spec.parameters["labels"] as List<String>, spec.parameters["props"] as Map<String, Any?>, spec.parameters["stamp"] as String?)
                listOf(nid)
            }
            else -> throw UnsupportedOperationException("The fake store does not answer: $text")
        } as List<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun execute(spec: QuerySpecification<*>) {
        val text = spec.statement!!.text.trim()
        val node = "${graphName}_node"
        val edge = "${graphName}_edge"
        val p = spec.parameters
        written += Written(text, p)
        when (text) {
            "DELETE FROM $node WHERE nid = \$nid" -> {
                val nid = (p.getValue("nid") as Number).toLong()
                requireNotNull(nodes.remove(nid)) { "No node $nid to delete" }
                edges.values.removeAll { it.src == nid || it.dst == nid }
            }
            "UPDATE $node SET labels = \$labels, props = \$props, stamp = \$stamp WHERE nid = \$nid" -> {
                val row = nodes.getValue((p.getValue("nid") as Number).toLong())
                row.labels = p["labels"] as List<String>
                row.props = p["props"] as Map<String, Any?>
                row.stamp = p["stamp"] as String?
            }
            "INSERT INTO $edge (src, dst, type, props) VALUES (\$src, \$dst, \$type, \$props)" -> {
                val src = (p.getValue("src") as Number).toLong()
                val dst = (p.getValue("dst") as Number).toLong()
                require(src in nodes && dst in nodes) { "A relationship $src -> $dst names a node that is not stored" }
                val eid = nextEid++
                edges[eid] = EdgeRow(eid, src, dst, p["type"] as String, p["props"] as Map<String, Any?>)
            }
            "UPDATE $edge SET props = \$props WHERE eid = \$eid" -> {
                edges.getValue((p.getValue("eid") as Number).toLong()).props = p["props"] as Map<String, Any?>
            }
            else -> throw UnsupportedOperationException("The fake store does not apply: $text")
        }
    }

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T = query(spec).single()

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = query(spec).singleOrNull()

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): Optional<T> = Optional.ofNullable(maybeGetOne(spec))

    // ----- Not a store's concern here -----

    override val grammar: CypherGrammar get() = throw UnsupportedOperationException("The fake store has no grammar")
    override val indexes: IndexManager get() = throw UnsupportedOperationException("The fake store has no indexes")
    override val constraints: ConstraintManager get() = throw UnsupportedOperationException("The fake store has no constraints")

    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) =
        throw UnsupportedOperationException("The fake store maps nothing")
}
