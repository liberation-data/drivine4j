package org.drivine.schema

import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.manager.PersistenceManager
import org.drivine.manager.requireCypher
import org.drivine.cypher.store.marker.StoreStampStatements as ModuleStoreStampStatements

/**
 * The statements that keep one of Drivine's own nodes in a graph: the schema-version marker
 * ([VersionMarkerStatements]) or the store's identity ([StoreStampStatements]).
 */
interface ReservedNodeStatements {

    /**
     * A schema statement that makes what the others need, run once before any of them: on Oracle, the
     * graph's tables, which a node read before anything else has made would not find. Null when the
     * store needs nothing made.
     */
    val prepare: String?
        get() = null
}

/**
 * The statements for one of Drivine's own nodes, in the language of the store this manager speaks
 * to, with what they need made: [own] of the manager's schema grammar, whose
 * [ReservedNodeStatements.prepare] is run on a schema connection before they are handed back. A
 * manager with no schema grammar is given [cypher] where its store speaks Cypher, and is refused
 * where it does not, [purpose] saying what could not be written.
 */
internal fun <T : ReservedNodeStatements> PersistenceManager.reservedNode(purpose: String, own: SchemaGrammar.() -> T, cypher: () -> T): T {
    val statements = schemaGrammar?.own() ?: cypher().also { requireCypher(purpose) }
    statements.prepare?.let { indexes.run(it) }
    return statements
}

/**
 * The statements that keep Drivine's schema-version marker: one node of the graph for each owner
 * scope, holding the version last applied, the inventory of what was applied, and when. Written in
 * the store's own language by its [SchemaGrammar]; the graph engines share the Cypher of
 * [CypherVersionMarker]. Every statement binds the scope as `$scope`.
 *
 * Drivine's own grammars give the statements the Cypher compiler's module writes
 * ([org.drivine.cypher.store.marker.MarkerStatements]), which the three Drivine libraries share, so a
 * marker one library wrote is the marker another reads. This interface is what a grammar of yours
 * gives in their place, and what [SchemaVersionStore] runs.
 */
interface VersionMarkerStatements : ReservedNodeStatements {

    /** Reads [property] of the newest marker of the scope, as text; no row when there is none. */
    fun read(property: String): String

    /** Writes the marker of the scope, making it if it is not there. Its parameters are those of [mergeBindings]. */
    val merge: String

    /** The values [merge] binds for [version] and the [items] of the inventory, as [storedItems] writes them. */
    fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        mapOf("scope" to scope, "version" to version, "items" to storedItems(items))

    /** Removes every marker of the scope but the newest. */
    val deleteAllButNewest: String

    /** Removes the marker of the scope. */
    val delete: String

    /**
     * The scope of every marker, a row each, as the store's own SQL; null where they are read as Cypher, which the
     * graph engines run and Postgres and Oracle compile. SQLite reads them in SQL, since an older SQLite refuses
     * compiled Cypher, and a remove, which reads them, works there as the rest of the schema manager does.
     */
    val scopes: String?
        get() = null

    /** The inventory as the store keeps it; [items] reads it back. A store that cannot keep an empty string marks it. */
    fun storedItems(items: String): String = items

    /** The inventory as [read] gives it back, undoing [storedItems]. */
    fun items(stored: String): String = stored
}

/**
 * The module's marker statements ([shared]) as drivine4j's [VersionMarkerStatements]. The text is the
 * module's; the moment a SQL store binds as `$now` is this process's clock, read when the merge is
 * bound, as the module has none of its own. The graph engines' merge binds none: they take
 * `timestamp()`, as they always have, so the markers a store already holds are read as they were.
 */
internal class SharedVersionMarker(private val shared: MarkerStatements) : VersionMarkerStatements {
    override val prepare: String? get() = shared.prepare
    override fun read(property: String): String = shared.read(property)
    override val merge: String get() = shared.merge
    override fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        shared.mergeParameters(scope, version, items, System.currentTimeMillis())
    override val deleteAllButNewest: String get() = shared.deleteAllButNewest
    override val delete: String get() = shared.delete
    override val scopes: String? get() = shared.scopes
    override fun storedItems(items: String): String = shared.storedItems(items)
    override fun items(stored: String): String = shared.items(stored)
}

