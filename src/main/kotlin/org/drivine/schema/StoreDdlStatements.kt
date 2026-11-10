package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.CypherCompile
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.DdlStatement

/*
 * The schema grammars' DDL is written by the Cypher compiler's module (`org.drivine.cypher.store.ddl`), which the three
 * Drivine libraries share, so that a store one library makes is the store another reads (drivine-cypher-spec#105). These
 * map drivine4j's types onto the module's and back, and nothing more: what the statements say is the module's, and running
 * them, deciding what to run, and reading what they gave are drivine4j's.
 */

/** This item as the module's generators take one to drop: what the store's catalog or introspection gave of it. */
internal fun SchemaItemInfo.catalogItem(): CatalogItem = CatalogItem(kind, label, properties, name)

/** The module's [statement] as a grammar gives it: its text, or on FalkorDB the driver's command. */
internal fun schemaStatement(statement: DdlStatement): SchemaStatement = when (statement) {
    is DdlStatement.Text -> SchemaStatement.Cypher(statement.statement)
    is DdlStatement.Native -> SchemaStatement.Native(statement.args)
}

/**
 * The statements [make] gets from the module's generators, as a grammar gives them. A generator refuses what its store
 * cannot make or drop (a TEXT index off Neo4j, a drop by a name not known, a constraint `GRAPH.CONSTRAINT` cannot take)
 * with the compiler's `CompilerException`, which comes out as the [DrivineException] drivine4j has always thrown for it,
 * with the same message: see [CypherCompile.refusing].
 */
internal inline fun statementsOf(make: () -> List<DdlStatement>): List<SchemaStatement> =
    CypherCompile.refusing(make).map(::schemaStatement)

/** What [check] does, a generator's refusal thrown as drivine4j's [DrivineException], as [statementsOf] throws one. */
internal inline fun refusing(check: () -> Unit) = CypherCompile.refusing(check)
