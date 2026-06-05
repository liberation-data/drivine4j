package org.drivine.connection

/**
 * Capability interface for connection providers that can execute native (non-Cypher) schema
 * commands at driver level.
 *
 * Some engines manage parts of their schema outside Cypher — FalkorDB uniqueness constraints are
 * managed with the Redis command `GRAPH.CONSTRAINT`, which cannot be issued through the normal
 * query path. Schema grammars emit such operations as
 * [org.drivine.schema.SchemaStatement.Native] and they are routed here.
 */
interface NativeSchemaCommandExecutor {

    /**
     * Executes a native schema command.
     *
     * @param args the command and its arguments, e.g.
     *   `["GRAPH.CONSTRAINT", "CREATE", "{graphName}", "UNIQUE", "NODE", "Person", "PROPERTIES", "1", "email"]`.
     *   Occurrences of [org.drivine.schema.SchemaStatement.Native.GRAPH_NAME] are substituted
     *   with the actual graph/database name by the implementation.
     * @return the raw command response, decoded to a String where possible
     */
    fun executeNativeSchemaCommand(args: List<String>): Any?
}