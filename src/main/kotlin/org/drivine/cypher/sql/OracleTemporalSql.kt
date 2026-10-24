package org.drivine.cypher.sql

/**
 * Oracle's SQL for temporal values: see [TemporalSql]. Oracle reads ISO 8601 text as a JSON value's moment,
 * and has no time of day apart from a day, so a time of day is read on 1970-01-01. Its intervals keep months
 * apart from days. `ADD_MONTHS` and `MONTHS_BETWEEN` take the last day of a month to the last day of another,
 * where Cypher keeps the day of the month, so months are added and counted from the first of the month.
 */
class OracleTemporalSql(
    shared: (List<String>, (List<String>) -> String) -> String,
    private val procedural: () -> Boolean,
) : TemporalSql(shared) {

    override fun local(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE -> "TO_TIMESTAMP($text, 'YYYY-MM-DD')"
        Temporal.LOCAL_DATE_TIME -> iso(text, "TIMESTAMP")
        Temporal.DATE_TIME -> iso(unzoned(text), "TIMESTAMP")
        Temporal.LOCAL_TIME -> iso("'1970-01-01T' || $text", "TIMESTAMP")
        Temporal.TIME -> iso("'1970-01-01T' || ${unzoned(text)}", "TIMESTAMP")
        Temporal.DURATION -> error("a duration is no moment")
    }

    override fun utc(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE_TIME -> "CAST(SYS_EXTRACT_UTC(${iso(text, "TIMESTAMP WITH TIME ZONE")}) AS TIMESTAMP)"
        Temporal.TIME -> "CAST(SYS_EXTRACT_UTC(${iso("'1970-01-01T' || $text", "TIMESTAMP WITH TIME ZONE")}) AS TIMESTAMP)"
        else -> local(text, kind)
    }

    /**
     * The clock as a query starts. A block reads it once, into its variable [NOW], where each statement of the block
     * would read it as that statement starts, and a statement's clock is one in Cypher.
     */
    override val now get() = if (procedural()) NOW else CLOCK

    override fun matches(text: String, pattern: String) = "REGEXP_LIKE($text, $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "REGEXP_SUBSTR($text, $pattern, 1, 1, NULL, $group)"

    override fun number(text: String) = "TO_NUMBER($text)"

    override fun micros(timestamp: String) = "TO_CHAR($timestamp, 'FF6')"

    override fun dayOfWeek(timestamp: String) =
        shared(listOf(timestamp)) { (t) -> "(TRUNC(CAST($t AS DATE)) - TRUNC(CAST($t AS DATE), 'IW') + 1)" }

    /**
     * The first of the month [months] later, by `ADD_MONTHS`, which takes a day; then the day of the month, or the
     * month's last where it has fewer, and the time of day with the fraction of a second.
     */
    override fun addMonths(timestamp: String, months: String) = shared(listOf(timestamp, months)) { (t, m) ->
        shared(listOf("ADD_MONTHS(TRUNC(CAST($t AS DATE), 'MM'), $m)")) { (first) ->
            "(CAST($first AS TIMESTAMP) + NUMTODSINTERVAL(LEAST(EXTRACT(DAY FROM $t), EXTRACT(DAY FROM LAST_DAY($first))) - 1, 'DAY') + " +
                "($t - CAST(TRUNC(CAST($t AS DATE)) AS TIMESTAMP)))"
        }
    }

    override fun addTime(timestamp: String, days: String, seconds: String) =
        "($timestamp + NUMTODSINTERVAL($days, 'DAY') + NUMTODSINTERVAL($seconds, 'SECOND'))"

    /** The months between the calendar months, less one where [to]'s day and time of the month are short of [from]'s. */
    override fun monthsBetween(from: String, to: String) = shared(listOf(from, to)) { (f, t) ->
        val (since, until) = listOf(f, t).map { "TO_CHAR($it, 'DDHH24MISSFF6')" }
        shared(listOf("((EXTRACT(YEAR FROM $t) - EXTRACT(YEAR FROM $f)) * 12 + EXTRACT(MONTH FROM $t) - EXTRACT(MONTH FROM $f))")) { (m) ->
            "($m - CASE WHEN $m > 0 AND $until < $since THEN 1 WHEN $m < 0 AND $until > $since THEN -1 ELSE 0 END)"
        }
    }

    override fun secondsBetween(from: String, to: String) = shared(listOf(from, to)) { (f, t) ->
        val interval = "($t - $f)"
        "(EXTRACT(DAY FROM $interval) * 86400 + EXTRACT(HOUR FROM $interval) * 3600 + EXTRACT(MINUTE FROM $interval) * 60 + EXTRACT(SECOND FROM $interval))"
    }

    override fun truncate(unit: String, timestamp: String): String? {
        val format = mapOf("year" to "YYYY", "quarter" to "Q", "month" to "MM", "week" to "IW", "day" to "DD", "hour" to "HH24", "minute" to "MI")[unit]
        return when {
            format != null -> "CAST(TRUNC(CAST($timestamp AS DATE), '$format') AS TIMESTAMP)"
            unit == "second" -> "CAST(CAST($timestamp AS DATE) AS TIMESTAMP)"
            unit == "millisecond" -> "TO_TIMESTAMP(TO_CHAR($timestamp, 'YYYY-MM-DD HH24:MI:SS.FF3'), 'YYYY-MM-DD HH24:MI:SS.FF3')"
            else -> null
        }
    }

    /** The moment the ISO 8601 text [text] writes, as Oracle reads it out of JSON, as [type]. */
    private fun iso(text: String, type: String) = "JSON_VALUE(JSON_SCALAR($text), '\$' RETURNING $type)"

    companion object {
        /** The clock as the query that reads it starts, in UTC. */
        const val CLOCK = "CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS TIMESTAMP)"

        /** The variable a block reads [CLOCK] into as it starts. */
        const val NOW = "cy_now"
    }
}
