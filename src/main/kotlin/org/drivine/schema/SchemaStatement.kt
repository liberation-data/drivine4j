package org.drivine.schema

/**
 * A DDL operation emitted by a [SchemaGrammar].
 *
 * Most schema DDL is Cypher and executes through the normal query path ([Cypher]). Some engines
 * require operations that are not expressible as Cypher — e.g. FalkorDB's uniqueness constraints
 * are managed by the Redis command `GRAPH.CONSTRAINT` — and those are modeled as [Native]
 * commands executed at driver level.
 */
sealed interface SchemaStatement {

    /** Cypher DDL, executed through the normal query path in auto-commit mode. */
    data class Cypher(val statement: String) : SchemaStatement

    /**
     * A native (non-Cypher) command executed at driver level by a connection provider that
     * implements [org.drivine.connection.NativeSchemaCommandExecutor].
     *
     * Use [GRAPH_NAME] as a placeholder for the graph/database name; the executing provider
     * substitutes the actual name.
     */
    data class Native(val args: List<String>) : SchemaStatement {
        companion object {
            /** Placeholder substituted with the graph/database name by the executing provider. */
            const val GRAPH_NAME = "{graphName}"
        }
    }
}