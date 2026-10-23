package org.drivine.cypher.sql

/**
 * openCypher's temporal values on a SQL store, worked out of a store's [TemporalSql]. A value is its ISO 8601
 * text, as a store keeps a moment: `2024-03-01`, `10:30:00`, `10:30:00+02:00`, `2024-03-01T10:30:00`,
 * `2024-03-01T10:30:00.500Z`, `P1Y2M3DT4H`. A zoned value keeps the offset it was written with, its fields
 * read on its wall clock and compared as the moment it is. A duration is months, days and seconds, as
 * Cypher's is, and as a Postgres interval is.
 *
 * Every argument and result is SQL: a text, a number, or with [shared] a value written once where it is read
 * more than once.
 */
class Temporals(private val sql: TemporalSql, private val shared: (List<String>, (List<String>) -> String) -> String) {

    /** A duration's parts, SQL numbers each: whole months, whole days, and seconds with their fraction. */
    class Parts(val months: String, val days: String, val seconds: String)

    // ----- Kinds -----

    /** Whether the text [text] is of [kind]. */
    fun isKind(text: String, kind: Temporal): String = sql.matches(text, literal(kind.pattern))

    /**
     * [body] of the kind among [kinds] that the text [text] is, as its shape tells, and of the text, written once:
     * null where it is none.
     */
    fun byKind(text: String, kinds: List<Temporal> = Temporal.instants, body: (Temporal, String) -> String): String =
        shared(listOf(text)) { (t) -> "CASE ${kinds.joinToString(" ") { "WHEN ${isKind(t, it)} THEN ${body(it, t)}" }} END" }

    // ----- Moments and times of day -----

    /** The text of [kind] the wall clock [timestamp] reads, with [offset], a text, where the kind is zoned. */
    fun format(timestamp: String, kind: Temporal, offset: String? = null): String = shared(listOf(timestamp)) { (t) ->
        val time = "to_char($t, 'HH24:MI:SS') || ${fraction(t)}"
        when (kind) {
            Temporal.DATE -> "to_char($t, 'YYYY-MM-DD')"
            Temporal.LOCAL_DATE_TIME -> "to_char($t, 'YYYY-MM-DD\"T\"') || $time"
            Temporal.DATE_TIME -> "to_char($t, 'YYYY-MM-DD\"T\"') || $time || ${offset ?: "'Z'"}"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time || ${offset ?: "'Z'"}"
            Temporal.DURATION -> error("a duration is no moment")
        }
    }

    /** A second's fraction as Java writes one: none for a whole second, else three digits or six. */
    private fun fraction(timestamp: String): String = shared(listOf(sql.micros(timestamp))) { (f) ->
        "CASE WHEN $f = '000000' THEN '' WHEN SUBSTR($f, 4, 3) = '000' THEN '.' || SUBSTR($f, 1, 3) ELSE '.' || $f END"
    }

    /** The offset the zoned text [text] ends with. */
    fun offset(text: String): String = sql.group(text, literal(Temporal.OFFSET), 1)

    /** The text of [to] the text [text] of [from] is: a moment's date, a date's midnight, a local value's in UTC. */
    fun convert(text: String, from: Temporal, to: Temporal): String = shared(listOf(text)) { (t) ->
        val offset = if (to.zoned) (if (from.zoned) offset(t) else "'Z'") else null
        format(sql.local(t, from), to, offset)
    }

    /** The text of [to] the text [text] is, whatever kind its shape tells: null where it is none. */
    fun convert(text: String, to: Temporal): String = byKind(text) { from, t -> convert(t, from, to) }

    /** The text of [kind] the statement runs at, in UTC. */
    fun now(kind: Temporal): String = format(sql.now, kind)

    /**
     * The text of [kind] of the fields [fields], SQL numbers each by Cypher's names (`year`, `month`, `day`,
     * `hour`, `minute`, `second`, `millisecond`, `microsecond`, `nanosecond`), and [timezone], a text.
     */
    fun of(fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        fun field(name: String, default: String, digits: Int) = "to_char(${fields[name] ?: default}, 'FM${"0".repeat(digits)}')"
        val micros = "TRUNC(${fields["millisecond"] ?: "0"} * 1000 + ${fields["microsecond"] ?: "0"} + ${fields["nanosecond"] ?: "0"} / 1000)"
        val date = "${field("year", "1970", 4)} || '-' || ${field("month", "1", 2)} || '-' || ${field("day", "1", 2)}"
        val time = "${field("hour", "0", 2)} || ':' || ${field("minute", "0", 2)} || ':' || ${field("second", "0", 2)} || '.' || to_char($micros, 'FM000000')"
        val zone = timezone ?: "'Z'"
        val text = when (kind) {
            Temporal.DATE -> date
            Temporal.LOCAL_DATE_TIME -> "$date || 'T' || $time"
            Temporal.DATE_TIME -> "$date || 'T' || $time || $zone"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time || $zone"
            Temporal.DURATION -> error("a duration is made of its own fields")
        }
        return convert(text, kind, kind)
    }

