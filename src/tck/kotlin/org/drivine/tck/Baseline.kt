package org.drivine.tck

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** One scenario's run on one store: a line of `build/tck/<store>.jsonl`. */
data class Row(
    val store: String,
    /** The feature file, under the TCK's `features/`: `clauses/match/Match1.feature`. */
    val file: String,
    val feature: String,
    /** The scenario's place in its feature, from 1. */
    val number: Int,
    val name: String,
    /** For a scenario outline, the example's place among the outline's examples, from 0. */
    val example: Int?,
    val outcome: Outcome,
    val kind: String,
    val message: String,
    val query: String,
    val ms: Long,
    /** Where a scenario that expects an error got one: `drivine` (before the database) or `database`. */
    val errorSource: String?,
    /** Passes by the stricter score: an expected error must come from a Cypher implementation, not from SQL. */
    val strict: Boolean,
) {
    /** The scenario's key in a baseline: `3`, or `4:0` for the first example of the outline at 4. */
    val key: String get() = scenarioKey(number, example)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "store" to store, "file" to file, "feature" to feature, "number" to number, "name" to name, "example" to example,
        "outcome" to outcome.code, "kind" to kind, "message" to message, "query" to query, "ms" to ms,
        "errorSource" to errorSource, "strict" to strict,
    )

    companion object {
        fun from(node: JsonNode): Row = Row(
            store = node["store"].asText(),
            file = node["file"].asText(),
            feature = node["feature"].asText(),
            number = node["number"].asInt(),
            name = node["name"].asText(),
            example = node["example"]?.takeUnless { it.isNull }?.asInt(),
            outcome = Outcome.entries.first { it.code == node["outcome"].asText() },
            kind = node["kind"]?.asText().orEmpty(),
            message = node["message"]?.asText().orEmpty(),
            query = node["query"]?.asText().orEmpty(),
            ms = node["ms"]?.asLong() ?: 0,
            errorSource = node["errorSource"]?.takeUnless { it.isNull }?.asText(),
            strict = node["strict"]?.asBoolean() ?: false,
        )
    }
}

fun scenarioKey(number: Int, example: Int?): String = if (example == null) "$number" else "$number:$example"

/**
 * The scenarios that pass on one store, per feature: `src/tck/resources/tck-baseline-<store>.json`. The gate holds a
 * run to it scenario by scenario, so a fix elsewhere can't hide a regression; the ratchet rewrites it.
 */
class Baseline(val store: String, val tck: String, val scenarios: Int, val features: Map<String, Set<String>>) {

    val passing: Int get() = features.values.sumOf { it.size }

    /**
     * Why a run of the TCK version [tck] can't be held to this baseline, or null when it can. A scenario is keyed by its
     * place in its feature, and another version may add or drop scenarios and so move those places: the gate would read
     * the moves as regressions.
     */
    fun refusal(tck: String): String? = if (tck == this.tck) null else
        "The baseline for $store was written on ${this.tck}, and this run is $tck: a scenario's place in its feature may " +
            "have moved between them. Rewrite the baseline with -Ptck.baseline.update=true alone, and read what it drops."

    /** One feature to a line, so a fix's diff names the features it moved. */
    fun write(file: File) {
        val json = ObjectMapper()
        fun q(s: Any) = json.writeValueAsString(s)
        val text = buildString {
            append("{\n")
            append("  \"store\": ${q(store)},\n")
            append("  \"tck\": ${q(tck)},\n")
            append("  \"scenarios\": $scenarios,\n")
            append("  \"passing\": $passing,\n")
            append("  \"features\": {")
            features.entries.sortedBy { it.key }.forEachIndexed { i, (feature, keys) ->
                append(if (i == 0) "\n" else ",\n")
                append("    ${q(feature)}: [${keys.sortedWith(KEY_ORDER).joinToString(", ") { q(it) }}]")
            }
            append("\n  }\n}\n")
        }
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    companion object {
        private val KEY_ORDER = compareBy<String>({ it.substringBefore(':').toInt() }, { it.substringAfter(':', "-1").toInt() })

        fun of(store: String, tck: String, rows: List<Row>): Baseline = Baseline(
            store, tck, rows.size,
            rows.filter { it.outcome == Outcome.PASS }.groupBy { it.file }.mapValues { (_, r) -> r.map { it.key }.toSet() },
        )

        fun read(file: File): Baseline? {
            if (!file.exists()) return null
            val node = ObjectMapper().readTree(file)
            val features = node["features"].properties().associate { (feature, keys) -> feature to keys.map { it.asText() }.toSet() }
            return Baseline(node["store"].asText(), node["tck"].asText(), node["scenarios"].asInt(), features)
        }
    }
}

/** What a run came to against its store's baseline. */
class Comparison(
    /** Scenarios the baseline has passing that ran and failed. */
    val regressions: List<Row>,
    /** Scenarios the baseline has passing that the TCK no longer has, as `feature#key`. */
    val missing: List<String>,
    /** Scenarios that passed and that the baseline doesn't have passing. */
    val gains: List<Row>,
) {
    val failed: Boolean get() = regressions.isNotEmpty() || missing.isNotEmpty()

    /** Each regression with its feature, scenario, query and the first line of its failure. */
    fun describe(): String = buildString {
        for (r in regressions) {
            val example = if (r.example == null) "" else ", example ${r.example}"
            append("REGRESSED ${r.file} #${r.number}$example: ${r.name}\n")
            append("  query:   ${r.query.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(400)}\n")
            append("  failure: ${r.outcome.label} (${r.kind}): ${r.message.lineSequence().firstOrNull().orEmpty().take(400)}\n")
        }
        for (m in missing) append("MISSING   $m passes in the baseline and is no longer in the TCK: rewrite the baseline with -Ptck.baseline.update=true\n")
    }

    companion object {
        /**
         * Compares [rows], a run, with [baseline], scenario by scenario. [tckKeys] holds every scenario the TCK has, as
         * `feature#key`; a scenario the run left out by a filter is neither a regression nor a gain.
         */
        fun of(baseline: Baseline, rows: List<Row>, tckKeys: Set<String>): Comparison {
            val passing = baseline.features
            return Comparison(
                regressions = rows.filter { it.outcome != Outcome.PASS && passing[it.file]?.contains(it.key) == true },
                missing = passing.flatMap { (feature, keys) -> keys.map { "$feature#$it" } }.filter { it !in tckKeys }.sorted(),
                gains = rows.filter { it.outcome == Outcome.PASS && passing[it.file]?.contains(it.key) != true },
            )
        }
    }
}