/**
 * The marker's statements for the store the module's [ddl] makes, for a grammar's
 * [SchemaGrammar.versionMarker] given [label] and [scopeProperty]. The marker's label and key are
 * the shared layout's ([SchemaMarker]), which the module writes and no other: a marker of another
 * label would be one no Drivine library reads, so asking for one is refused, not answered with it.
 */
internal fun sharedVersionMarker(label: String, scopeProperty: String, ddl: StoreDdl): VersionMarkerStatements {
    require(label == SchemaMarker.LABEL && scopeProperty == SchemaMarker.SCOPE_PROPERTY) {
        "The schema marker is a node labelled ${SchemaMarker.LABEL} keyed by ${SchemaMarker.SCOPE_PROPERTY}, as every Drivine library " +
            "keeps it; there are no statements for one labelled $label keyed by $scopeProperty."
    }
    return SharedVersionMarker(MarkerStatements.of(ddl))
}

/**
 * The marker's statements in Cypher, for the graph engines: the module's ([org.drivine.cypher.store.marker.CypherMarkerStatements]).
 * [label] and [scopeProperty] must be the marker's own, [SchemaVersionStore.LABEL] and
 * [SchemaVersionStore.SCOPE_PROPERTY]: see [sharedVersionMarker].
 */
class CypherVersionMarker(label: String, scopeProperty: String) :
    VersionMarkerStatements by sharedVersionMarker(label, scopeProperty, Neo4jDdl)

/**
 * The statements that keep the store's identity: one node of the graph, stamped on first sight. See
 * `StoreIdentityResolver`. Written in the store's own language by its [SchemaGrammar]; the graph
 * engines share the Cypher of [CypherStoreStamp]. Drivine's own grammars give the statements the
 * Cypher compiler's module writes ([org.drivine.cypher.store.marker.StoreStampStatements]), which the
 * three Drivine libraries share.
 */
interface StoreStampStatements : ReservedNodeStatements {

    /** Makes the stamp if there is none: binds `$key`, `$storeId`, `$assignedAt` and `$engine`. */
    val assignIfAbsent: String

    /** Reads every stamp as a map `{id, assignedAt}`, the oldest first, tie-broken by id, so every reader picks the same one. */
    val readStamps: String
}

/** The module's stamp statements ([shared]) as drivine4j's [StoreStampStatements]: the text is the module's, unchanged. */
internal class SharedStoreStamp(private val shared: ModuleStoreStampStatements) : StoreStampStatements {
    override val prepare: String? get() = shared.prepare
    override val assignIfAbsent: String get() = shared.assignIfAbsent
    override val readStamps: String get() = shared.readStamps
}

/**
 * The stamp's statements for the store the module's [ddl] makes, for a grammar's
 * [SchemaGrammar.storeStamp] given [label] and [keyProperty], which must be the stamp's own
 * ([StoreStamp]), as for the marker's ([sharedVersionMarker]).
 */
internal fun sharedStoreStamp(label: String, keyProperty: String, ddl: StoreDdl): StoreStampStatements {
    require(label == StoreStamp.LABEL && keyProperty == StoreStamp.KEY_PROPERTY) {
        "The store's stamp is a node labelled ${StoreStamp.LABEL} keyed by ${StoreStamp.KEY_PROPERTY}, as every Drivine library " +
            "keeps it; there are no statements for one labelled $label keyed by $keyProperty."
    }
    return SharedStoreStamp(ModuleStoreStampStatements.of(ddl))
}

/**
 * The stamp's statements in Cypher, for the graph engines: the module's ([org.drivine.cypher.store.marker.CypherStoreStampStatements]).
 * [label] and [keyProperty] must be the stamp's own, `DrivineStore` and `key`: see [sharedStoreStamp].
 */
class CypherStoreStamp(label: String, keyProperty: String) :
    StoreStampStatements by sharedStoreStamp(label, keyProperty, Neo4jDdl)
