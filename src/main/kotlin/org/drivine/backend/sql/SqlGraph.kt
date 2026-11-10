package org.drivine.backend.sql

import org.drivine.connection.Connection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification

/**
 * A graph as a SQL store keeps it: a table of nodes, a table of relationships, and a catalog of what
 * Drivine made of indexes and constraints. Oracle, Postgres and SQLite keep the same tables, so a store
 * written from one library reads the same from another:
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
 * What the tables, their indexes and what is made beside them are, and what each is named, is the Cypher
 * compiler's module's ([store], `org.drivine.cypher.store.ddl`), which the three Drivine libraries share,
 * so that each makes the same store. A graph name the store cannot take is refused as the module's [store]
 * is made. What is drivine4j's is running the statements: looking for the store, making it, and waiting
 * out another session's DDL.
 */
internal abstract class SqlGraph(val name: String) {

    /** The module's DDL of the graph on this store: its tables, their names and what makes them. */
    abstract val store: SqlStoreDdl

    /** The table of nodes: a row each, with its labels, its properties and its stamp. */
    val nodes: String get() = store.nodes

    /** The table of relationships: a row each, from the node `src` to the node `dst`, with its type and its properties. */
    val edges: String get() = store.edges

    /** What Drivine made of indexes and constraints. */
    val catalog: String get() = store.catalog

    /** The table that holds the text a full-text index on [label]'s [properties] is over. */
    fun texts(label: String, properties: List<String>): String = store.texts(label, properties)

    /** The table that holds the embeddings a vector index on [label]'s [property] is over. */
    fun vectors(label: String, property: String): String = store.vectors(label, property)

    /**
     * A name for an object of [kind] made for [parts], a label and properties, as the module names it
     * ([SqlStoreDdl.objectName]): the name is written to the graph's catalog with what it was made for,
     * and an object is dropped by the name recorded there.
     */
    fun objectName(kind: String, parts: List<String>): String = store.objectName(kind, parts)

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

    /**
     * Refuses a store that is there but must not be used, by reads alone, each statement given to [run]: what [ensure]
     * refuses before it makes anything. [JdbcConnectionProvider] asks it on a Spring transaction's connection, where it
     * makes no tables (#395), so a store is refused there as on any other connection. None is refused by default.
     */
    internal open fun refuse(run: (String) -> List<Any?>) {}

    /** [refuse], on [connection]. */
    internal fun refuse(connection: Connection) = refuse { connection.query(QuerySpecification.withStatement(it)) }

    companion object {
        /** The graph of a manager that names none. */
        const val DEFAULT_NAME = JdbcConnectionProvider.DEFAULT_GRAPH

        /**
         * The properties of an index as the catalog's record of it holds them: part of the catalog's key, which a read of
         * the catalog binds to find a record the module's DDL wrote, so written byte for byte as the module writes it,
         * and not by a JSON library whose spacing or escaping might change.
         */
        fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }
}
