package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar

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

    fun connect(): Connection
    fun end()
}