    /**
     * A field of the text [text] of [kind], by Cypher's name for it: a number, or for `timezone` and `offset` a
     * text; null for a name the kind has no field of. A zoned value's fields are those of its wall clock.
     */
    fun field(text: String, kind: Temporal, name: String): Field? {
        if (kind == Temporal.DURATION) {
            if (durationField(parts("NULL"), name) == null) return null
            return Field(shared(listOf(text)) { (t) -> durationField(parts(t), name)!! }, text = false)
        }
        if (kind.zoned && name in setOf("timezone", "offset", "offsetMinutes", "offsetSeconds")) return zoneField(text, name)
        val epoch = "TIMESTAMP '1970-01-01 00:00:00'"
        when (name) {
            "epochSeconds" -> return Field("TRUNC(${sql.secondsBetween(epoch, sql.utc(text, kind))})", text = false)
            "epochMillis" -> return Field("TRUNC(${sql.secondsBetween(epoch, sql.utc(text, kind))} * 1000)", text = false)
        }
        if (name !in INSTANT_FIELDS) return null
        val number = shared(listOf(sql.local(text, kind))) { (t) ->
            val second = "EXTRACT(SECOND FROM $t)"
            when (name) {
                "year" -> "EXTRACT(YEAR FROM $t)"
                "quarter" -> "TRUNC((EXTRACT(MONTH FROM $t) + 2) / 3)"
                "month" -> "EXTRACT(MONTH FROM $t)"
                "week" -> sql.number("to_char($t, 'IW')")
                "weekYear" -> sql.number("to_char($t, 'IYYY')")
                "day" -> "EXTRACT(DAY FROM $t)"
                "ordinalDay" -> sql.number("to_char($t, 'DDD')")
                "dayOfWeek", "weekDay" -> sql.dayOfWeek(t)
                "hour" -> "EXTRACT(HOUR FROM $t)"
                "minute" -> "EXTRACT(MINUTE FROM $t)"
                "second" -> "TRUNC($second)"
                "millisecond" -> "(TRUNC($second * 1000) - TRUNC($second) * 1000)"
                "microsecond" -> "(TRUNC($second * 1000000) - TRUNC($second) * 1000000)"
                else -> "((TRUNC($second * 1000000) - TRUNC($second) * 1000000) * 1000)"
            }
        }
        return Field(number, text = false)
    }

    /** A field of a value: its SQL, and whether it is a text, where it is not a number. */
    class Field(val sql: String, val text: Boolean)

    private fun zoneField(text: String, name: String): Field = shared(listOf(offset(text))) { (o) ->
        val sign = "CASE WHEN SUBSTR($o, 1, 1) = '-' THEN -1 ELSE 1 END"
        val minutes = "CASE WHEN $o = 'Z' THEN 0 ELSE $sign * (${sql.number("SUBSTR($o, 2, 2)")} * 60 + ${sql.number("SUBSTR(REPLACE($o, ':', ''), 4, 2)")}) END"
        when (name) {
            "timezone" -> o
            "offset" -> "CASE WHEN $o = 'Z' THEN '+00:00' ELSE $o END"
            "offsetMinutes" -> minutes
            else -> "($minutes) * 60"
        }
    }.let { Field(it, text = name == "timezone" || name == "offset") }

    /** The names of the fields a temporal value may have, of a moment's or a duration's. */
    val fieldNames = setOf(
        "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfWeek", "weekDay", "hour", "minute", "second",
        "millisecond", "microsecond", "nanosecond", "epochSeconds", "epochMillis", "timezone", "offset", "offsetMinutes", "offsetSeconds",
        "years", "quarters", "months", "weeks", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds",
        "quartersOfYear", "monthsOfQuarter", "monthsOfYear", "daysOfWeek", "minutesOfHour", "secondsOfMinute", "millisecondsOfSecond",
        "microsecondsOfSecond", "nanosecondsOfSecond",
    )

