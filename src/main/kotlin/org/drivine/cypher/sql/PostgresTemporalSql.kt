package org.drivine.cypher.sql

/** Postgres's SQL for temporal values: see [TemporalSql]. Its intervals keep months, days and time apart, as Cypher's durations do. */
class PostgresTemporalSql : TemporalSql() {

    override fun local(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE -> "CAST(CAST($text AS date) AS timestamp)"
        Temporal.LOCAL_DATE_TIME -> "CAST($text AS timestamp)"
        Temporal.DATE_TIME -> "CAST(${unzoned(text)} AS timestamp)"
        Temporal.LOCAL_TIME -> "CAST('1970-01-01 ' || $text AS timestamp)"
        Temporal.TIME -> "CAST('1970-01-01 ' || ${unzoned(text)} AS timestamp)"
        Temporal.DURATION -> error("a duration is no moment")
    }

    override fun utc(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE_TIME -> "(CAST($text AS timestamptz) AT TIME ZONE 'UTC')"
        Temporal.TIME -> "(CAST('1970-01-01 ' || $text AS timestamptz) AT TIME ZONE 'UTC')"
        else -> local(text, kind)
    }

    override val now = "(statement_timestamp() AT TIME ZONE 'UTC')"

    override fun matches(text: String, pattern: String) = "($text ~ $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "(regexp_match($text, $pattern))[$group]"

    override fun number(text: String) = "CAST(NULLIF($text, '') AS numeric)"

    override fun micros(timestamp: String) = "to_char($timestamp, 'US')"

    override fun dayOfWeek(timestamp: String) = "CAST(EXTRACT(ISODOW FROM $timestamp) AS numeric)"

    override fun addMonths(timestamp: String, months: String) = "($timestamp + make_interval(months => CAST($months AS int)))"

    override fun addTime(timestamp: String, days: String, seconds: String) =
        "($timestamp + make_interval(days => CAST($days AS int), secs => CAST($seconds AS double precision)))"

    /** `age` counts in calendar months, then days, then time, as Cypher's `duration.between` does. */
    override fun monthsBetween(from: String, to: String) =
        "(EXTRACT(YEAR FROM age($to, $from)) * 12 + EXTRACT(MONTH FROM age($to, $from)))"

    override fun secondsBetween(from: String, to: String) = "EXTRACT(EPOCH FROM ($to - $from))"

    override fun truncate(unit: String, timestamp: String) =
        if (unit in setOf("year", "quarter", "month", "week", "day", "hour", "minute", "second", "millisecond")) "date_trunc('$unit', $timestamp)" else null

    private fun unzoned(text: String) = replace(text, "'${Temporal.OFFSET}'", "''")
}
