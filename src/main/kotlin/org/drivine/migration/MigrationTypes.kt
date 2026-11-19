package org.drivine.migration

import org.drivine.cypher.store.migrate.LayoutMigration
import org.drivine.cypher.store.migrate.Status as ModuleStatus
import org.drivine.cypher.store.migrate.MigrationReport as ModuleReport

/**
 * The kind a property is declared to a store layout migration ([Declaration]): what layout 1 can't tell of its values, which
 * texts are dates or moments, which whole numbers are floats, and which texts (base64) or lists of numbers are bytes. A
 * list's declaration is its items' kind, but for bytes, which a list of numbers is. [word] is the kind's name, as the report and the command line write it.
 */
@ExperimentalStoreLayoutMigration
enum class Kind(val word: String) {
    DATE("date"),
    LOCAL_TIME("localtime"),
    TIME("time"),
    LOCAL_DATE_TIME("localdatetime"),
    DATE_TIME("datetime"),
    DURATION("duration"),
    FLOAT("float"),
    BYTES("bytes"),
    ;

    /** The shared module's kind of the same name, which decides each rewrite: drivine4j's alone, unseen from Java too. */
    @get:JvmSynthetic
    internal val module: LayoutMigration.Kind get() = LayoutMigration.Kind.valueOf(name)

    companion object {
        /** The kind [word] names, as [Kind.word] writes it; null for none. */
        @JvmStatic
        fun of(word: String): Kind? = entries.firstOrNull { it.word == word }
    }
}

/**
 * That the values of [property] of the nodes of [label] (or of the relationships of that type) are of [kind]: what a store
 * layout migration is told of what layout 1 can't tell (see [StoreLayoutMigration]).
 */
@ExperimentalStoreLayoutMigration
data class Declaration(val label: String, val property: String, val kind: Kind) {
    /** The shared module's declaration of the same parts: drivine4j's alone, unseen from Java too. */
    @JvmSynthetic
    internal fun toModule() = LayoutMigration.Declaration(label, property, kind.module)
}

/**
 * What a store layout migration, or its dry run, found and did ([StoreLayoutMigration.migrate]), as the shared module
 * reports it to each library alike:
 *
 * - [status] and [message]: how it ended.
 * - [tables]: for `nodes` and `edges`, the rows read and the rows changed: in a dry run, what would change; once written,
 *   what was.
 * - [properties]: for each label (a relationship's type, for one) and property that a rewrite changes or finds a value of
 *   not of its declared kind, the count of each change and each such value.
 * - [observed]: for each label and property no declaration names, the count of its values of each class.
 * - [candidates]: the properties no declaration names whose values all read as one kind a declaration would change them
 *   by: what to declare, never applied unless declared.
 * - [kept]: once written, each value kept as it was, not of its declared kind, where the migration was told to keep them.
 * - [collisions]: keys a uniqueness constraint would refuse, with samples: after the writes, exactly; in a dry run, over
 *   layout 1's values, possible ones.
 * - [notes]: what a migration leaves to others: the indexes, which the application's schema plan makes at its next start,
 *   and the count store, left unseeded.
 */