    /** The text of [kind] the duration text [duration] after the text [text] of it, or with [sign] -1 before: a zoned value keeps its offset. */
    fun add(text: String, kind: Temporal, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        val s = if (sign < 0) "-" else ""
        val parts = parts(d)
        val moved = sql.addTime(sql.addMonths(sql.local(t, kind), "$s(${parts.months})"), "$s(${parts.days})", "$s(${parts.seconds})")
        format(moved, kind, if (kind.zoned) offset(t) else null)
    }

    /**
     * [add] for a text of whatever kind its shape tells: read as a wall clock once, whatever its kind, the duration
     * added once, and written as the kind it was.
     */
    fun addToAny(text: String, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        val s = if (sign < 0) "-" else ""
        val parts = parts(d)
        val clock = "CASE ${Temporal.instants.joinToString(" ") { "WHEN ${isKind(t, it)} THEN ${sql.local(t, it)}" }} END"
        shared(listOf(sql.addTime(sql.addMonths(clock, "$s(${parts.months})"), "$s(${parts.days})", "$s(${parts.seconds})"))) { (moved) ->
            "CASE ${Temporal.instants.joinToString(" ") { "WHEN ${isKind(t, it)} THEN ${format(moved, it, if (it.zoned) offset(t) else null)}" }} END"
        }
    }

    /** The text of [to] the text [text] of [from] is, cut short to [unit]: null where the store truncates to no such unit. */
    fun truncate(unit: String, text: String, from: Temporal, to: Temporal): String? {
        if (sql.truncate(unit, "NULL") == null) return null
        return shared(listOf(text)) { (t) -> format(sql.truncate(unit, sql.local(t, from))!!, to, if (to.zoned) (if (from.zoned) offset(t) else "'Z'") else null) }
    }

    /** Whether the text [a] stands to the text [b], both of [kind], as [operator] says: null where either is no such text. */
    fun compare(a: String, b: String, kind: Temporal, operator: String): String = shared(listOf(a, b)) { (x, y) ->
        val test = if (kind == Temporal.DURATION) {
            // A duration is equal to another of the same parts, and is in no order.
            if (operator != "=" && operator != "<>") "NULL" else {
                val (p, q) = parts(x) to parts(y)
                val equal = "(${p.months} = ${q.months} AND ${p.days} = ${q.days} AND ${p.seconds} = ${q.seconds})"
                if (operator == "=") equal else "NOT $equal"
            }
        } else if (kind.zoned && (operator == "=" || operator == "<>")) {
            // Two zoned values are equal where they are the same moment at the same offset, as Neo4j has them.
            val equal = "(${sql.utc(x, kind)} = ${sql.utc(y, kind)} AND ${offset(x)} = ${offset(y)})"
            if (operator == "=") equal else "NOT $equal"
        } else {
            "${sql.utc(x, kind)} $operator ${sql.utc(y, kind)}"
        }
        "CASE WHEN ${isKind(x, kind)} AND ${isKind(y, kind)} THEN $test END"
    }

    // ----- Durations -----

    /** The parts of the duration text [text], which each read it more than once: [text] is a name, written once. */
    private fun parts(text: String): Parts {
        fun group(name: String) = Temporal.DURATION_PARTS.getValue(name).let { (pattern, group) -> sql.group(text, literal(pattern), group) }
        fun part(name: String) = "COALESCE(${sql.number(group(name))}, 0)"
        val sign = "CASE WHEN ${group("sign")} = '-' THEN -1 ELSE 1 END"
        return Parts(
            "($sign * (${part("years")} * 12 + ${part("months")}))",
            "($sign * (${part("weeks")} * 7 + ${part("days")}))",
            "($sign * (${part("hours")} * 3600 + ${part("minutes")} * 60 + ${part("seconds")}))",
        )
    }

    /** The duration text of the fields [fields], SQL numbers each by Cypher's names (`years`, `months`, `weeks`, `days`, `hours`…). */
    fun duration(fields: Map<String, String>): String {
        fun f(name: String) = fields[name] ?: "0"
        return text(Parts(
            "(${f("years")} * 12 + ${f("quarters")} * 3 + ${f("months")})",
            "(${f("weeks")} * 7 + ${f("days")})",
            "(${f("hours")} * 3600 + ${f("minutes")} * 60 + ${f("seconds")} + ${f("milliseconds")} / 1000 + ${f("microseconds")} / 1000000 + ${f("nanoseconds")} / 1000000000)",
        ))
    }

