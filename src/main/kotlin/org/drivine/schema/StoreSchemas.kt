package org.drivine.schema

import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.cypher.store.schema.FalkorDbSchema
import org.drivine.cypher.store.schema.MemgraphSchema
import org.drivine.cypher.store.schema.Neo4jSchema
import org.drivine.cypher.store.schema.StoreError
import org.drivine.cypher.store.schema.StoreSchema

/*
 * What the module's schema planner ([org.drivine.cypher.store.plan.SchemaPlanner]) is told of a store: its
 * [StoreSchema] and its marker's [MarkerStatements]. drivine4j's own grammars are the module's stores, so each is given
 * the module's schema of the same store, which decides as the grammar did (`store.yaml` pins both alike). A grammar of
 * another kind (one of the application's, or a test's that wraps one of drivine4j's) is read through [GrammarStoreSchema],
 * so that what it says of its store still holds. These map drivine4j's types onto the module's, as
 * [StoreDdlStatements] and [SharedVersionMarker] do, and decide nothing.
 */

/**
 * The module's schema of the store this grammar writes for; for a grammar that isn't one of drivine4j's own, the grammar
 * itself, as a [GrammarStoreSchema]. [thrown] gives back the error a [StoreError] was made from, which such a grammar's
 * error rules read.
 */
internal fun SchemaGrammar.storeSchema(thrown: (StoreError) -> Throwable?): StoreSchema = when (this::class) {
    Neo4jSchemaGrammar::class -> Neo4jSchema((this as Neo4jSchemaGrammar).elementIdFunction)
    MemgraphSchemaGrammar::class -> MemgraphSchema((this as MemgraphSchemaGrammar).ddl)
    FalkorDbSchemaGrammar::class -> FalkorDbSchema
    PostgresSchemaGrammar::class -> (this as PostgresSchemaGrammar).schema
    OracleSchemaGrammar::class -> (this as OracleSchemaGrammar).schema
    SqliteSchemaGrammar::class -> (this as SqliteSchemaGrammar).schema
    else -> GrammarStoreSchema(this, thrown)
}

/** The marker's statements for [store]: the module's, or for a [GrammarStoreSchema], its grammar's own. */
internal fun markerStatements(store: StoreSchema): MarkerStatements =
    (store as? GrammarStoreSchema)?.markers ?: MarkerStatements.of(store.ddl)

/** A [SchemaGrammar] of no store the module knows, as a [StoreSchema]: everything it is asked is asked of the grammar. */
internal class GrammarStoreSchema(
    private val grammar: SchemaGrammar,
    private val thrown: (StoreError) -> Throwable?,
) : StoreSchema {

    override val ddl: StoreDdl = GrammarStoreDdl(grammar)
    override val engine: String get() = grammar.engine
    override val supportsIfNotExists: Boolean get() = grammar.supportsIfNotExists
    override val supportsNamedItems: Boolean get() = grammar.supportsNamedItems
    override val supportsTextIndex: Boolean get() = grammar.supportsTextIndex
    override val constraintsRequireBackingIndex: Boolean get() = grammar.constraintsRequireBackingIndex
    override val constraintCreationIsAsync: Boolean get() = grammar.constraintCreationIsAsync
    override val indexOperationsAreAsync: Boolean get() = grammar.indexOperationsAreAsync
    override val awaitIndexStatement: String? get() = grammar.awaitIndexStatement
    override val indexStateStatement: String? get() = grammar.indexStateStatement

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = grammar.parseIndexRows(rows)
    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = grammar.parseConstraintRows(rows)
    override fun matchesIdentity(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean = grammar.matchesIdentity(existing, spec)
    override fun matchesShape(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean = grammar.matchesShape(existing, spec)
    override fun matchesEngineVectorOptions(existing: SchemaItemInfo, spec: VectorIndexSpec): Boolean = grammar.matchesEngineVectorOptions(existing, spec)
    override fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> = grammar.unsupportedVectorTuning(spec)
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = grammar.isIncomplete(existing)
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String = grammar.sampleConflictsQuery(spec, limit)
    override fun elementId(variable: String): String = grammar.elementId(variable)

    override fun isViolation(error: StoreError): Boolean = thrown(error)?.let(grammar::isConstraintViolation) ?: false
    override fun isBlockedByIndex(error: StoreError): Boolean = thrown(error)?.let(grammar::isBlockedByIndex) ?: false
    override fun isBuildTimeout(error: StoreError): Boolean = thrown(error)?.let(grammar::isBuildTimeout) ?: false

    /** The marker's statements as the grammar gives them. */
    val markers: MarkerStatements by lazy { GrammarMarkerStatements(grammar.versionMarker(SchemaMarker.LABEL, SchemaMarker.SCOPE_PROPERTY)) }
}

/** A grammar's statements as the module's [StoreDdl]. */
private class GrammarStoreDdl(private val grammar: SchemaGrammar) : StoreDdl {
    override val engine: String get() = grammar.engine
    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = grammar.createIndex(spec, existing?.info()).map(::ddlStatement)
    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = grammar.createConstraint(spec).map(::ddlStatement)
    override fun dropIndex(item: CatalogItem): List<DdlStatement> = grammar.dropIndex(item.info()).map(::ddlStatement)
    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = grammar.dropConstraint(item.info()).map(::ddlStatement)
    override fun listIndexesQuery(kind: SchemaItemKind): String = grammar.listIndexesQuery(kind)
    override fun listConstraintsQuery(): String = grammar.listConstraintsQuery()

    private fun CatalogItem.info() = SchemaItemInfo(kind, label, properties, name)
}

/** A grammar's [SchemaStatement] as the module's [DdlStatement]: the inverse of [schemaStatement]. */
internal fun ddlStatement(statement: SchemaStatement): DdlStatement = when (statement) {
    is SchemaStatement.Cypher -> DdlStatement.Text(statement.statement)
    is SchemaStatement.Native -> DdlStatement.Native(statement.args)
}

/** A grammar's [VersionMarkerStatements] as the module's [MarkerStatements]. */
private class GrammarMarkerStatements(private val statements: VersionMarkerStatements) : MarkerStatements {
    override val prepare: String? get() = statements.prepare
    override fun read(property: String): String = statements.read(property)
    override val merge: String get() = statements.merge
    override fun mergeParameters(scope: String, version: String?, items: String, appliedAt: Long): Map<String, Any?> =
        statements.mergeBindings(scope, version, items)
    override val deleteAllButNewest: String get() = statements.deleteAllButNewest
    override val delete: String get() = statements.delete
    override val scopes: String? get() = statements.scopes
    override fun storedItems(items: String): String = statements.storedItems(items)
    override fun items(stored: String): String = statements.items(stored)
}
