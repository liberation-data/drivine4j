package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.UnsupportedSchemaGrammar

/**
 * Hands out the connections of one registered database, and says what the database is: the engine
 * it speaks to, the grammars its statements and its schema are written in, and the graph it keeps.
 */
interface ConnectionProvider {
    /** The name the database is registered under. */
    val name: String

    /** The engine the database is. */
    val type: DatabaseType

    /** The subtypes a polymorphic result is mapped to, by the labels of its node; null when none are registered. */
    val subtypeRegistry: SubtypeRegistry?

    /**
     * Cypher dialect for this engine. Controls query syntax generation
     * (existence checks, collection sort emission, etc.).
     */
    val cypherDialect: CypherDialect
        get() = CypherDialect.OPEN_CYPHER

    /**
     * Resolved grammar from the dialect. Override to customize the sort emitter
     * while keeping other dialect behaviors.
     */
    val grammar: CypherGrammar
        get() = cypherDialect.grammar()

    /**
     * Schema (index / constraint) DDL grammar for this engine, resolved from the dialect.
     */
    val schemaGrammar: SchemaGrammar
        get() = cypherDialect.schemaGrammar()

    /**
     * Whether this engine supports schema (index / constraint) management. False for engines
     * with no DDL parity (Neptune, generic openCypher); used to skip them when a catalog
     * broadcasts to all databases.
     */
    val supportsSchemaManagement: Boolean
        get() = schemaGrammar !is UnsupportedSchemaGrammar

    /**
     * Whether [Connection.rollbackTransaction] undoes the statements run since
     * [Connection.startTransaction], so that a batch of statements is applied whole. False for an
     * engine that commits each statement as it runs (FalkorDB).
     */
    val atomicBatches: Boolean
        get() = true

    /**
     * The graph this provider's connections read and write, on a store that keeps a graph under a
     * name: on Oracle it names the property graph and the tables beneath it. Null where the
     * connection itself selects the graph.
     */
    val graphName: String?
        get() = null

    /** A connection for the caller to use, and to release when it is done. */
    fun connect(): Connection

    /** Closes what the provider holds open. A provider over a `DataSource` the application made leaves it open. */
    fun end()
}
