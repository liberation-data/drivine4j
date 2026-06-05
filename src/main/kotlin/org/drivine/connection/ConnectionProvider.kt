package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.UnsupportedSchemaGrammar

interface ConnectionProvider {
    val name: String
    val type: DatabaseType
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

    fun connect(): Connection
    fun end()
}