    /**
     * The duration text of the parts [parts], as Neo4j writes one: whole years and months, days, then hours,
     * minutes and seconds, each signed, and `PT0S` for none. A fraction of a month or a day goes to the parts
     * below it, as Cypher's does: a month as 30.436875 days.
     */
    private fun text(parts: Parts): String = shared(listOf(parts.months, parts.days, parts.seconds)) { (m0, d0, s0) ->
        val m = "TRUNC($m0)"
        val d = "TRUNC($d0 + ($m0 - TRUNC($m0)) * 30.436875)"
        val s = "($s0 + ($d0 + ($m0 - TRUNC($m0)) * 30.436875 - $d) * 86400)"
        shared(listOf(m, d, s)) { (months, days, seconds) ->
            val years = "TRUNC($months / 12)"
            val hours = "TRUNC($seconds / 3600)"
            val minutes = "TRUNC(($seconds - $hours * 3600) / 60)"
            val secs = "($seconds - $hours * 3600 - $minutes * 60)"
            fun unit(value: String, letter: String) = "CASE WHEN $value <> 0 THEN ${number(value)} || '$letter' ELSE '' END"
            "CASE WHEN $months = 0 AND $days = 0 AND $seconds = 0 THEN 'PT0S' ELSE 'P' || ${unit(years, "Y")} || ${unit("($months - $years * 12)", "M")} || " +
                "${unit(days, "D")} || CASE WHEN $seconds <> 0 THEN 'T' || ${unit(hours, "H")} || ${unit(minutes, "M")} || ${unit(secs, "S")} ELSE '' END END"
        }
    }

    /** A field of a duration of the parts [parts], by Cypher's name for it: null for a name a duration has no field of. */
    private fun durationField(parts: Parts, name: String): String? {
        val (m, d, s) = Triple(parts.months, parts.days, parts.seconds)
        return when (name) {
            "years" -> "TRUNC($m / 12)"
            "quarters" -> "TRUNC($m / 3)"
            "months" -> m
            "weeks" -> "TRUNC($d / 7)"
            "days" -> d
            "hours" -> "TRUNC($s / 3600)"
            "minutes" -> "TRUNC($s / 60)"
            "seconds" -> "TRUNC($s)"
            "milliseconds" -> "TRUNC($s * 1000)"
            "microseconds" -> "TRUNC($s * 1000000)"
            "nanoseconds" -> "TRUNC($s * 1000000000)"
            "quartersOfYear" -> "(TRUNC($m / 3) - TRUNC($m / 12) * 4)"
            "monthsOfQuarter" -> "($m - TRUNC($m / 3) * 3)"
            "monthsOfYear" -> "($m - TRUNC($m / 12) * 12)"
            "daysOfWeek" -> "($d - TRUNC($d / 7) * 7)"
            "minutesOfHour" -> "(TRUNC($s / 60) - TRUNC($s / 3600) * 60)"
            "secondsOfMinute" -> "(TRUNC($s) - TRUNC($s / 60) * 60)"
            "millisecondsOfSecond" -> "(TRUNC($s * 1000) - TRUNC($s) * 1000)"
            "microsecondsOfSecond" -> "(TRUNC($s * 1000000) - TRUNC($s) * 1000000)"
            "nanosecondsOfSecond" -> "(TRUNC($s * 1000000000) - TRUNC($s) * 1000000000)"
            else -> null
        }
    }

    /** The duration text of the sum of the duration texts [a] and [b], or with [sign] -1 their difference. */
    fun sum(a: String, b: String, sign: Int): String = shared(listOf(a, b)) { (x, y) ->
        val (p, q) = parts(x) to parts(y)
        val op = if (sign < 0) "-" else "+"
        text(Parts("(${p.months} $op ${q.months})", "(${p.days} $op ${q.days})", "(${p.seconds} $op ${q.seconds})"))
    }

    /** The duration text of the duration text [duration] times [factor], a SQL number. */
    fun times(duration: String, factor: String): String = shared(listOf(duration, factor)) { (d, f) ->
        val p = parts(d)
        text(Parts("(${p.months} * $f)", "(${p.days} * $f)", "(${p.seconds} * $f)"))
    }

    /** The canonical duration text of the duration text [text]: null where it is none. */
    fun duration(text: String): String = shared(listOf(text)) { (t) -> "CASE WHEN ${isKind(t, Temporal.DURATION)} THEN ${text(parts(t))} END" }

