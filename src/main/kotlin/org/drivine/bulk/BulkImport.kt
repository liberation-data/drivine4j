package org.drivine.bulk

import org.drivine.DrivineException
import org.drivine.connection.CypherCompile
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.cypher.api.Store
import org.drivine.cypher.store.bulk.BulkImportStatements
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import java.util.function.Consumer

/**
 * Writes many nodes and relationships into a graph on Postgres at once (liberation-data/drivine4j#684). This is far faster
 * than saving them, and it writes the rows a save writes. The shared module's [BulkImportStatements] gives each statement;
 * this runs them, and copies the rows in through the driver's `COPY`.
 *
 * ```kotlin
 * val report = BulkImport(provider).run {
 *     nodes("Person", key = "id", rows = people)                       // Sequence<Map<String, Any?>>
 *     nodes("Repo", key = "slug", rows = repos)
 *     relationships("OWNS", from = BulkImport.End("Person", "id"), to = BulkImport.End("Repo", "slug"),
 *         pairs = owners.map { BulkImport.Pair(it.person, it.repo) })
 * }
 * ```
 *
 * **Nodes come first.** Each call to [Writer.nodes] gives the nodes of one label, each a map of its properties. Each node
 * is written as a save writes it:
 * - each value as Drivine stores it, a temporal value, a list, a number;
 * - nulls left out;
 * - a key beginning with `$` held with one more;
 * - a stamp of its own, so a row that holds the stamp's property, `__drivine.stamp`, is refused;
 * - the lowercased copy of each of [Writer.nodes]' `lowercased` properties, which a fragment's `@CaseInsensitive`
 *   properties need for `eqIgnoreCase` to find them.
 *
 * The import makes nodes, and finds none to merge into: a node whose key the graph or the import holds already refuses it.
 *
 * **Then relationships.** Each call to [Writer.relationships] gives the relationships of one type, from one label's nodes
 * to another's, by their keys. An end is a node the import made, or one the graph holds. A key no node holds, or two of the
 * graph's nodes hold, refuses the import. A relationship is made as `CREATE` makes one, beside any other of its type
 * between the same nodes. A node of the graph that gains one is stamped anew, so an object loaded from it before is seen
 * to be stale.
 *
 * **All or nothing, and alone.** The import is one transaction, on a connection of its own, which a refusal or any failure
 * rolls back, a refusal caught in the import's own lambda too: every later call, and the import, fail with it. While it
 * runs:
 * - other writers to the graph wait for it, and reads go on until the first call of relationships;
 * - from then on reads wait too: the edge table's foreign keys and its two indexes are dropped, so each relationship costs
 *   the same however large the graph is;
 * - at the end they are made again as the store's layout made them, which checks every relationship.
 *
 * An import given a connection of the application's transaction, a Spring or JTA one, is refused before it writes; a
 * provider not of Postgres, or whose graph name cannot name tables, as the import is made.
 *
 * @param provider the Postgres store and graph to import into
 */
class BulkImport(private val provider: JdbcConnectionProvider) {

    init {
        if (provider.type != DatabaseType.POSTGRES) {
            throw DrivineException("A bulk import runs on Postgres for now, not on ${provider.type}: save the nodes with saveAll.")
        }
        // A graph whose name cannot name tables has none to import into, and the store's DDL below would throw on its name.
        provider.schemaRefusal?.let { throw DrivineException("A bulk import needs the graph's tables: $it") }
    }

    private val graph = (provider.sqlGraph ?: throw DrivineException("A bulk import needs a graph in tables, as Postgres keeps one.")).store as PostgresDdl
    private val statements = BulkImportStatements.of(graph) as BulkImportStatements.Postgres

    /** A node's label and the property its relationships find it by. */
    data class End(val label: String, val key: String)

    /** A relationship, from the node keyed [from] to the node keyed [to], with its [properties]. */
    data class Pair @JvmOverloads constructor(val from: Any, val to: Any, val properties: Map<String, Any?> = emptyMap())

