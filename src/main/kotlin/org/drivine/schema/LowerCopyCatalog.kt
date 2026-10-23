package org.drivine.schema

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.manager.PersistenceManager
import org.drivine.model.LowerCopies
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.drivine.store.StoreIdentityResolver
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The statements that fill in the lowercased copies of a property (see [LowerCopies]) and record that
 * every node of a label has its copy, in the store's own language. The graph engines keep the record
 * on the store's `DrivineStore` node, Oracle and Postgres in the graph's catalog table: see
 * [CypherLowerCopies] and the grammars of the SQL stores.
 *
 * On the graph engines a record names a label and a property as the JSON array
 * `["<label>","<property>"]`, written as [entry] writes it; Oracle and Postgres keep the two in
 * columns of a catalog row, and [entries] reads them back as the same pair.
 */
interface LowerCopyStatements : ReservedNodeStatements {

    /**
     * Writes the copy of [property] on at most `$batch` nodes of [label] whose copy is not the
     * property lowercased, and removes it from those whose property has no value. Returns one row:
     * the number of nodes it wrote. Run until it writes fewer than `$batch`.
     */
    fun fill(label: String, property: String): String

    /**
     * Records that every node of a label has the copy of a property: binds `$label`, `$properties`, the
     * property as [json] writes a list of it, and `$entry`, the two as [entry] writes them. Run again,
     * it records it once.
     */
    val record: String

    /** Reads every record, binding nothing: each row is one, as [entries] reads it. */
    val read: String

    /** The label and property of each record among [rows], as [read] gives them. */
    fun entries(rows: List<Any?>): Set<Pair<String, String>> = rows.mapNotNull { row ->
        (row as? String)?.let { parsed(it) }
    }.toSet()

    companion object {
        private val mapper = ObjectMapper()

        /**
         * [values] as a JSON array of strings, with nothing between its parts, and in each only a
         * backslash and a quote escaped: written so, byte for byte the same each time, it can be a key.
         */
        fun json(values: List<String>): String =
            values.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

        /** The record of [label] and [property]: the JSON array of the two, as [json] writes it. */
        fun entry(label: String, property: String): String = json(listOf(label, property))

        /** The label and property of [entry]; null for text that is not one. */
        fun parsed(entry: String): Pair<String, String>? = runCatching {
            val (label, property) = mapper.readValue(entry, List::class.java).map { it as String }
            label to property
        }.getOrNull()
    }
}

/**
 * The copies' statements in Cypher, for the graph engines. The record is a list of entries on the
 * store's `(:DrivineStore {key: 'singleton'})` node, under `lowered`.
 */
class CypherLowerCopies(
    private val label: String = StoreIdentityResolver.KEY_CONSTRAINT.label,
    private val key: String = StoreIdentityResolver.KEY_CONSTRAINT.properties.single(),
    private val singleton: String = StoreIdentityResolver.SINGLETON_KEY,
) : LowerCopyStatements {

    override fun fill(label: String, property: String): String {
        val copy = "n.${SchemaGrammar.quoted(LowerCopies.of(property))}"
        val value = "n.${SchemaGrammar.identifier(property)}"
        return """
            MATCH (n:${SchemaGrammar.identifier(label)})
            WHERE NOT coalesce($copy = toLower($value), $copy IS NULL AND $value IS NULL)
            WITH n LIMIT ${'$'}batch
            SET ${LowerCopies.setClause("n", property)}
            RETURN count(n)
        """.trimIndent()
    }

    /** The node's lock is taken before its list is read, so two processes recording at once both keep theirs: see [Stamps.lock]. */
    override val record: String = """
        MATCH (s:${SchemaGrammar.identifier(label)} {${SchemaGrammar.identifier(key)}: '$singleton'})
        ${Stamps.lock("s")}
        SET s.$LIST = [e IN coalesce(s.$LIST, []) WHERE e <> ${'$'}entry] + ${'$'}entry
    """.trimIndent()

    override val read: String = "MATCH (s:${SchemaGrammar.identifier(label)}) UNWIND coalesce(s.$LIST, []) AS e RETURN DISTINCT e"

    companion object {
        /** The property of the store's node that holds the records. */
        const val LIST = "lowered"
    }
}

