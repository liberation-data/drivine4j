package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification

/**
 * The graph a fixture is worked out over: every node and relationship of the graph [pm] speaks to,
 * read into memory by [load], changed there by the statement, and written back by [store], which
 * writes only what was made, changed or deleted. A test's graph is a handful of nodes.
 *
 * Nothing reaches the store before [store], so a statement that is refused half way has written
 * nothing. The store is Oracle or Postgres, which keep a graph in the same tables (see `SqlGraph`).
 */
internal class MiniCypherGraph(private val pm: PersistenceManager) {

    /** A node or a relationship: something with properties a statement reads and sets by name. */
    sealed interface Element {

        /** The property [key]; null when there is none. */
        operator fun get(key: String): Any?

        /** Sets the property [key], or removes it when [value] is null, and marks the element to be written. */
        operator fun set(key: String, value: Any?)
    }

    /**
     * A node. Its stamp is a column of its own in the store and a property like any other to a
     * statement, as it is on the graph engines: it is read and set under [Stamps.PROPERTY], and
     * [all] lists it.
     */
    class Node(var nid: Long?, val labels: MutableList<String>, val properties: MutableMap<String, Any?>, var stamp: String?) : Element {
        var changed = false

        override fun get(key: String): Any? = if (key == Stamps.PROPERTY) stamp else properties[key]

        override fun set(key: String, value: Any?) {
            if (key == Stamps.PROPERTY) stamp = value as String? else if (value == null) properties.remove(key) else properties[key] = value
            changed = true
        }

        /** Gives the node [label], unless it has it. */
        fun label(label: String) {
            if (label in labels) return
            labels += label
            changed = true
        }

        /** The node's properties as a graph engine has them, the stamp among them when there is one. */
        fun all(): Map<String, Any?> = properties + listOfNotNull(stamp?.let { Stamps.PROPERTY to it })
    }

    /** A relationship of one type, from one node to another. */
    class Edge(var eid: Long?, val from: Node, val to: Node, val type: String, val properties: MutableMap<String, Any?>) : Element {
        var changed = false

        override fun get(key: String): Any? = properties[key]

        override fun set(key: String, value: Any?) {
            if (value == null) properties.remove(key) else properties[key] = value
            changed = true
        }
    }

    private val graph = requireNotNull(pm.graphName)

    /** Whether the store is Postgres; Oracle when it is not. Only a row's JSON and an insert that returns its key are written differently. */
    private val postgres = pm.type == DatabaseType.POSTGRES

    /** A JSON object of the columns [columns] of a row, as the store writes one. */
    private fun row(vararg columns: String): String =
        if (postgres) {
            columns.joinToString(", ", "jsonb_build_object(", ")") { "'$it', $it" }
        } else {
            columns.joinToString(", ", "JSON_OBJECT(", " RETURNING JSON)") { "'$it' VALUE $it" }
        }
    private val live = mutableListOf<Node>()
    private val related = mutableListOf<Edge>()
    private val deleted = linkedSetOf<Node>()

    /** The nodes there are, in the order the store gave them and then the order they were made in; a deleted one is not among them. */
    val nodes: List<Node> get() = live

    /** The relationships there are; those of a deleted node are not among them. */
    val edges: List<Edge> get() = related

    /**
     * Makes a node as a `CREATE` describes it. A property set to null is no property, as on the
     * graph engines, and the stamp, given as a property, is the node's own.
     */
    fun node(labels: List<String>, properties: Map<String, Any?>): Node {
        val kept = properties.filterValues { it != null }.toMutableMap()
        val stamp = kept.remove(Stamps.PROPERTY) as String?
        return Node(null, labels.toMutableList(), kept, stamp).also { live += it }
    }

    /** Makes a relationship of [type] from [from] to [to]; a property set to null is no property. */
    fun relate(from: Node, to: Node, type: String, properties: Map<String, Any?>): Edge =
        Edge(null, from, to, type, properties.filterValues { it != null }.toMutableMap()).also { related += it }