    /** What an import wrote: the nodes of each label, the relationships of each type, and the seconds it took. */
    data class Report(val nodes: Map<String, Long>, val relationships: Map<String, Long>, val seconds: Double)

    /**
     * Runs the import [writes] gives, with [maintenanceMegabytes] of `maintenance_work_mem` to make the edge table's
     * indexes and foreign keys again with, and commits it. Throws a [DrivineException] where it refuses the import, and
     * rolls everything back; so does any other failure.
     */
    @JvmSynthetic // Java has the overload below: with both in its sight, a lambda with no type given fits either.
    fun run(maintenanceMegabytes: Int = 1024, writes: Writer.() -> Unit): Report {
        require(maintenanceMegabytes > 0) { "maintenanceMegabytes is $maintenanceMegabytes: more than 0." }
        val start = System.nanoTime()
        val connection = provider.connect() as JdbcConnection
        var failure: Throwable? = null
        try {
            if (connection.joined) {
                throw DrivineException("A bulk import into '${graph.name}' runs on a connection of its own, and was given one in a transaction of the application's: run it outside a transaction.")
            }
            connection.startTransaction()
            val writer = Writer(connection)
            try {
                writer.begin()
                writer.writes()
                writer.finish(maintenanceMegabytes)
                connection.commitTransaction()
            } catch (e: Exception) {
                runCatching { connection.rollbackTransaction() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            return Report(writer.nodeCounts.toMap(), writer.edgeCounts.toMap(), (System.nanoTime() - start) / 1e9)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // As a store layout migration gives its connections back: a failure to give it back hides no failure of the import's.
            runCatching { connection.release(failure) }.exceptionOrNull()?.let { failure?.addSuppressed(it) }
        }
    }

    /** [run] for Java, whose lambda need return nothing: `importer.run(writer -> writer.nodes("Team", "code", teams))`. */
    @JvmOverloads
    fun run(maintenanceMegabytes: Int = 1024, writes: Consumer<Writer>): Report = run(maintenanceMegabytes) { writes.accept(this) }

    /** What an import is given its nodes and relationships through, call by call: nodes first. */
    inner class Writer internal constructor(private val connection: JdbcConnection) {

        internal val nodeCounts = linkedMapOf<String, Long>()
        internal val edgeCounts = linkedMapOf<String, Long>()
        private var deferred = false

        /**
         * The first failure of a call of [nodes] or [relationships]. A refusal leaves the transaction able to commit, and its
         * call's rows staged, so one caught in [run]'s own lambda still fails every later call and the import: else the
         * import would commit what the other calls wrote, and the next call would make the refused call's rows.
         */
        private var failed: Throwable? = null

        private inline fun <T> call(block: () -> T): T {
            failed?.let { throw DrivineException("A bulk import into '${graph.name}' is refused: an earlier call of nodes or relationships failed. Nothing was written.", it) }
            return try {
                block()
            } catch (e: Throwable) {
                failed = e
                throw e
            }
        }

        private val mark = Stamps.fresh()

        internal fun begin() {
            query(statements.lock)
            execute(statements.lockTables)
            statements.createStages.forEach(::execute)
        }

        /**
         * Makes [rows], nodes of [label] and of [otherLabels], each a map of its properties, which [key] holds the node's
         * key under: its value is what [relationships] name the node by. The properties [lowercased] names are given their
         * lowercased copies, as a fragment's `@CaseInsensitive` properties are. Refused after the first call of
         * [relationships], and where a node holds no [key], or a key the graph or the import holds already.
         */
        @JvmOverloads
        fun nodes(label: String, key: String, rows: Sequence<Map<String, Any?>>, otherLabels: Collection<String> = emptyList(), lowercased: Collection<String> = emptyList()): Unit = call {
            if (deferred) throw IllegalStateException("A bulk import gives its nodes before any relationship: '$label' came after one.")
            val labels = CypherCompile.storeText((otherLabels + label).distinct().sorted(), Store.POSTGRES)
            val written = connection.copyIn(statements.copyNodes, rows.map { row ->
                val properties = row.filterValues { it != null }
                val id = properties[key] ?: throw DrivineException("A node of '$label' in a bulk import holds no '$key', its key: $row")
                // The stamp is the node's own, kept beside its props: one given here would be a property no save writes.
                if (Stamps.PROPERTY in properties) throw DrivineException("A node of '$label' in a bulk import holds '${Stamps.PROPERTY}', the stamp the import gives it: leave it out. $row")
                csv(CypherCompile.storeText(id, Store.POSTGRES), labels, CypherCompile.storeText(properties, Store.POSTGRES), Stamps.fresh())
            })
            refuse(statements.takenKeys(label, key), "nodes of '$label' whose '$key' the graph or the import holds already, or that give it twice")
            execute(statements.makeNodes(label, key), mapOf("lower" to CypherCompile.storeText(lowercased.distinct(), Store.POSTGRES)))
            execute(statements.clearNodes)
            nodeCounts.merge(label, written, Long::plus)
        }

        /** [nodes] for Java: [rows] an [Iterable]. */
        @JvmOverloads
        fun nodes(label: String, key: String, rows: Iterable<Map<String, Any?>>, otherLabels: Collection<String> = emptyList(), lowercased: Collection<String> = emptyList()) =
            nodes(label, key, rows.asSequence(), otherLabels, lowercased)

        /**
         * Makes [pairs], relationships of [type] from [from]'s nodes to [to]'s, each found by its key: a node the import
         * made, or one the graph holds. Refused where a key names no node, or two of the graph's.
         */
        fun relationships(type: String, from: End, to: End, pairs: Sequence<Pair>): Unit = call {
            if (!deferred) {
                statements.defer.forEach(::execute)
                deferred = true
            }
            val (start, end) = BulkImportStatements.End(from.label, from.key) to BulkImportStatements.End(to.label, to.key)
            val written = connection.copyIn(statements.copyEdges, pairs.map { pair ->
                csv(
                    CypherCompile.storeText(pair.from, Store.POSTGRES),
                    CypherCompile.storeText(pair.to, Store.POSTGRES),
                    CypherCompile.storeText(pair.properties.filterValues { it != null }, Store.POSTGRES),
                )
            })
            refuse(statements.ambiguousKeys(start, "src"), "'$type' relationships from keys of '${from.label}' that two of its nodes hold")
            refuse(statements.ambiguousKeys(end, "dst"), "'$type' relationships to keys of '${to.label}' that two of its nodes hold")
            execute(statements.findEnds(start, "src"))
            execute(statements.findEnds(end, "dst"))
            refuse(statements.missingEnds(start, end), "'$type' relationships whose start or end is no node")
            execute(statements.makeEdges(start, end), mapOf("type" to type))
            execute(statements.relink(start, end), mapOf("mark" to mark))
            execute(statements.clearEdges)
            edgeCounts.merge(type, written, Long::plus)
        }

        /** [relationships] for Java: [pairs] an [Iterable]. */
        fun relationships(type: String, from: End, to: End, pairs: Iterable<Pair>) = relationships(type, from, to, pairs.asSequence())

        internal fun finish(maintenanceMegabytes: Int): Unit = call {
            (if (deferred) statements.rebuild(maintenanceMegabytes) else statements.analyze).forEach(::execute)
        }

        private fun execute(statement: String, parameters: Map<String, Any?> = emptyMap()) {
            connection.query(QuerySpecification.withStatement(statement).bind(parameters))
        }

        private fun query(statement: String): List<Any?> = connection.query(QuerySpecification.withStatement(statement))

        /** Refuses the import where [statement] gives any row: each is one of what it refuses, at most ten. */
        private fun refuse(statement: String, what: String) {
            val rows = query(statement)
            if (rows.isNotEmpty()) throw DrivineException("A bulk import into '${graph.name}' is refused: $what, as $rows. Nothing was written.")
        }
    }

    internal companion object {
        /** One line of CSV: each field quoted, a quote in it doubled, and a line end. */
        fun csv(vararg fields: String): String = fields.joinToString(",", postfix = "\n") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }
}
