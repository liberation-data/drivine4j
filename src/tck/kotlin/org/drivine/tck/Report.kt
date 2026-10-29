package org.drivine.tck

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.Locale

/**
 * The report of the latest run on each store: `report.md` and `report.json`, beside the runs' `<store>.jsonl` in [dir].
 * Totals per store, by both scores and by outcome, then the pass rate of each area of the TCK.
 */
object Report {
    private val STORES = listOf("neo4j", "postgres", "oracle", "sqlite")

    fun write(dir: File) {
        val json = ObjectMapper()
        val runs = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .associate { f -> f.name.removeSuffix(".jsonl") to f.readLines().filter { it.isNotBlank() }.map { Row.from(json.readTree(it)) } }
            .filterValues { it.isNotEmpty() }
            .toSortedMap(compareBy({ STORES.indexOf(it).let { i -> if (i < 0) STORES.size else i } }, { it }))
        if (runs.isEmpty()) return
        val totals = runs.mapValues { (_, rows) -> totals(rows) }
        val areas = runs.values.flatten().map { area(it.file) }.distinct().sorted()
        val byArea = runs.mapValues { (_, rows) -> rows.groupBy { area(it.file) }.mapValues { (_, r) -> totals(r) } }

        json.writerWithDefaultPrettyPrinter().writeValue(
            File(dir, "report.json"),
            linkedMapOf(
                "stores" to totals,
                "areas" to areas.associateWith { a -> byArea.mapValues { (_, m) -> m[a] }.filterValues { it != null } },
            ),
        )

        val md = buildString {
            append("# openCypher TCK on Drivine\n\n")
            append("The latest run on each store. Lenient: any error passes where the TCK expects one. ")
            append("Strict: where Drivine compiles to SQL, an expected error passes only when Drivine raised it, not the database.\n\n")
            append("| store | scenarios | lenient pass | strict pass | refused on purpose | wrong result | wrong or missing error | crash or stall | harness limitation | expected errors from Drivine / from the database |\n")
            append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
            for ((store, t) in totals) {
                append("| $store | ${t["scenarios"]} | ${rate(t, "pass")} | ${rate(t, "strict")} | ${t[Outcome.REFUSED.code]} | ${t[Outcome.WRONG_RESULT.code]} | ")
                append("${t[Outcome.WRONG_ERROR.code]} | ${t[Outcome.CRASH.code]} | ${t[Outcome.HARNESS.code]} | ${t["errorFromDrivine"]} / ${t["errorFromDatabase"]} |\n")
            }
            append("\n## Lenient pass rate by area\n\n")
            append("| area | scenarios | ${runs.keys.joinToString(" | ")} |\n")
            append("|---|---:|${runs.keys.joinToString("") { "---:|" }}\n")
            for (a in areas) {
                val n = byArea.values.firstNotNullOfOrNull { it[a] }?.get("scenarios") ?: 0
                append("| $a | $n | ${runs.keys.joinToString(" | ") { s -> byArea.getValue(s)[a]?.let { rate(it, "pass") } ?: "" }} |\n")
            }
        }
        File(dir, "report.md").writeText(md)
    }

    /** The area a feature file is in: its first two directories, `clauses/match`. */
    fun area(file: String): String = file.split('/').let { if (it.size > 2) "${it[0]}/${it[1]}" else it.first() }

    fun totals(rows: List<Row>): Map<String, Int> = linkedMapOf(
        "scenarios" to rows.size,
        "pass" to rows.count { it.outcome == Outcome.PASS },
        "strict" to rows.count { it.strict },
        "errorFromDrivine" to rows.count { it.outcome == Outcome.PASS && it.errorSource == "drivine" },
        "errorFromDatabase" to rows.count { it.outcome == Outcome.PASS && it.errorSource == "database" },
    ) + Outcome.entries.filter { it != Outcome.PASS }.associate { o -> o.code to rows.count { it.outcome == o } }

    fun rate(totals: Map<String, Int>, key: String): String {
        val n = totals.getValue("scenarios")
        val k = totals.getValue(key)
        return if (n == 0) "" else String.format(Locale.ROOT, "%d (%.1f%%)", k, 100.0 * k / n)
    }
}