    /** Deletes [node] and every relationship it has. Deleting it again changes nothing. */
    fun delete(node: Node) {
        if (!live.remove(node)) return
        deleted += node
        related.removeAll { it.from === node || it.to === node }
    }

    private fun sql(statement: String, vararg bound: Pair<String, Any?>) = QuerySpecification.withStatement(statement.trimIndent()).bind(mapOf(*bound))

    /** Reads the whole graph from the store. */
    @Suppress("UNCHECKED_CAST")
    fun load() {
        val byId = mutableMapOf<Long, Node>()
        pm.query(sql("SELECT ${row("nid", "labels", "props", "stamp")} FROM ${graph}_node ORDER BY nid"))
            .forEach { row ->
                row as Map<String, Any?>
                val node = Node((row["nid"] as Number).toLong(), (row["labels"] as List<String>).toMutableList(), (row["props"] as Map<String, Any?>).toMutableMap(), row["stamp"] as String?)
                live += node
                byId[node.nid!!] = node
            }
        pm.query(sql("SELECT ${row("eid", "src", "dst", "type", "props")} FROM ${graph}_edge ORDER BY eid"))
            .forEach { row ->
                row as Map<String, Any?>
                related += Edge(
                    (row["eid"] as Number).toLong(), byId.getValue((row["src"] as Number).toLong()), byId.getValue((row["dst"] as Number).toLong()),
                    row["type"] as String, (row["props"] as Map<String, Any?>).toMutableMap(),
                )
            }
    }

    /**
     * Writes back what the statement did: a `DELETE` for each deleted node the store has, which
     * takes its relationships with it, then each node made or changed, then each relationship made
     * or changed. A node is stored with its labels sorted, as a save stores them, and a node made
     * here is given the id the store gave it, for the relationships that follow to name.
     */
    fun store() {
        deleted.forEach { node -> node.nid?.let { pm.execute(sql("DELETE FROM ${graph}_node WHERE nid = \$nid", "nid" to it)) } }
        live.forEach { node ->
            if (node.nid == null) {
                node.nid = pm.query(
                    sql(
                        if (postgres) {
                            "INSERT INTO ${graph}_node (labels, props, stamp) VALUES (\$labels, \$props, \$stamp) RETURNING nid"
                        } else {
                            """
                            DECLARE
                                v_nid NUMBER;
                                v_rows SYS_REFCURSOR;
                            BEGIN
                                INSERT INTO ${graph}_node (labels, props, stamp) VALUES (${'$'}labels, ${'$'}props, ${'$'}stamp) RETURNING nid INTO v_nid;
                                OPEN v_rows FOR SELECT v_nid FROM dual;
                                DBMS_SQL.RETURN_RESULT(v_rows);
                            END;
                            """
                        },
                        "labels" to node.labels.sorted(), "props" to node.properties, "stamp" to node.stamp,
                    )
                ).single().let { (it as Number).toLong() }
            } else if (node.changed) {
                pm.execute(
                    sql(
                        "UPDATE ${graph}_node SET labels = \$labels, props = \$props, stamp = \$stamp WHERE nid = \$nid",
                        "labels" to node.labels.sorted(), "props" to node.properties, "stamp" to node.stamp, "nid" to node.nid,
                    )
                )
            }
        }
        related.forEach { edge ->
            if (edge.eid == null) {
                pm.execute(
                    sql(
                        "INSERT INTO ${graph}_edge (src, dst, type, props) VALUES (\$src, \$dst, \$type, \$props)",
                        "src" to edge.from.nid, "dst" to edge.to.nid, "type" to edge.type, "props" to edge.properties,
                    )
                )
            } else if (edge.changed) {
                pm.execute(sql("UPDATE ${graph}_edge SET props = \$props WHERE eid = \$eid", "props" to edge.properties, "eid" to edge.eid))
            }
        }
    }
}