    /**
     * `duration.between`: the duration from the text [from] to the text [to], both of [kind], in whole months as a
     * calendar counts them, then days, then seconds; with [unit] `months`, `days` or `seconds`, in that alone.
     */
    fun between(from: String, to: String, kind: Temporal, unit: String? = null): String = shared(listOf(sql.utc(from, kind), sql.utc(to, kind))) { (a, b) ->
        val months = if (kind == Temporal.TIME || kind == Temporal.LOCAL_TIME) "0" else sql.monthsBetween(a, b)
        when (unit) {
            "months" -> text(Parts(months, "0", "0"))
            "days" -> text(Parts("0", "TRUNC(${sql.secondsBetween(a, b)} / 86400)", "0"))
            "seconds" -> text(Parts("0", "0", sql.secondsBetween(a, b)))
            else -> shared(listOf(months)) { (m) ->
                shared(listOf(sql.secondsBetween(sql.addMonths(a, m), b))) { (rest) -> text(Parts(m, "TRUNC($rest / 86400)", "($rest - TRUNC($rest / 86400) * 86400)")) }
            }
        }
    }

    /** A number as Neo4j writes one in a duration: no zeros after the point, nor the point where there is no fraction. */
    private fun number(value: String) = "RTRIM(to_char($value, 'FM9999999999990.999999999'), '.')"

    private fun literal(text: String) = "'${text.replace("'", "''")}'"

    companion object {
        /**
         * The canonical text of [kind] of the text [text], worked out as the store's SQL would work it out, for a
         * value the statement writes: null where the text is of no temporal kind.
         */
        fun canonical(text: String, kind: Temporal): String? {
            if (kind == Temporal.DURATION) return if (Regex(Temporal.DURATION.pattern).matches(text)) canonicalDuration(text) else null
            val from = Temporal.instants.firstOrNull { Regex(it.pattern).matches(text) } ?: return null
            val offset = if (from.zoned) Regex(Temporal.OFFSET).find(text)!!.value else null
            val local = if (offset != null) text.removeSuffix(offset) else text
            val clock = when (from) {
                Temporal.DATE -> java.time.LocalDate.parse(local).atStartOfDay()
                Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> java.time.LocalDateTime.parse(local)
                else -> java.time.LocalTime.parse(local).atDate(java.time.LocalDate.of(1970, 1, 1))
            }
            val micros = clock.nano / 1000
            val fraction = when {
                micros == 0 -> ""
                micros % 1000 == 0 -> ".%03d".format(micros / 1000)
                else -> ".%06d".format(micros)
            }
            val time = "%02d:%02d:%02d".format(clock.hour, clock.minute, clock.second) + fraction
            val zone = if (kind.zoned) offset ?: "Z" else ""
            return when (kind) {
                Temporal.DATE -> clock.toLocalDate().toString()
                Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> "${clock.toLocalDate()}T$time$zone"
                else -> "$time$zone"
            }
        }

        /** The canonical text of the duration text [text], as [text] of its parts writes it. */
        private fun canonicalDuration(text: String): String {
            val groups = Regex(Temporal.DURATION.pattern).matchEntire(text)!!.groupValues
            fun part(group: Int) = groups[group].takeIf { it.isNotEmpty() }?.toBigDecimal() ?: java.math.BigDecimal.ZERO
            val sign = if (groups[1] == "-") java.math.BigDecimal.ONE.negate() else java.math.BigDecimal.ONE
            val m0 = sign * (part(3) * 12.toBigDecimal() + part(5))
            val d0 = sign * (part(7) * 7.toBigDecimal() + part(9))
            val s0 = sign * (part(12) * 3600.toBigDecimal() + part(14) * 60.toBigDecimal() + part(16))
            val monthDays = "30.436875".toBigDecimal()
            val months = m0.setScale(0, java.math.RoundingMode.DOWN)
            val daysExact = d0 + (m0 - months) * monthDays
            val days = daysExact.setScale(0, java.math.RoundingMode.DOWN)
            val seconds = s0 + (daysExact - days) * 86400.toBigDecimal()
            if (months.signum() == 0 && days.signum() == 0 && seconds.signum() == 0) return "PT0S"
            val years = months.divide(12.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
            val hours = seconds.divide(3600.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
            val minutes = (seconds - hours * 3600.toBigDecimal()).divide(60.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
            val secs = seconds - hours * 3600.toBigDecimal() - minutes * 60.toBigDecimal()
            fun unit(value: java.math.BigDecimal, letter: String) = if (value.signum() != 0) value.stripTrailingZeros().toPlainString() + letter else ""
            val time = if (seconds.signum() != 0) "T" + unit(hours, "H") + unit(minutes, "M") + unit(secs, "S") else ""
            return "P" + unit(years, "Y") + unit(months - years * 12.toBigDecimal(), "M") + unit(days, "D") + time
        }

        /** The fields of a moment or a time of day read off its wall clock. */
        val INSTANT_FIELDS = setOf(
            "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfWeek", "weekDay",
            "hour", "minute", "second", "millisecond", "microsecond", "nanosecond",
        )
    }
}