/**
 * Which `@CaseInsensitive` properties have their lowercased copy (see [LowerCopies]) on every node of
 * a label, so that the query DSL may compare with the copy: those an `ensure` of an index or a
 * constraint that ignores case has filled in, as the store records them.
 *
 * What this process has recorded is known at once. What another process recorded is read from the
 * store as Oracle's labelled indexes are: never on the caller's thread, at least every [maxAge]
 * milliseconds while a property is asked after, on a schema connection of its own (see [CatalogReads]). Until a
 * read has come back, a property counts as not filled in, and the DSL lowercases the property itself,
 * which gives the same answer without the index. A record is never taken back: every save keeps the
 * copy in step, and `recreate` fills it in again where a write that bypassed Drivine left it out of
 * step.
 *
 * @param owner the auto-commit manager the copies are filled in and recorded through
 * @param query runs a statement on a schema connection of its own, binding nothing
 */
internal class LowerCopyCatalog(
    private val owner: PersistenceManager,
    private val query: (String) -> List<Any?>,
    private val maxAge: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refresh: Executor = CatalogReads.BACKGROUND,
) {
    private class Read(val entries: Set<Pair<String, String>>, val at: Long)

    private val statements: LowerCopyStatements by lazy {
        owner.reservedNode("the record of lowercased copies", { lowerCopies() }) { CypherLowerCopies() }
    }

    /** What this process has recorded. */
    private val recorded = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    @Volatile
    private var read: Read? = null

    private val reading = AtomicBoolean(false)

    /** Whether every node of [label] is known to have the copy of [property]. Never waits for the store. */
    fun filledIn(label: String, property: String): Boolean {
        if (label to property in recorded) return true
        val last = read
        if (last == null || clock() - last.at >= maxAge) readAgain()
        return read?.entries?.contains(label to property) == true
    }

    /**
     * Fills in the copy of each of [properties] on the nodes of [label], [BATCH] at a time, and records
     * that they all have it; one recorded already is left as it is, unless [again].
     */
    fun fillIn(label: String, properties: List<String>, again: Boolean = false) {
        if (properties.isEmpty()) return
        val stored = if (again) emptySet() else stored()
        properties.filter { again || label to it !in stored }.forEach { property ->
            var filled = 0L
            do {
                val written = owner.getOne(
                    QuerySpecification.withStatement(statements.fill(label, property)).bind(mapOf("batch" to BATCH)).transform(Number::class.java)
                ).toLong()
                filled += written
            } while (written >= BATCH)
            // The graph engines keep the record on the store's own node, which is made when it is not there.
            StoreIdentityResolver(owner).resolve()
            owner.execute(
                QuerySpecification.withStatement(statements.record)
                    .bind(mapOf("label" to label, "properties" to LowerCopyStatements.json(listOf(property)), "entry" to LowerCopyStatements.entry(label, property)))
            )
            recorded += label to property
            logger.info("Filled in the lowercased copy of {}.{} on {} node(s), and recorded every node has it", label, property, filled)
        }
    }

    /** What the store records, read now. */
    fun stored(): Set<Pair<String, String>> = statements.entries(query(statements.read))

    private fun readAgain() {
        if (!reading.compareAndSet(false, true)) return
        try {
            refresh.execute {
                try {
                    val at = clock()
                    read = Read(fetch() ?: read?.entries.orEmpty(), at)
                } finally {
                    reading.set(false)
                }
            }
        } catch (e: Throwable) {
            reading.set(false)
            if (e !is RejectedExecutionException) throw e
        }
    }

    private fun fetch(): Set<Pair<String, String>>? = try {
        stored()
    } catch (e: Exception) {
        // A store whose record cannot be read is compared without the copies: slower, and the same answers.
        logger.debug("The record of lowercased copies could not be read, so they are not compared with: {}", e.message)
        null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LowerCopyCatalog::class.java)

        /** How many nodes one statement fills in. */
        const val BATCH = 10_000
    }
}
