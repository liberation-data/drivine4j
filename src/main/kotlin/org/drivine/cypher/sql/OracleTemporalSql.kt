package org.drivine.cypher.sql

/**
 * Oracle's SQL for temporal values: see [TemporalSql]. Oracle reads ISO 8601 text as a JSON value's moment,
 * and has no time of day apart from a day, so a time of day is read on 1970-01-01. Its intervals keep months
 * apart from days, and a month past the end of a shorter one is its last day by `ADD_MONTHS`.
 */
class OracleTemporalSql : TemporalSql() {

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

    override val now = "CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS TIMESTAMP)"

    override fun matches(text: String, pattern: String) = "REGEXP_LIKE($text, $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "REGEXP_SUBSTR($text, $pattern, 1, 1, NULL, $group)"

    override fun number(text: String) = "TO_NUMBER($text)"

    override fun micros(timestamp: String) = "TO_CHAR($timestamp, 'FF6')"

    override fun dayOfWeek(timestamp: String) = "(TRUNC(CAST($timestamp AS DATE)) - TRUNC(CAST($timestamp AS DATE), 'IW') + 1)"

    /** `ADD_MONTHS` takes a day, so the time of day and the fraction of a second are added back after. */
    override fun addMonths(timestamp: String, months: String) =
        "(CAST(ADD_MONTHS(CAST($timestamp AS DATE), $months) AS TIMESTAMP) + ($timestamp - CAST(CAST($timestamp AS DATE) AS TIMESTAMP)))"

    override fun addTime(timestamp: String, days: String, seconds: String) =
        "($timestamp + NUMTODSINTERVAL($days, 'DAY') + NUMTODSINTERVAL($seconds, 'SECOND'))"

    override fun monthsBetween(from: String, to: String) = "TRUNC(MONTHS_BETWEEN(CAST($to AS DATE), CAST($from AS DATE)))"

    override fun secondsBetween(from: String, to: String): String {
        val interval = "($to - $from)"
        return "(EXTRACT(DAY FROM $interval) * 86400 + EXTRACT(HOUR FROM $interval) * 3600 + EXTRACT(MINUTE FROM $interval) * 60 + EXTRACT(SECOND FROM $interval))"
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

    private fun unzoned(text: String) = replace(text, "'${Temporal.OFFSET}'", "''")
}