@ExperimentalStoreLayoutMigration
data class MigrationReport(
    val status: Status?,
    val message: String?,
    val tables: Map<String, Counted>,
    val properties: List<PropertyReport>,
    val observed: List<Observed>,
    val candidates: List<Candidate>,
    val kept: List<Kept>,
    val collisions: List<Collision>,
    val notes: List<String>,
) {
    /** How a migration ended; [word] is its name, as the report and the command line write it. */
    enum class Status(val word: String) {
        /** The graph is in layout 2 already: nothing was done. */
        CURRENT("current"),

        /** The graph holds nothing to migrate: nothing was done. */
        EMPTY("empty"),

        /**
         * The graph can't be migrated as asked, and the message says why: it is of another layout, or changed since its
         * migration began, or the migration is resumed, before every row is written, with other declarations or another
         * `keepInvalid`, or another migration of it began, or finished, meanwhile.
         */
        REFUSED("refused"),

        /** A dry run of a graph a migration has started: run the migration again to resume it. */
        IN_PROGRESS("inProgress"),

        /**
         * Values are no value of their declared kind. Found by the scan, nothing was written: fix the declarations, or keep
         * the values. Found by the writes, the graph was written to during the migration, and nothing of that row's batch
         * was written: correct it, and run the migration again to resume it.
         */
        INVALID("invalid"),

        /** A dry run's report: nothing was written. */
        DRY_RUN("dryRun"),

        /** The rows are rewritten, and a uniqueness constraint's keys collide: fix them, and run the migration again. */
        COLLISIONS("collisions"),

        /** The graph is in layout 2. */
        MIGRATED("migrated"),

        /**
         * A statement failed, and the message is the store's: run the migration again to resume it. Or, once finished, the
         * graph isn't judged of layout 2, and the message says why.
         */
        FAILED("failed"),
    }

    /** The rows of a table read and changed. */
    data class Counted(val rows: Long, val changed: Long)

    /** What [property] of [label]'s values were changed by, counted by change, and its values not of their declared kind. */
    data class PropertyReport(val label: String, val property: String, val changes: Map<String, Long>, val invalid: List<InvalidValue>)

    /** A value of the row [id] of [table] not of its declared [kind]: [value], its JSON text, and why ([message]). */
    data class InvalidValue(val table: String, val id: Long, val kind: String, val value: String, val message: String)

    /** The count of [property] of [label]'s values of each class. */
    data class Observed(val label: String, val property: String, val classes: Map<String, Long>)

    /** A property to declare: [kind] would change its [values]. */
    data class Candidate(val label: String, val property: String, val kind: String, val values: Long)

    /** A value kept as it was, not of its declared [kind], in the row [id] of [table], of [labels]: [value], and why. */
    data class Kept(val table: String, val id: Long, val labels: List<String>, val property: String, val kind: String, val value: String, val message: String)

    /** Keys of the uniqueness constraint over [properties] of [label] that repeat, and a [sample] of them, as its JSON text. */
    data class Collision(val label: String, val properties: List<String>, val sample: String)

    /** How many values are not of their declared kind: one of a node of two labels that declare its property, listed under both, counted once. */
    val invalidCount: Int get() = properties.flatMap { p -> p.invalid.map { Triple(it.table, it.id, p.property) } }.distinct().size

    /** As JSON, as the shared module writes a report: what the command line prints. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "status" to status?.word,
        "message" to message,
        "tables" to tables.mapValues { (_, c) -> linkedMapOf("rows" to c.rows, "changed" to c.changed) },
        "properties" to properties.map {
            linkedMapOf(
                "label" to it.label, "property" to it.property, "changes" to it.changes,
                "invalid" to it.invalid.map { v -> linkedMapOf("table" to v.table, "id" to v.id, "kind" to v.kind, "value" to v.value, "message" to v.message) },
            )
        },
        "observed" to observed.map { linkedMapOf("label" to it.label, "property" to it.property, "classes" to it.classes) },
        "candidates" to candidates.map { linkedMapOf("label" to it.label, "property" to it.property, "kind" to it.kind, "values" to it.values) },
        "kept" to kept.map {
            linkedMapOf("table" to it.table, "id" to it.id, "labels" to it.labels, "property" to it.property, "kind" to it.kind, "value" to it.value, "message" to it.message)
        },
        "collisions" to collisions.map { linkedMapOf("label" to it.label, "properties" to it.properties, "sample" to it.sample) },
        "notes" to notes,
    )

    internal companion object {
        /**
         * The module's [report], as drivine4j gives it: read from the JSON the module writes of it, so each label and
         * property comes as the module names them, in its order.
         */
        @Suppress("UNCHECKED_CAST")
        @JvmSynthetic
        fun of(report: ModuleReport): MigrationReport {
            val map = report.toMap()
            fun list(key: String) = map[key] as List<Map<String, Any?>>
            fun counts(value: Any?) = (value as Map<String, Number>).mapValues { it.value.toLong() }
            return MigrationReport(
                status = report.status?.let(::status),
                message = report.message,
                tables = report.tables.mapValues { (_, c) -> Counted(c.rows, c.changed) },
                properties = list("properties").map { p ->
                    PropertyReport(
                        p["label"] as String, p["property"] as String, counts(p["changes"]),
                        (p["invalid"] as List<Map<String, Any?>>).map {
                            InvalidValue(it["table"] as String, (it["id"] as Number).toLong(), it["kind"] as String, it["value"] as String, it["message"] as String)
                        },
                    )
                },
                observed = list("observed").map { Observed(it["label"] as String, it["property"] as String, counts(it["classes"])) },
                candidates = report.candidates.map { Candidate(it.label, it.property, it.kind, it.values) },
                kept = report.kept.map { Kept(it.table, it.id, it.labels, it.property, it.kind, it.value, it.message) },
                collisions = report.collisions.map { Collision(it.label, it.properties, it.sample) },
                notes = report.notes,
            )
        }

        private fun status(status: ModuleStatus): Status = Status.valueOf(status.name)
    }
}
