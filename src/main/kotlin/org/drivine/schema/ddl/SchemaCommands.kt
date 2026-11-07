package org.drivine.schema.ddl

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.PinnedConnections
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.RecordedItem
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaManager
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import org.drivine.schema.unsupportedTextIndex
import org.drivine.transaction.TransactionContextHolder
import org.slf4j.LoggerFactory

/**
 * Runs Neo4j schema DDL on a SQL store, one database's, through Drivine's schema machinery: the statements a
 * graph engine runs itself, which a Cypher statement on Postgres, Oracle or SQLite would otherwise be refused as.
 * [SchemaDdl] reads the statement, and what it gives is applied as the schema API applies a spec, so the items
 * are the ones the API makes, with the same names, expressions and inventory entries.
 *
 * - `CREATE` ensures the spec with [SchemaManager.ensure], under the owner [OWNER] and never an application's
 *   own, whose declared items an ensure of its catalog would replace. The DDL's name (or, for DDL that names
 *   none, the API's name for its spec), where it is not the name the item goes by, is kept as an alias of the
 *   item (see [org.drivine.schema.SchemaAliases]): so too over an item of the same properties in another order,
 *   which the API names by that order. Over an
 *   item of the same kind, label and properties that is there already, `IF NOT EXISTS` does nothing but add the
 *   alias, and without it the statement fails, as on Neo4j. A name that another item goes by, by the API's name
 *   or an alias, is refused, with or without `IF NOT EXISTS`.
 * - `DROP` finds the item by the API's name, or else by an alias, and removes it with [SchemaManager.remove].
 *   An item another owner's marker records, such as an application's, is forgotten by [OWNER] alone and left in
 *   place, with a warning, and no longer goes by the alias dropped. An item no one has by that name is no error
 *   with `IF EXISTS`, and fails without it.
 * - `CALL db.awaitIndexes(…)` does nothing: an index on a SQL store is whole once it is made.
 * - A constraint of Drivine's own nodes is ensured as Drivine ensures it, and recorded in no inventory.
 *
 * DDL is not run inside a transaction ([refusedInTransaction]): Drivine's schema statements run on a connection
 * of their own, committed as they go, which the caller's rollback would not undo.
 *
 * Each command runs on one connection of the database's provider, borrowed as it starts and given back as it ends
 * (see [PinnedConnections]): its catalog reads, its DDL and its marker's writes, which would otherwise borrow one
 * each, some twenty for a `CREATE CONSTRAINT` (#654).
 */
