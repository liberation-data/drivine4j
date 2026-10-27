package org.drivine.backend.sql

import org.drivine.connection.Connection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification

/**
 * A graph as a SQL store keeps it: a table of nodes, a table of relationships, and a catalog of what
 * Drivine made of indexes and constraints. Oracle and Postgres keep the same tables, so a store written
 * from one library reads the same from another:
 *
 * - `<name>_node`: a row for each node, keyed by `nid`, with its labels (a JSON array), its properties
 *   (a JSON object keyed by the names the graph engines store them under) and its stamp;
 * - `<name>_edge`: a row for each relationship, keyed by `eid`, from the node `src` to the node `dst`,
 *   with its type and its properties, removed with either node;
 * - `<name>_schema`: see the store's schema grammar.
 *
 * Labels and properties are data, so they are JSON, and a label or a type is a predicate on `labels`
 * or `type`. A node's stamp is a column of its own and not a property: every save rewrites it, and it
 * is read back as the property the graph engines store it under.
 *
 * What a store makes beside the tables is named by [objectName], within the [maxObjectName] it allows.
 *
 * @param engine the store's name, for what is said of a name it cannot take
 */
internal abstract class SqlGraph(val name: String, engine: String, private val maxObjectName: Int) {

    init {
        // The longest a name adds is a table beside the nodes, `_v__` and a hash with nothing readable, and that table's trigger.
        val longest = maxObjectName - ("_v__".length + 8 + BESIDE.length)
        require(Regex("[A-Za-z][A-Za-z0-9_]{0,${longest - 1}}").matches(name)) {
            "'$name' cannot name a graph on $engine: it names tables, so it is letters, digits and underscores, begins with a letter, and is at most $longest long, so that what it names fits in $engine's $maxObjectName."
        }
    }

    /** The table of nodes: a row each, with its labels, its properties and its stamp. */
    val nodes = "${name}_node"

    /** The table of relationships: a row each, from the node `src` to the node `dst`, with its type and its properties. */
    val edges = "${name}_edge"

    /** What Drivine made of indexes and constraints. */
    val catalog = "${name}_schema"

    /** The table that holds the text a full-text index on [label]'s [properties] is over. */
    fun texts(label: String, properties: List<String>): String = objectName("t", listOf(label) + properties)

    /** The table that holds the embeddings a vector index on [label]'s [property] is over. */
    fun vectors(label: String, property: String): String = objectName("v", listOf(label, property))

    /**
     * A name for an object of [kind] made for [parts], a label and properties: the graph's name, the
     * kind, what of the parts reads as a name, and a hash of the parts whole, which tells apart two
     * objects whose parts read as one name: a label and a property that run together as another
     * pair does, or names that differ only in what a name cannot hold. It is a hash of 32 bits and
     * not a proof: two sets of parts that read alike and hash alike would share a name. It leaves
     * room for the `_t` or `_i` an object made beside it adds, within the [maxObjectName] the store
     * allows.
     *
     * The name is written to the graph's catalog with what it was made for, and an object is dropped
     * by the name recorded there.
     */
    fun objectName(kind: String, parts: List<String>): String {
        val hash = Integer.toHexString(parts.joinToString("\u0000").hashCode())
        val fixed = "${name}_${kind}__${hash}".length + BESIDE.length
        val readable = parts.joinToString("") { it.replace(Regex("[^A-Za-z0-9]"), "") }.take((maxObjectName - fixed).coerceIn(0, 40))
        return "${name}_${kind}_${readable}_$hash"
    }

    /**
     * Makes the store if it is not there, each statement given to [run], which returns its rows. Each
     * statement is safe to run again, and the store is looked for before anything is made, so a user
     * who may read the tables but not make them gets nothing made over a store that is there.
     */
    internal abstract fun ensure(run: (String) -> List<Any?>)

    /**
     * Makes the store if it is not there. Schema statements go on a connection of their own and never
     * on the caller's transaction.
     */
    fun ensure(persistenceManager: PersistenceManager) = ensure { persistenceManager.indexes.run(it) }

    /**
     * Makes the store if it is not there, on [connection], which is in auto-commit mode and in no
     * transaction: how [JdbcConnectionProvider] makes the graph's tables before its first connection.
     */
    internal fun ensure(connection: Connection) = ensure { connection.query(QuerySpecification.withStatement(it)) }

    companion object {
        /** The graph of a manager that names none. */
        const val DEFAULT_NAME = JdbcConnectionProvider.DEFAULT_GRAPH

        /** The longest an object made beside another adds to its name: a trigger's `_t`, an index's `_i`. */
        private const val BESIDE = "_t"
    }
}
