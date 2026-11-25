package org.drivine.migration

import org.drivine.DrivineException
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.cypher.store.migrate.LayoutMigrator
import org.drivine.cypher.store.migrate.MigrationAction
import org.drivine.cypher.store.migrate.MigrationOutcome
import org.drivine.cypher.store.migrate.MigrationRequest
import org.drivine.cypher.store.migrate.MigrationStep
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphView
import org.drivine.annotation.JsonPacked
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.slf4j.LoggerFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Marked the store layout migration as not yet proven on every store, until 0.8.0 proved it on Postgres, Oracle and
 * SQLite (liberation-data/drivine4j#832): nothing is marked with it now, so nothing needs the opt-in, and an `@OptIn` of
 * it, or a `-opt-in` compiler option naming it, is left over. Kept, still a marker, so that code that opted in compiles
 * with one warning, this deprecation, rather than a second that it opts in to no marker.
 */
@Deprecated("The store layout migration needs no opt-in now that it is proven on Postgres, Oracle and SQLite: remove the @OptIn, or the -opt-in compiler option.")
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Nothing is marked with ExperimentalStoreLayoutMigration now: the store layout migration needs no opt-in.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
annotation class ExperimentalStoreLayoutMigration

/**
 * Moves a graph on Postgres, Oracle or SQLite from store layout 1, which every Drivine before 0.8.0 wrote, to layout 2,
 * which this version reads, in place of reloading it (liberation-data/drivine-cypher-spec#256). The shared module's
 * [LayoutMigrator] decides each round, as every library migrates alike; this runs what it decides on the provider's
 * store, and gives back what it reports, as a [MigrationReport].
 *
 * Run it while nothing else uses the graph, with a backup taken first: a migration stopped at any moment is resumed by
 * running it again, with the same declarations. What layout 1 can't tell, which texts are dates or moments, which whole
 * numbers are floats, and which texts are bytes, it is told by [declarations], label by label and property by property,
 * as the application's model has them; a dry run ([dryRun]) writes nothing, and reports what would change, the values that
 * are no value of their declared kind, the properties whose values all read as one kind (the candidates to declare), and
 * the uniqueness constraints' keys that may collide.
 *
 * A migration makes no index or constraint: the application's schema plan makes its declared ones at its next start. Until
 * one does, statements give the same answers, unindexed. The report's `notes` say so, and that the count store is left
 * unseeded.
 *
 * What stays drivine4j's: the connections. Statements run on a connection of their own that neither makes the graph's
 * tables nor judges its layout, as [JdbcConnectionProvider.connect] would, and refuse the graph; the lock is held on
 * another, in a transaction left open until the migration ends; a transaction's statements commit together or not at all.
 * A migration given a connection of the application's transaction (a Spring or JTA one) is refused before it reads the
 * graph, as that transaction would hold every round's writes until it ends.
 *
 * @param provider the store and graph to migrate
 */
class StoreLayoutMigration(private val provider: JdbcConnectionProvider) {

    private val graph = provider.sqlGraph
        ?: throw DrivineException("A store layout migration runs on Postgres, Oracle or SQLite, which keep a graph in tables, not on ${provider.type}.")
    private val store = graph.store
    private val stamps = StoreStampStatements.of(store)

    /**
     * Migrates the graph, or with [dryRun] reports what a migration would do and writes nothing, by [declarations], reading
     * and writing [batchSize] rows at a time. Where [keepInvalid], a value that is no value of its declared kind is kept as
     * it is, and named in the report, where it would otherwise refuse the graph. A declaration is refused before anything
     * runs, with an [IllegalArgumentException], where one label's property is declared two kinds, or where it is Drivine's
     * own label's, and so is a [batchSize] under 1.
     */
    @JvmOverloads
    fun migrate(
        declarations: List<Declaration>,
        batchSize: Int = 1000,
        dryRun: Boolean = false,
        keepInvalid: Boolean = false,
    ): MigrationReport {
        val migrator = LayoutMigrator(store)
        val request = MigrationRequest(declarations.map { it.toModule() }, batchSize, dryRun, keepInvalid)
        migrator.validate(request)?.let { throw IllegalArgumentException(it) }
        val work = provider.connectUnjudged()
        var lock: JdbcConnection? = null
        try {
            // A transaction of the application's (Spring's, or JTA's) would hold every round's writes, and the lock's, until it
            // ends, and the lock's rollback would mark it to roll back: the migration is run outside one.
            if (work.joined) {
                throw DrivineException("A store layout migration of '${store.name}' runs on connections of its own, and was given one in a transaction of the application's: run it outside a transaction.")
            }
            var step = migrator.start(request)
            while (step is MigrationStep.Run) {
                val outcomes = mutableListOf<MigrationOutcome>()
                for (action in step.actions) {
                    val outcome = try {
                        when (action) {
                            is MigrationAction.Lock -> {
                                // Kept as soon as it is had, so the finally below gives it back where its transaction or
                                // its statement fails, as Oracle's does when another migration holds the row past its wait.
                                if (lock == null) {
                                    val held = provider.connectUnjudged()
                                    lock = held
                                    held.startTransaction()
                                    held.query(QuerySpecification.withStatement(sqlStatement(action.statement)))
                                }
                                MigrationOutcome.Ran
                            }
                            else -> run(work, action)
                        }
                    } catch (e: Exception) {
                        logger.warn("Store layout migration of '{}': {} failed: {}", store.name, describe(action), e.message)
                        MigrationOutcome.Failed(e.message ?: e.toString())
                    }
                    outcomes += outcome
                    if (outcome is MigrationOutcome.Failed) break
                }
                step = migrator.resume(step.state, outcomes)
            }
            val report = MigrationReport.of((step as MigrationStep.Done).report)
            logger.info("Store layout migration of '{}': {}: {}", store.name, report.status?.word, report.message)
            report.notes.forEach { logger.warn("Store layout migration of '{}': {}", store.name, it) }
            return report
        } finally {
            lock?.let { held -> runCatching { held.rollbackTransaction() }; runCatching { held.release() } }
            runCatching { work.release() }
        }
    }

    /** Runs [action], any but a lock, on [work], and gives back what came of it. */
    private fun run(work: JdbcConnection, action: MigrationAction): MigrationOutcome = when (action) {
        is MigrationAction.Query -> MigrationOutcome.Rows(rows(work, action.statement, action.parameters))
        is MigrationAction.Execute -> { rows(work, action.statement, action.parameters); MigrationOutcome.Ran }
        is MigrationAction.Transaction -> {
            work.startTransaction()
            try {
                action.statements.forEach { rows(work, it.statement, it.parameters) }
                work.commitTransaction()
            } catch (e: Exception) {
                runCatching { work.rollbackTransaction() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            MigrationOutcome.Ran
        }
        // The store's tables made as a first connection makes them, without the verdict it asks first, which refuses the graph.
        MigrationAction.Prepare -> { graph.ensure { work.query(QuerySpecification.withStatement(sqlStatement(it))) }; MigrationOutcome.Ran }
        MigrationAction.AssignStamp -> {
            rows(work, stamps.assignIfAbsent, StoreStamp.assignParameters(UUID.randomUUID().toString(), Instant.now().toString(), StoreStamp.engine(store)))
            MigrationOutcome.Ran
        }
        is MigrationAction.Lock -> throw IllegalStateException("A lock is held on a connection of its own")
    }

    /** The rows [statement], one of the module's, gives on [work], bound [parameters]: each a map where its row is a JSON object. */
    private fun rows(work: JdbcConnection, statement: String, parameters: Map<String, Any?>): List<Any?> =
        work.query(QuerySpecification.withStatement(sqlStatement(statement)).bind(parameters)).map { row ->
            (row as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: row
        }

    private fun describe(action: MigrationAction): String = when (action) {
        is MigrationAction.Query -> "a query (${action.purpose})"
        is MigrationAction.Execute -> "a statement (${action.purpose})"
        is MigrationAction.Transaction -> "a transaction of ${action.statements.size} statement(s)"
        MigrationAction.Prepare -> "making the store's tables"
        MigrationAction.AssignStamp -> "stamping the graph"
        is MigrationAction.Lock -> "the lock"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StoreLayoutMigration::class.java)

        /**
         * What the node fragment [type] (`@NodeFragment`) says of its properties that layout 1 can't tell, a declaration for
         * each of its labels and each of its fields of a temporal type (a `java.util.Date` a moment), a float or bytes, or a
         * list, a set or an array of a temporal type or a float, by the name it is stored under: what a migration of the nodes
         * it maps is told. A field layout 1 saved in another form than its kind's gets none, as the module would read it as no
         * value of its kind and refuse the migration: a `java.time.Duration`, saved as its seconds, a number ([kindOf]), and
         * one that is `@JsonPacked`, saved as a JSON text, or carries `@JsonSerialize` or `@JsonFormat`, saved as it says ([shaped]).
         */
        @JvmStatic
        fun declarationsOf(type: Class<*>): List<Declaration> {
            val model = FragmentModel.from(type)
            val shaped = shaped(type)
            return model.labels.flatMap { label -> declared(model, label, shaped) }
        }

        /**
         * What the relationship fragment [type] (`@RelationshipFragment`) says of its properties, for the relationships of
         * [relationshipType]: each of its properties but the one that points at the node it reaches, as [declarationsOf].
         */
        @JvmStatic
        fun declarationsOf(relationshipType: String, type: Class<*>): List<Declaration> {
            val shaped = shaped(type)
            return type.kotlin.memberProperties.filter { property ->
                val target = (property.returnType.classifier as? KClass<*>)?.java
                property.name !in shaped &&
                    target?.isAnnotationPresent(NodeFragment::class.java) != true && target?.isAnnotationPresent(GraphView::class.java) != true
            }.mapNotNull { property ->
                val stored = property.findAnnotation<GraphProperty>()?.value?.takeIf { it.isNotEmpty() } ?: property.name
                kindOf(property.returnType.toString())?.let { Declaration(relationshipType, stored, it) }
            }
        }

        private fun declared(model: FragmentModel, label: String, shaped: Set<String>) = model.fields
            .filter { !it.stamp && it.nodeLabels == null && it.propertyBag == null && it.name !in shaped }
            .mapNotNull { field -> kindOf(field.typeString)?.let { Declaration(label, field.propertyName, it) } }

        /**
         * The kind a field whose type is written [typeString] (`java.time.LocalDate`, `kotlin.collections.List<java.time.LocalDate>`,
         * or as Kotlin writes a Java field's, `kotlin.collections.(Mutable)List<java.time.LocalDate!>!`) is declared: its own, or
         * for a list, a set or an array, its items'; null for one layout 1 holds as layout 2 does, for a `java.time.Duration`,
         * which layout 1 saved as its seconds, a number, that the module reads as no duration, and for a list of lists or
         * of arrays (`DoubleArray`, `ByteArray`), whose items the module reads as no value of a kind, so a declaration would
         * refuse the migration.
         */
        internal fun kindOf(typeString: String): Kind? {
            val type = plain(typeString)
            val item = COLLECTION.matchEntire(type)?.groupValues?.get(2)?.let(::plain)
                ?: type.takeIf { it.endsWith("[]") && it != "byte[]" }?.removeSuffix("[]")
                ?: return KINDS[type]
            return if (COLLECTION.matches(item) || item.endsWith("[]") || item.endsWith("Array")) null else KINDS[item]
        }

        /**
         * The fields of [type] that are `@JsonPacked`, on the field, its getter or its constructor parameter, saved as a JSON
         * text, or carry `@JsonSerialize` or `@JsonFormat`, saved as they say (a float as its text, say), which can't be told
         * from the field's type: what the module may read as no value of a kind, so a declaration would refuse the migration.
         */
        private fun shaped(type: Class<*>): Set<String> =
            INTROSPECTOR.serializationConfig.introspect(INTROSPECTOR.constructType(type)).findProperties()
                .filter { p -> listOfNotNull(p.field, p.getter, p.constructorParameter).any { m -> SHAPING.any(m::hasAnnotation) } }
                .map { it.name }.toSet()

        private val SHAPING = listOf(JsonPacked::class.java, JsonSerialize::class.java, JsonFormat::class.java)

        private val INTROSPECTOR = jacksonObjectMapper()

        /** [type] without its nullability, or a Java type's `!`. */
        private fun plain(type: String) = type.trim().removeSuffix("?").removeSuffix("!")

        /** A list's, a set's or an array's type, its items' in the second group. */
        private val COLLECTION = Regex("""(kotlin\.collections\.(?:\(Mutable\)|Mutable)?\w*(?:List|Set|Collection)|java\.util\.\w*(?:List|Set|Collection)|kotlin\.Array)<(?:out |\(out\) |\? extends )?(.+)>""")

        private val KINDS = mapOf(
            "java.time.LocalDate" to Kind.DATE,
            "java.time.LocalTime" to Kind.LOCAL_TIME,
            "java.time.OffsetTime" to Kind.TIME,
            "java.time.LocalDateTime" to Kind.LOCAL_DATE_TIME,
            "java.time.ZonedDateTime" to Kind.DATE_TIME,
            "java.time.OffsetDateTime" to Kind.DATE_TIME,
            "java.time.Instant" to Kind.DATE_TIME,
            "java.util.Date" to Kind.DATE_TIME,
            // Not a java.time.Duration: layout 1 saved one as its seconds, a number (before drivine4j#862), no duration.
            "java.time.Period" to Kind.DURATION,
            "kotlin.Double" to Kind.FLOAT, "java.lang.Double" to Kind.FLOAT, "double" to Kind.FLOAT,
            "kotlin.Float" to Kind.FLOAT, "java.lang.Float" to Kind.FLOAT, "float" to Kind.FLOAT,
            "kotlin.DoubleArray" to Kind.FLOAT, "kotlin.FloatArray" to Kind.FLOAT,
            "kotlin.ByteArray" to Kind.BYTES, "byte[]" to Kind.BYTES,
        )
    }
}