internal class SchemaCommands(
    /** The database's registered name. */
    private val database: String,
    private val schemaManager: SchemaManager,
    /** The database's non-transactional manager, asked for only when a command runs. */
    private val manager: () -> PersistenceManager,
    /** The database's connection provider, whose one connection a command runs on. */
    private val provider: () -> ConnectionProvider,
) {

    private val logger = LoggerFactory.getLogger(SchemaCommands::class.java)

    /** Serializes the commands, each of which reads what is there before it changes it. */
    private val lock = Any()

    /** Runs [ddl], schema DDL that [SchemaDdl.translate] read. */
    fun run(ddl: SchemaDdlResult) {
        when (ddl) {
            is SchemaDdlResult.NotDdl -> throw IllegalArgumentException("Not schema DDL")
            is SchemaDdlResult.Refused -> throw DrivineException(ddl.message)
            is SchemaDdlResult.AwaitIndexes -> logger.debug("db.awaitIndexes on '{}': an index on a SQL store is whole once made, so nothing is waited for", database)
            is SchemaDdlResult.Reserved -> command {
                val result = manager().constraints.ensure(ddl.spec, replaceBlockingIndex = true)
                logger.debug("Schema DDL on '{}' named Drivine's own {} on {}{}, ensured as Drivine ensures it: {}", database, ddl.spec.kind, ddl.spec.label, ddl.spec.properties, result)
            }
            is SchemaDdlResult.Create -> command { create(ddl) }
            is SchemaDdlResult.Drop -> command { drop(ddl) }
        }
    }

    /** Runs [block] under [lock], on one connection of the database held for its length. */
    private fun command(block: () -> Unit) = synchronized(lock) { PinnedConnections.pinned(provider(), block) }

    /** What is recorded of every index and constraint, with their aliases. */
    private fun recorded(pm: PersistenceManager): List<RecordedItem> = pm.indexes.recorded() + pm.constraints.recorded()

    private fun create(ddl: SchemaDdlResult.Create) {
        val pm = manager()
        val spec = ddl.spec
        val grammar = pm.indexes.grammar
        if (spec is TextIndexSpec && !grammar.supportsTextIndex) throw unsupportedTextIndex(spec, grammar.engine)
        val made = spec.made()
        val name = ddl.alias ?: spec.effectiveName
        val items = recorded(pm)
        items.firstOrNull { (it.info.name == name || name in it.aliases) && !grammar.matchesIdentity(it.info, made) }?.let { namesake ->
            throw DrivineException(
                "There is already a schema item named '$name' on '$database': ${describe(namesake.info)}. " +
                    "${describe(made)} is not created under that name."
            )
        }
        val existing = items.firstOrNull { grammar.matchesIdentity(it.info, made) }
        if (existing != null) {
            val equivalent = grammar.matchesShape(existing.info, made)
            if (!ddl.ifNotExists) {
                throw DrivineException(
                    if (equivalent) "An equivalent ${what(spec)} already exists on '$database': ${describe(existing.info)}."
                    else "A ${what(spec)} on ${made.label}${made.properties} already exists on '$database', of another shape: ${describe(existing.info)}."
                )
            }
            if (!equivalent) {
                logger.warn("Schema DDL on '{}': {} exists already, of another shape than {}, and IF NOT EXISTS leaves it as it is", database, describe(existing.info), describe(made))
                return
            }
        }
        schemaManager.ensure(SchemaCatalog.of(spec).named(OWNER).forDatabase(database))
        val item = existing ?: recorded(pm).firstOrNull { grammar.matchesIdentity(it.info, made) } ?: return
        // The DDL's name, or the API's name for its spec, where the item goes by neither: as over an item of the
        // same properties in another order, whose name the API gives of that order.
        val alias = name
        if (alias == item.info.name || alias in item.aliases) return
        pm.indexes.alias(item.info, item.aliases + alias)
        logger.info("Schema DDL on '{}': {} goes by '{}' too", database, describe(item.info), alias)
    }

    private fun drop(ddl: SchemaDdlResult.Drop) {
        val pm = manager()
        val items = recorded(pm)
        val what = ddl.kind.name.lowercase()
        val spec = (items.firstOrNull { it.info.name == ddl.name } ?: items.firstOrNull { ddl.name in it.aliases })?.let { specOf(it.info) }
            ?: schemaManager.ensured(OWNER).firstOrNull { it.effectiveName == ddl.name }
        if (spec == null) {
            if (ddl.ifExists) return
            throw DrivineException("There is no $what named '${ddl.name}' on '$database' to drop.")
        }
        val index = spec is IndexSpec
        if (index != (ddl.kind == SchemaDdlResult.Drop.Kind.INDEX)) {
            val (other, drop) = if (index) "an index" to "INDEX" else "a constraint" to "CONSTRAINT"
            throw DrivineException("'${ddl.name}' is $other on '$database', ${describe(spec)}: DROP ${ddl.kind.name} does not drop it, and DROP $drop does.")
        }
        if (SchemaDdl.reserved(spec)) {
            throw DrivineException("'${ddl.name}' is Drivine's own constraint on ${spec.label}${spec.properties}, which Drivine keeps, and is not dropped.")
        }
        schemaManager.remove(SchemaCatalog.of(spec).named(OWNER).forDatabase(database))
        // An item another owner declares is left in place by the removal: it no longer goes by this DDL's name.
        recorded(pm).firstOrNull { ddl.name in it.aliases }?.let { pm.indexes.alias(it.info, it.aliases - ddl.name) }
    }

    companion object {
        /** The owner the items DDL makes are ensured and recorded under: never an application's own. */
        const val OWNER = "ddl"

        /** The stores whose Cypher is compiled to SQL, where Drivine runs schema DDL itself. */
        val STORES = setOf(DatabaseType.POSTGRES, DatabaseType.ORACLE, DatabaseType.SQLITE)

        /** The commands of the database [name] of [registry], ensured and removed through [factory]'s managers. */
        fun of(factory: PersistenceManagerFactory, registry: DatabaseRegistry, name: String): SchemaCommands {
            val database = registry.resolveDatabaseName(name) ?: name
            return SchemaCommands(
                database,
                SchemaManager(factory, registry, emptyList()),
                manager = { factory.get(database, PersistenceManagerType.NON_TRANSACTIONAL) },
                provider = { registry.connectionProvider(database) ?: throw DrivineException("No database is registered under name: $database") },
            )
        }

        /** The commands of [provider]'s database, for a manager made without a factory: a registry of that database alone. */
        fun standalone(provider: ConnectionProvider): SchemaCommands {
            val registry = DatabaseRegistry(DataSourceMap())
            registry.register(provider)
            return of(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, provider.name)
        }

        /**
         * The schema DDL [spec] is, run on a store of [type]: null where it is none, or where the store runs
         * Cypher itself and is sent the statement as it is, or where the statement is not Cypher.
         */
        fun ddlOf(type: DatabaseType, spec: QuerySpecification<*>): SchemaDdlResult? {
            if (type !in STORES) return null
            val statement = spec.statement ?: return null
            if (statement.language != QueryLanguage.CYPHER) return null
            return SchemaDdl.translate(statement.text).takeUnless { it is SchemaDdlResult.NotDdl }
        }

        /**
         * Whether [spec], run on a store of [type] inside an open transaction, is `db.awaitIndexes`, which does
         * nothing there; throws for any other schema DDL, which Drivine runs on connections of its own and the
         * transaction's rollback would not undo. False for a statement that is no DDL.
         */
        fun refusedInTransaction(type: DatabaseType, spec: QuerySpecification<*>): Boolean = when (val ddl = ddlOf(type, spec)) {
            null -> false
            is SchemaDdlResult.AwaitIndexes -> true
            is SchemaDdlResult.Refused -> throw DrivineException(ddl.message, spec = spec)
            else -> throw DrivineException(
                "A schema command is not run inside a transaction on ${if (type == DatabaseType.SQLITE) "SQLite" else type.name.lowercase().replaceFirstChar { it.uppercase() }}: " +
                    "Drivine applies it on connections of its own, which the transaction's rollback would not undo. " +
                    "Run it outside the transaction, with a non-transactional PersistenceManager.",
                spec = spec,
            )
        }

        /** The spec of an item introspection found, by its kind, label, properties and name. */
        internal fun specOf(info: SchemaItemInfo): SchemaItemSpec {
            val name = info.name?.takeIf { it.isNotBlank() }
            return when (info.kind) {
                SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(info.label, info.properties, name)
                SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(info.label, info.properties, name, info.analyzer)
                SchemaItemKind.VECTOR_INDEX ->
                    VectorIndexSpec(info.label, info.properties.single(), info.dimensions ?: 1, info.similarity ?: SimilarityFunction.COSINE, name)
                SchemaItemKind.TEXT_INDEX -> TextIndexSpec(info.label, info.properties.single(), name)
                SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(info.label, info.properties, name)
            }
        }

        private fun what(spec: SchemaItemSpec) = if (spec is ConstraintSpec) "constraint" else "index"

        private fun describe(info: SchemaItemInfo) = "${info.kind} ${info.name ?: ""} on ${info.label}${info.properties}".replace("  ", " ")

        private fun describe(spec: SchemaItemSpec) = "${spec.kind} ${spec.effectiveName} on ${spec.label}${spec.properties}"
    }
}
