package org.drivine.query

/**
 * A statement's result as [org.drivine.manager.PersistenceManager.queryResult] gives it: its [columns], named as the store
 * names them, and its [rows], each a list of one value per column, in the columns' order.
 *
 * Where [org.drivine.manager.PersistenceManager.query] gives one value per row for a statement of one column and drops the
 * column names, this keeps both: a result with no rows still has its columns, and a statement that returns nothing has
 * none. It is for a caller that shows a result as it is, as the drivine CLI prints it, rather than mapping it to its own
 * classes.
 *
 * A value is what `query` gives for it, except a node, a relationship and a path, at any depth in a list or a map: they are
 * a [GraphNode], a [GraphRelationship] and a [GraphPath], which keep the labels and type that `query` drops. It is the same
 * on every store: on Postgres, Oracle and SQLite, the Cypher compiler gives the columns and tags each node and relationship
 * (see drivine-cypher-spec's `results.yaml`, which pins what each store gives).
 *
 * Not a data class, so a property can be added later without breaking code compiled against it.
 */
class QueryResult(columns: List<String>, rows: List<List<Any?>>, counts: Map<String, Int>) {

    /** A result with no [counts], as a store that gives none gives it. */
    constructor(columns: List<String>, rows: List<List<Any?>>) : this(columns, rows, emptyMap())

    val columns: List<String> = columns.toList()

    val rows: List<List<Any?>> = rows.map { it.toList() }

    /**
     * What the statement wrote, as the store counts it, where it does: each count of [COUNTS] that isn't 0, by its name,
     * in that order. Neo4j and Memgraph give them; FalkorDB and the SQL stores give none yet, and this is empty there.
     * cypher-shell prints them after a table, as the drivine CLI does.
     */
    val counts: Map<String, Int> = LinkedHashMap(counts)

    override fun equals(other: Any?): Boolean = other is QueryResult && columns == other.columns && rows == other.rows && counts == other.counts

    override fun hashCode(): Int = (31 * columns.hashCode() + rows.hashCode()) * 31 + counts.hashCode()

    override fun toString(): String = "QueryResult(columns=$columns, rows=$rows" + (if (counts.isEmpty()) "" else ", counts=$counts") + ")"

    companion object {
        /** The names of the counts a result may hold, in the order a store's counts are given, as cypher-shell prints them. */
        @JvmField
        val COUNTS: List<String> = listOf(
            "nodesCreated", "nodesDeleted", "relationshipsCreated", "relationshipsDeleted", "propertiesSet",
            "labelsAdded", "labelsRemoved", "indexesAdded", "indexesRemoved", "constraintsAdded", "constraintsRemoved",
        )
    }
}

/**
 * A node a result holds: its [labels], sorted, so that it reads and compares the same on every store, as a graph engine's
 * driver gives them in no order of its own; and its [properties], as `query` gives a node's.
 *
 * It holds no id of the store's: an id is the store's own and differs between stores, so the same graph gives the same
 * node everywhere. One may be added as a property of its own later, which equality would leave out.
 */
class GraphNode(labels: Collection<String>, properties: Map<String, Any?>) {

    val labels: List<String> = labels.sorted()

    val properties: Map<String, Any?> = LinkedHashMap(properties)

    override fun equals(other: Any?): Boolean = other is GraphNode && labels == other.labels && properties == other.properties

    override fun hashCode(): Int = 31 * labels.hashCode() + properties.hashCode()

    override fun toString(): String = "GraphNode(labels=$labels, properties=$properties)"
}

/**
 * A relationship a result holds: its [type], and its [properties], as `query` gives a relationship's. Like [GraphNode], it
 * holds no id of the store's, nor its ends' ids; a [GraphPath] says which way it is walked.
 */
class GraphRelationship(val type: String, properties: Map<String, Any?>) {

    val properties: Map<String, Any?> = LinkedHashMap(properties)

    override fun equals(other: Any?): Boolean = other is GraphRelationship && type == other.type && properties == other.properties

    override fun hashCode(): Int = 31 * type.hashCode() + properties.hashCode()

    override fun toString(): String = "GraphRelationship(type=$type, properties=$properties)"
}

/**
 * A path a result holds: its [nodes] and [relationships] in the order it walks them, one more node than relationships, and
 * for each relationship whether it is walked along its direction ([forward]), from `nodes[i]` to `nodes[i + 1]`, or against
 * it, as cypher-shell prints `-[…]->` or `<-[…]-`.
 */
class GraphPath(nodes: List<GraphNode>, relationships: List<GraphRelationship>, forward: List<Boolean>) {

    val nodes: List<GraphNode> = nodes.toList()

    val relationships: List<GraphRelationship> = relationships.toList()

    val forward: List<Boolean> = forward.toList()

    init {
        require(this.nodes.size == this.relationships.size + 1) { "A path has one more node than relationships: ${this.nodes.size} and ${this.relationships.size}." }
        require(this.forward.size == this.relationships.size) { "A path says the direction of each of its relationships: ${this.forward.size} of ${this.relationships.size}." }
    }

    override fun equals(other: Any?): Boolean =
        other is GraphPath && nodes == other.nodes && relationships == other.relationships && forward == other.forward

    override fun hashCode(): Int = (31 * nodes.hashCode() + relationships.hashCode()) * 31 + forward.hashCode()

    override fun toString(): String = "GraphPath(nodes=$nodes, relationships=$relationships, forward=$forward)"
}
