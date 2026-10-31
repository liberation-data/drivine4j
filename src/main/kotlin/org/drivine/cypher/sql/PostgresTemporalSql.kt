package org.drivine.cypher.sql

/** Postgres's SQL for temporal values: see [TemporalSql]. Its intervals keep months, days and time apart, as Cypher's durations do. */
class PostgresTemporalSql(shared: (List<String>, (List<String>) -> String) -> String) : TemporalSql(shared) {

    override fun local(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE -> "CAST(CAST(${era(text)} AS date) AS timestamp)"
        Temporal.LOCAL_DATE_TIME -> "CAST(${era(text)} AS timestamp)"
        Temporal.DATE_TIME -> "CAST(${era(unzoned(text))} AS timestamp)"
        Temporal.LOCAL_TIME -> "CAST('1970-01-01 ' || $text AS timestamp)"
        Temporal.TIME -> "CAST('1970-01-01 ' || ${unzoned(text)} AS timestamp)"
        Temporal.DURATION -> error("a duration is no moment")
    }

    override fun utc(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE_TIME -> "(CAST(${era(text)} AS timestamptz) AT TIME ZONE 'UTC')"
        Temporal.TIME -> "(CAST('1970-01-01 ' || $text AS timestamptz) AT TIME ZONE 'UTC')"
        else -> local(text, kind)
    }

    /**
     * Postgres reads no year before 1 in ISO text, but reads one BC after it: so 1 BC, the year `0000`, is `0001 … BC`,
     * and 45 BC, the year `-0044`, is `0045 … BC`.
     */
    override fun era(text: String) =
        "regexp_replace($text, '^(-[0-9]{4}|0000)(.*)$', to_char(1 - CAST(substring($text from '^-?[0-9]{4}') AS int), 'FM0000') || '\\2 BC')"

    /** `to_char` writes a year BC unsigned, and says it is BC. */
    override fun date(timestamp: String, then: String) =
        "regexp_replace(regexp_replace(to_char($timestamp, 'YYYY-MM-DD$then BC'), '^[0-9]{4}(.*) BC$', to_char(EXTRACT(YEAR FROM $timestamp) + 1, 'FM0000') || '\\1'), ' AD$', '')"

    override val now = "(statement_timestamp() AT TIME ZONE 'UTC')"

    override fun matches(text: String, pattern: String) = "($text ~ $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "(regexp_match($text, $pattern))[$group]"

    override fun number(text: String) = "CAST(NULLIF($text, '') AS numeric)"

    override fun sortable(timestamp: String) = "to_char($timestamp, 'YYYY-MM-DD\"T\"HH24:MI:SS.US')"

    override fun micros(timestamp: String) = "to_char($timestamp, 'US')"

    override fun dayOfWeek(timestamp: String) = "CAST(EXTRACT(ISODOW FROM $timestamp) AS numeric)"

    override fun addMonths(timestamp: String, months: String) = "($timestamp + make_interval(months => CAST($months AS int)))"

    override fun addTime(timestamp: String, days: String, seconds: String) =
        "($timestamp + make_interval(days => CAST($days AS int), secs => CAST($seconds AS double precision)))"

    /** `age` counts in calendar months, then days, then time, as Cypher's `duration.between` does. */
    override fun monthsBetween(from: String, to: String) =
        shared(listOf("age($to, $from)")) { (age) -> "(EXTRACT(YEAR FROM $age) * 12 + EXTRACT(MONTH FROM $age))" }

    override fun secondsBetween(from: String, to: String) = "EXTRACT(EPOCH FROM ($to - $from))"

    /**
     * `date_trunc`, but for the units it counts otherwise than Neo4j: its millennium starts in 2001 and its century in
     * 1901, so those and the decade are the year less its years into the span (see [YEARS_OF]). The week-based year is
     * so many weeks before the timestamp's week, its ISO week less one.
     */
    override fun truncate(unit: String, timestamp: String) = when (unit) {
        in setOf("year", "quarter", "month", "week", "day", "hour", "minute", "second", "millisecond") -> "date_trunc('$unit', $timestamp)"
        "microsecond" -> "date_trunc('microseconds', $timestamp)"
        in YEARS_OF -> shared(listOf(timestamp)) { (t) ->
            "(date_trunc('year', $t) - make_interval(years => MOD(CAST(${year(t)} AS int), ${YEARS_OF.getValue(unit)})))"
        }
        "weekYear" -> shared(listOf(timestamp)) { (t) -> "(date_trunc('week', $t) - make_interval(weeks => CAST(EXTRACT(WEEK FROM $t) AS int) - 1))" }
        else -> null
    }
}
