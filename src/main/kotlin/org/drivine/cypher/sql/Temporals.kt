package org.drivine.cypher.sql

/**
 * openCypher's temporal values on a SQL store, worked out of a store's [TemporalSql]. A value is its ISO 8601
 * text, as a store keeps a moment: `2024-03-01`, `10:30:00`, `10:30:00+02:00`, `2024-03-01T10:30:00`,
 * `2024-03-01T10:30:00.500Z`, `P1Y2M3DT4H`. A zoned value keeps the offset it was written with, its fields
 * read on its wall clock and compared as the moment it is. A duration is months, days and seconds, as
 * Cypher's is, and as a Postgres interval is. A year is numbered as Neo4j writes it, astronomically: `0000` is
 * 1 BC and `-0044` is 45 BC, and each store's [TemporalSql] reads and writes a year BC as the store keeps one.
 *
 * Every argument and result is SQL: a text, a number, or with [shared] a value written once where it is read
 * more than once.
 */
class Temporals(
    private val sql: TemporalSql,
    private val shared: (List<String>, (List<String>) -> String) -> String,
    /** Whether [shared] binds a long value in a subquery where the SQL is being written, rather than writing it out. */
    private val binding: () -> Boolean = { true },
) {

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
        shared(listOf(text)) { (t) -> ofKinds(listOf(t), kinds) { kind -> body(kind, t) } }

    /**
     * A `CASE` that gives [body] of the kind among [kinds] that each of [texts] is: null where they are of none. On a store
     * that reads a year BC otherwise than ISO 8601 writes it, a kind with a date has a `WHEN` first, where each is a text
     * of a year from 1000 on ([Temporal.adPattern]), and [body] there reads them as they are ([TemporalSql.knownAd]); the
     * kinds' own `WHEN`s follow those. So an ordinary date costs the tests of the kinds before its own, as before years BC
     * were read, and only a text the first `WHEN`s leave pays to be read as one. A [body] that reads none of them as a
     * store reads a year BC, as a year read off the text, has its kind's `WHEN` alone, as has a long one where the compiler
     * binds nothing ([binding]), as in an aggregate's argument: a value made of another is written out wherever it is read
     * there, and a long [body] written twice would double it again at each (see [TemporalSql.bcOnly]).
     */
    private fun ofKinds(texts: List<String>, kinds: List<Temporal>, body: (Temporal) -> String): String {
        fun whens(test: (String) -> String, sql: String) = "WHEN ${texts.joinToString(" AND ", transform = test)} THEN $sql"
        val first = mutableListOf<String>()
        val own = mutableListOf<String>()
        for (kind in kinds) {
            val ad = kind.adPattern?.takeIf { sql.numbersBcFromOne }
            val (plain, read) = if (ad == null) body(kind) to false else sql.knownAd(texts) { body(kind) }
            if (read && ad != null && (binding() || plain.length <= TemporalSql.GUARDED_UP_TO)) first += whens({ sql.matches(it, literal(ad)) }, plain)
            own += whens({ isKind(it, kind) }, if (read) body(kind) else plain)
        }
        return "CASE ${(first + own).joinToString(" ")} END"
    }

    // ----- Moments and times of day -----

    /**
     * The text of [kind] the wall clock [timestamp] reads, with [offset], a text, where the kind is zoned: null where
     * [timestamp] is null, which Oracle would otherwise write out as the text's punctuation alone (`.`, `.Z`), for its
     * null joins a text as the empty text.
     */
    fun format(timestamp: String, kind: Temporal, offset: String? = null): String = shared(listOf(timestamp)) { (t) -> sql.asAd(timestamp, listOf(t)) {
        val time = "${sql.toChar}($t, 'HH24:MI:SS') || ${fraction(t)}"
        val text = when (kind) {
            Temporal.DATE -> sql.date(t)
            Temporal.LOCAL_DATE_TIME -> "${sql.date(t, "\"T\"")} || $time"
            Temporal.DATE_TIME -> "${sql.date(t, "\"T\"")} || $time || ${offset ?: "'Z'"}"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time || ${offset ?: "'Z'"}"
            Temporal.DURATION -> error("a duration is no moment")
        }
        if (kind == Temporal.DATE) text else "CASE WHEN $t IS NOT NULL THEN $text END"
    } }

    /** A second's fraction as Java writes one: none for a whole second, else three digits or six. */
    private fun fraction(timestamp: String): String = shared(listOf(sql.micros(timestamp))) { (f) ->
        "CASE WHEN $f = '000000' THEN '' WHEN SUBSTR($f, 4, 3) = '000' THEN '.' || SUBSTR($f, 1, 3) ELSE '.' || $f END"
    }

    /**
     * The offset the zoned text [text] ends with, as Cypher gives it: with a colon where it is written without one,
     * and `Z` where it is none, however written (`+00:00`, `-0000`), so a value at UTC is equal to another however
     * each writes it.
     */
    fun offset(text: String): String {
        val utc = sql.replace(sql.group(text, literal(Temporal.OFFSET), 1), "'^[+-]00:?00$'", "'Z'")
        return sql.replace(utc, "'^([+-][0-9]{2})([0-9]{2})$'", "'\\1:\\2'")
    }

    /** The offset a value of [to] made of the text [text] of [from] has: its own, or UTC's where it has none; none where [to] is local. */
    private fun offset(text: String, from: Temporal, to: Temporal): String? = if (to.zoned) (if (from.zoned) offset(text) else "'Z'") else null

    /** The text of [to] the text [text] of [from] is: a moment's date, a date's midnight, a local value's in UTC. */
    fun convert(text: String, from: Temporal, to: Temporal): String = shared(listOf(text)) { (t) ->
        // The wall clock kept is of the text's year, so is written as one from 1000 on where the text's is.
        sql.local(t, from).let { clock -> sql.asAd(t, listOf(clock)) { format(clock, to, offset(t, from, to)) } }
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
        fun field(name: String, default: String, digits: Int) = "${sql.toChar}(${fields[name] ?: default}, 'FM${"0".repeat(digits)}')"
        val micros = "${sql.trunc}(${fields["millisecond"] ?: "0"} * 1000 + ${fields["microsecond"] ?: "0"} + ${fields["nanosecond"] ?: "0"} / 1000)"
        val date = "${field("year", "1970", 4)} || '-' || ${field("month", "1", 2)} || '-' || ${field("day", "1", 2)}"
        val time = "${field("hour", "0", 2)} || ':' || ${field("minute", "0", 2)} || ':' || ${field("second", "0", 2)} || '.' || ${sql.toChar}($micros, 'FM000000')"
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
     * [of] for [fields] that give the date by the calendar's other fields beside the `year`, SQL numbers each: a `week`
     * of the ISO week-based year and its `dayOfWeek`, an `ordinalDay`, or a `quarter` and its `dayOfQuarter`. The date
     * is so many days after the first of the year, of the quarter, or of the year's first ISO week, the week of January
     * 4th. As Neo4j has them, a field that is null is given as its least (the year 0, the first week, day or quarter),
     * and a week runs to 53 and a day of the quarter to 92 in any year, carried into the next where it has fewer. A
     * field that is not whole or is past its range gives the day 0, which the store fails on as it fails on `{day: 0}`.
     */
    fun ofCalendar(fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        val time = fields - CALENDAR_FIELDS - "year"
        fun given(name: String, least: Int) = fields[name]?.let { "COALESCE($it, $least)" } ?: "$least"
        fun january(y: String, day: Int) = sql.local("${sql.toChar}($y, 'FM0000') || '-01-0$day'", Temporal.DATE)
        fun whole(vararg values: String) = values.joinToString(" AND ") { "$it = ${sql.trunc}($it)" }
        val year = given("year", 0)
        return when {
            "week" in fields -> shared(listOf(year, given("week", 1), given("dayOfWeek", 1))) { (y, w, d) ->
                shared(listOf(january(y, 4))) { (january4) ->
                    calendarDate(sql.addTime(january4, "($w - 1) * 7 + $d - ${sql.dayOfWeek(january4)}", "0"), time, timezone, kind) {
                        "${whole(y, w, d)} AND $w BETWEEN 1 AND 53 AND $d BETWEEN 1 AND 7"
                    }
                }
            }
            "ordinalDay" in fields -> shared(listOf(year, given("ordinalDay", 1))) { (y, n) ->
                calendarDate(sql.addTime(january(y, 1), "$n - 1", "0"), time, timezone, kind) { day -> "${whole(y, n)} AND $n >= 1 AND ${sql.year(day)} = $y" }
            }
            else -> shared(listOf(year, given("quarter", 1), given("dayOfQuarter", 1))) { (y, q, n) ->
                calendarDate(sql.addTime(sql.addMonths(january(y, 1), "($q - 1) * 3"), "$n - 1", "0"), time, timezone, kind) {
                    "${whole(y, q, n)} AND $q BETWEEN 1 AND 4 AND $n BETWEEN 1 AND 92"
                }
            }
        }
    }

    /**
     * [of] the date the timestamp [date] reads where [valid] holds of it, else of the day 0, and the fields of the time
     * [time]. A moment's date is read as a date first: every store fails on a date's day 0, where Oracle reads a moment's
     * text as a JSON value's, null where it is no moment.
     */
    private fun calendarDate(date: String, time: Map<String, String>, timezone: String?, kind: Temporal, valid: (String) -> String): String =
        shared(listOf(date)) { (day) ->
            val dayOfMonth = "CASE WHEN NOT (${valid(day)}) THEN 0 ELSE ${sql.extract("DAY", day)} END"
            dated(mapOf("year" to sql.year(day), "month" to sql.extract("MONTH", day), "day" to dayOfMonth), time, timezone, kind)
        }

    /**
     * [of] the date of the fields [date] (`year`, `month`, `day`) and the fields of the time [time], the date read as a date
     * first where [kind] has a time too, so a store fails on a day its month has none of: see [calendarDate].
     */
    private fun dated(date: Map<String, String>, time: Map<String, String>, timezone: String?, kind: Temporal): String =
        if (kind == Temporal.DATE) of(date, null, kind)
        else shared(listOf(sql.local(of(date, null, Temporal.DATE), Temporal.DATE))) { (checked) -> of(time + dateOf(checked), timezone, kind) }

    /** The year, the month and the day of the month of the timestamp [timestamp], as [of] takes them. */
    private fun dateOf(timestamp: String) = mapOf("year" to sql.year(timestamp), "month" to sql.extract("MONTH", timestamp), "day" to sql.extract("DAY", timestamp))

    // ----- Values made from another -----

    /**
     * A temporal value that a map's `date`, `time` or `datetime` gives [over]: its [text], and the [kinds] it may be, one
     * where the statement tells its kind, else those of the kind that give what it is to give (a date, a time of day, both),
     * its text telling which, as a value read from the store does.
     */
    class Base(val text: String, val kinds: List<Temporal>)

    /**
     * The text of [kind] made of the value [date] gives its date and the value [time] gives its time of day (one value where a
     * map's `datetime` gives both), with the fields [fields] laid over theirs, SQL numbers each by Cypher's names, and the
     * offset's text [timezone], as Neo4j lays them:
     * - A `week` is of the base's week-based year or the `year` given, and keeps its day of the week unless one is given; an
     *   `ordinalDay` is of its year or the one given; a `dayOfQuarter` of its quarter or the one given. Each is worked out as
     *   [ofCalendar] works it out.
     * - Else a `year`, a `month` and a `day` given make a date with the base's others, which fails where it is none (February
     *   29th of another year); then a `quarter` alone moves it so many quarters, to the month's last day where it has fewer,
     *   and a `dayOfWeek` alone to that day of its week. Beside a `dayOfWeek` the `year` moves the date so many years, to the
     *   month's last day where it has fewer, and doesn't fail.
     * - A millisecond, a microsecond or a nanosecond given makes the whole fraction of the second.
     * - A time of day or a moment that gives the time keeps its offset, else UTC's; with a [timezone] it is moved there, the same
     *   moment, where it has an offset, and given that offset where it has none.
     *
     * Without [date], the fields give the date as a map's fields do; without [time], the time. Fails as the statement runs where a value
     * given is null, or of a kind that hasn't what it is to give, or the [timezone] is null or no offset, as Neo4j fails.
     */
    fun over(date: Base?, time: Base?, fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        val bases = listOfNotNull(date, time).distinct()
        return shared(bases.map { it.text }) { texts ->
            fun text(base: Base) = texts[bases.indexOf(base)]
            val clocks = bases.map { clock(text(it), it.kinds) }
            shared(clocks) { bound ->
                val day = date?.let { bound[bases.indexOf(it)] }
                val clock = time?.let { bound[bases.indexOf(it)] }
                // The offset of what gives the time, where it has one; null where it is local, or a moment is made without one.
                val own = time?.takeIf { kind.zoned }?.let { base ->
                    val zoned = base.kinds.filter { it.zoned }
                    when {
                        zoned.isEmpty() -> null
                        zoned.size == base.kinds.size -> offset(text(base))
                        else -> "CASE WHEN ${zoned.joinToString(" OR ") { isKind(text(base), it) }} THEN ${offset(text(base))} END"
                    }
                }
                val open = own != null && time.kinds.any { !it.zoned }
                val made = when {
                    !kind.zoned -> overFields(day, clock, fields, null, kind)
                    timezone == null -> overFields(day, clock, fields, own?.let { if (open) "COALESCE($it, 'Z')" else it }, kind)
                    own == null -> overFields(day, clock, fields, timezone, kind)
                    else -> moved(overFields(day, clock, fields, if (open) "COALESCE($own, $timezone)" else own, kind), kind, timezone)
                }
                // A value given that is null, or of a kind that hasn't what it is to give, or a time zone an expression gives that
                // is null or none, fails as on Neo4j: its date is the day 0, which every store fails on, written of the condition
                // so that a store can't fail on it where it isn't reached.
                shared(listOf(made)) { (value) ->
                    // The time zone too, which Oracle's `||` would drop where it is null, not make the text null.
                    val given = (bound + listOfNotNull(timezone) + value).joinToString(" AND ") { "$it IS NOT NULL" }
                    "CASE WHEN $given THEN $value ELSE ${of(mapOf("day" to "CASE WHEN $given THEN 1 ELSE 0 END"), null, Temporal.DATE)} END"
                }
            }
        }
    }

    /** The wall clock the text [text] reads, of the one of [kinds] it is: null where it is none. */
    private fun clock(text: String, kinds: List<Temporal>): String =
        kinds.singleOrNull()?.let { sql.local(text, it) }
            ?: ofKinds(listOf(text), Temporal.instants.filter { it in kinds }) { kind -> sql.local(text, kind) }

    /** [over]'s value of the wall clocks [day] and [clock] (null where none is given), laid over by [fields], with the offset [offset]. */
    private fun overFields(day: String?, clock: String?, fields: Map<String, String>, offset: String?, kind: Temporal): String {
        val time = buildMap {
            listOf("hour" to "HOUR", "minute" to "MINUTE").forEach { (name, part) -> (fields[name] ?: clock?.let { sql.extract(part, it) })?.let { put(name, it) } }
            (fields["second"] ?: clock?.let { "${sql.trunc}(${sql.extract("SECOND", it)})" })?.let { put("second", it) }
            if (FRACTION.any { it in fields }) putAll(fields.filterKeys { it in FRACTION })
            else if (clock != null) put("microsecond", sql.number(sql.micros(clock)))
        }
        if (kind == Temporal.LOCAL_TIME || kind == Temporal.TIME) return of(time, offset, kind)
        val calendar = fields.filterKeys { it == "year" || it in CALENDAR_FIELDS }
        if (day == null) return if (calendar.keys.any { it in CALENDAR_FIELDS }) ofCalendar(calendar + time, offset, kind) else of(fields.filterKeys { it in DAY } + time, offset, kind)
        fun own(name: String) = fields[name] ?: clockField(day, name)
        return when {
            "week" in fields -> ofCalendar(mapOf("year" to (fields["year"] ?: clockField(day, "weekYear")), "week" to own("week"), "dayOfWeek" to own("dayOfWeek")) + time, offset, kind)
            "ordinalDay" in fields -> ofCalendar(mapOf("year" to own("year"), "ordinalDay" to own("ordinalDay")) + time, offset, kind)
            "dayOfQuarter" in fields -> ofCalendar(mapOf("year" to own("year"), "quarter" to own("quarter"), "dayOfQuarter" to own("dayOfQuarter")) + time, offset, kind)
            DAY.none { it in fields } -> moveDay(day, fields, time, offset, kind)
            // Beside a day of the week (no month or day can be), a year moves the date so many years, to February 28th from the
            // 29th of a common year, as Neo4j moves it, where alone it fails there.
            "dayOfWeek" in fields -> moveDay(sql.addMonths(day, "(${own("year")} - ${clockField(day, "year")}) * 12"), fields, time, offset, kind)
            else -> shared(listOf(sql.local(of(DAY.associateWith { own(it) }, null, Temporal.DATE), Temporal.DATE))) { (checked) -> moveDay(checked, fields, time, offset, kind) }
        }
    }

    /** [over]'s value of the date [day], moved by a `quarter` or a `dayOfWeek` among [fields] where one is. */
    private fun moveDay(day: String, fields: Map<String, String>, time: Map<String, String>, offset: String?, kind: Temporal): String {
        fun whole(value: String, last: Int) = "$value = ${sql.trunc}($value) AND $value BETWEEN 1 AND $last"
        val quarter = fields["quarter"]
        val dayOfWeek = fields["dayOfWeek"]
        return when {
            quarter != null -> shared(listOf(quarter)) { (q) -> calendarDate(sql.addMonths(day, "($q - ${clockField(day, "quarter")}) * 3"), time, offset, kind) { whole(q, 4) } }
            dayOfWeek != null -> shared(listOf(dayOfWeek)) { (d) -> calendarDate(sql.addTime(day, "$d - ${sql.dayOfWeek(day)}", "0"), time, offset, kind) { whole(d, 7) } }
            else -> of(dateOf(day) + time, offset, kind)
        }
    }

    /** The text of [kind], zoned, the same moment as the text [text] of it at the offset's text [timezone]. */
    private fun moved(text: String, kind: Temporal, timezone: String): String = shared(listOf(text, timezone)) { (t, z) ->
        format(sql.addTime(sql.utc(t, kind), "0", zoneField(z, "offsetSeconds").sql), kind, offset(z))
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
        val epoch = sql.epoch
        when (name) {
            "epochSeconds" -> return Field("${sql.trunc}(${sql.secondsBetween(epoch, sql.utc(text, kind))})", text = false)
            "epochMillis" -> return Field("${sql.trunc}(${sql.secondsBetween(epoch, sql.utc(text, kind))} * 1000)", text = false)
        }
        if (name !in INSTANT_FIELDS) return null
        if (name == "year") sql.textYear(text, kind)?.let { return Field(it, text = false) }
        // A field read from the timestamp once is read where it is: bound, a stored date's year was a subquery for each row.
        val local = sql.local(text, kind)
        return Field(if (name in READ_ONCE) clockField(local, name) else shared(listOf(local)) { (t) -> clockField(t, name) }, text = false)
    }

    /** The field [name], one of [INSTANT_FIELDS], of the wall clock [t], a number. */
    private fun clockField(t: String, name: String): String {
        val second = sql.extract("SECOND", t)
        return when (name) {
            "year" -> sql.year(t)
            "quarter" -> "${sql.trunc}((${sql.extract("MONTH", t)} + 2) / 3)"
            "month" -> sql.extract("MONTH", t)
            "week" -> sql.number("${sql.toChar}($t, 'IW')")
            "weekYear" -> sql.weekYear(t)
            "day" -> sql.extract("DAY", t)
            "ordinalDay" -> sql.number("${sql.toChar}($t, 'DDD')")
            "dayOfQuarter" -> "(${sql.number("${sql.toChar}($t, 'DDD')")} - ${sql.number("${sql.toChar}(${sql.truncate("quarter", t)!!}, 'DDD')")} + 1)"
            "dayOfWeek", "weekDay" -> sql.dayOfWeek(t)
            "hour" -> sql.extract("HOUR", t)
            "minute" -> sql.extract("MINUTE", t)
            "second" -> "${sql.trunc}($second)"
            "millisecond" -> "(${sql.trunc}($second * 1000) - ${sql.trunc}($second) * 1000)"
            "microsecond" -> "(${sql.trunc}($second * 1000000) - ${sql.trunc}($second) * 1000000)"
            else -> "((${sql.trunc}($second * 1000000) - ${sql.trunc}($second) * 1000000) * 1000)"
        }
    }

    /** A field of a value: its SQL, and whether it is a text, where it is not a number. */
    class Field(val sql: String, val text: Boolean)

    private fun zoneField(text: String, name: String): Field = shared(listOf(offset(text))) { (o) ->
        val sign = "CASE WHEN SUBSTR($o, 1, 1) = '-' THEN -1 ELSE 1 END"
        val minutes = "CASE WHEN $o = 'Z' THEN 0 ELSE $sign * (${sql.number("SUBSTR($o, 2, 2)")} * 60 + ${sql.number("SUBSTR(REPLACE($o, ':', ''), 4, 2)")}) END"
        when (name) {
            "timezone", "offset" -> o
            "offsetMinutes" -> minutes
            else -> "($minutes) * 60"
        }
    }.let { Field(it, text = name == "timezone" || name == "offset") }

    /** The names of the fields a temporal value may have, of a moment's or a duration's. */
    val fieldNames = setOf(
        "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfQuarter", "dayOfWeek", "weekDay", "hour", "minute", "second",
        "millisecond", "microsecond", "nanosecond", "epochSeconds", "epochMillis", "timezone", "offset", "offsetMinutes", "offsetSeconds",
        "years", "quarters", "months", "weeks", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds",
        "quartersOfYear", "monthsOfQuarter", "monthsOfYear", "daysOfWeek", "minutesOfHour", "secondsOfMinute", "millisecondsOfSecond",
        "microsecondsOfSecond", "nanosecondsOfSecond",
    )

    /** The text of [kind] the duration text [duration] after the text [text] of it, or with [sign] -1 before: a zoned value keeps its offset. */
    fun add(text: String, kind: Temporal, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        format(moved(sql.local(t, kind), d, sign, if (kind == Temporal.DATE) "1 = 1" else null), kind, offset(t, kind, kind))
    }

    /**
     * [add] for a text of whatever kind its shape tells: read as a wall clock once, whatever its kind, the duration
     * added once, and written as the kind it was.
     */
    fun addToAny(text: String, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        shared(listOf(moved(clock(t, Temporal.instants), d, sign, isKind(t, Temporal.DATE)))) { (moved) ->
            "CASE ${Temporal.instants.joinToString(" ") { "WHEN ${isKind(t, it)} THEN ${format(moved, it, offset(t, it, it))}" }} END"
        }
    }

    /**
     * The timestamp [timestamp] the duration text [duration] later, or with [sign] -1 earlier. Where [date], a SQL
     * condition, holds, the duration's time is taken in whole days alone, as Cypher adds one to a date: a date less
     * `PT1H` is the same date, less `PT25H` the day before.
     */
    private fun moved(timestamp: String, duration: String, sign: Int, date: String? = null): String {
        val s = if (sign < 0) "-" else ""
        val parts = parts(duration)
        val seconds = if (date == null) parts.seconds else shared(listOf(parts.seconds)) { (sec) -> "CASE WHEN $date THEN ${sql.trunc}($sec / 86400) * 86400 ELSE $sec END" }
        return sql.addTime(sql.addMonths(timestamp, "$s(${parts.months})"), "$s(${parts.days})", "$s($seconds)")
    }

    /**
     * The fields the third argument of a truncation gives the value cut short, in place of its own: SQL numbers by
     * Cypher's names (`year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`, `microsecond`), `dayOfWeek`,
     * and [timezone], an offset's text.
     */
    class Overrides(val fields: Map<String, String>, val timezone: String?)

    /**
     * The text of [to] the text [text] of [from] is, cut short to [unit], with [overrides] in place of its fields where
     * it has any: null where the store truncates to no such unit.
     */
    fun truncate(unit: String, text: String, from: Temporal, to: Temporal, overrides: Overrides? = null): String? {
        if (sql.truncate(unit, "NULL") == null) return null
        if (overrides == null || (overrides.fields.isEmpty() && overrides.timezone == null)) {
            // A truncation of a year from 1000 on is of one too, so is written as such.
            return shared(listOf(text)) { (t) -> sql.truncate(unit, sql.local(t, from))!!.let { cut -> sql.asAd(t, listOf(cut)) { format(cut, to, offset(t, from, to)) } } }
        }
        return shared(listOf(text)) { (t) ->
            shared(listOf(sql.truncate(unit, sql.local(t, from))!!)) { (cut) -> overridden(cut, unit, to, overrides, overrides.timezone ?: offset(t, from, to)) }
        }
    }

    /**
     * The text of [kind] of the timestamp [cut], cut short to [unit], with the fields [overrides] gives in place of its own,
     * and the offset [offset], as Neo4j lays a truncation's fields over the value: the year, month and day, then the time,
     * then the day of the week, which moves the value within its ISO week. A millisecond or a microsecond given makes the
     * whole fraction of the second, but a microsecond given a value cut to the millisecond is added to the millisecond kept.
     */
    private fun overridden(cut: String, unit: String, kind: Temporal, overrides: Overrides, offset: String?): String {
        val given = overrides.fields
        val own = mapOf(
            "year" to sql.year(cut), "month" to sql.extract("MONTH", cut), "day" to sql.extract("DAY", cut),
            "hour" to sql.extract("HOUR", cut), "minute" to sql.extract("MINUTE", cut), "second" to "${sql.trunc}(${sql.extract("SECOND", cut)})",
        )
        val micros = sql.number(sql.micros(cut))
        val carried = unit == "millisecond" && "microsecond" in given
        val kept = if (carried) "($micros + ${given["microsecond"]})" else micros
        val fraction = when {
            "millisecond" in given -> "${given["millisecond"]} * 1000" + if (carried || "microsecond" !in given) "" else " + ${given["microsecond"]}"
            "microsecond" in given && !carried -> given.getValue("microsecond")
            else -> kept
        }
        val made = of(own.mapValues { (name, value) -> given[name] ?: value } + ("microsecond" to fraction), offset, kind)
        val day = given["dayOfWeek"] ?: return made
        return shared(listOf(made)) { (m) ->
            shared(listOf(sql.local(m, kind))) { (clock) ->
                format(sql.addTime(clock, "$day - ${sql.dayOfWeek(clock)}", "0"), kind, if (kind.zoned) offset(m) else null)
            }
        }
    }

    /** What the text [text] of [kind] sorts by: the moment it is, whatever its offset, as a timestamp. */
    fun moment(text: String, kind: Temporal): String = sql.utc(text, kind)

    /** Whether the text [a] stands to the text [b], both of [kind], as [operator] says: null where either is no such text. */
    fun compare(a: String, b: String, kind: Temporal, operator: String): String = shared(listOf(a, b)) { (x, y) ->
        fun test() = if (kind == Temporal.DURATION) {
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
        ofKinds(listOf(x, y), listOf(kind)) { test() }
    }

    // ----- Durations -----

    /**
     * The parts of the duration text [text], which each read it more than once: [text] is a name, written once.
     * A part it leaves out is 0, and every part of null is null, so a duration of null is added as null. A duration
     * negated whole opens with its sign.
     */
    private fun parts(text: String): Parts {
        fun group(name: String) = Temporal.DURATION_PARTS.getValue(name).let { (pattern, group) -> sql.group(text, literal(pattern), group) }
        fun part(name: String) = "COALESCE(${sql.number(group(name))}, 0)"
        val sign = "CASE WHEN SUBSTR($text, 1, 1) = '-' THEN -1 WHEN $text IS NOT NULL THEN 1 END"
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
            "(${f("hours")} * 3600 + ${f("minutes")} * 60 + ${f("seconds")} + ${sql.quotient(f("milliseconds"), "1000")} + ${sql.quotient(f("microseconds"), "1000000")} + ${sql.quotient(f("nanoseconds"), "1000000000")})",
        ))
    }

    /**
     * The duration text of the parts [parts], as Neo4j writes one: whole years and months, days, then hours,
     * minutes and seconds, each signed, and `PT0S` for none. A fraction of a month or a day goes to the parts
     * below it, as Cypher's does: a month as 30.436875 days. Null where a part is null.
     */
    private fun text(parts: Parts): String = shared(listOf(parts.months, parts.days, parts.seconds)) { (m0, d0, s0) ->
        val m = "${sql.trunc}($m0)"
        val d = "${sql.trunc}($d0 + ($m0 - ${sql.trunc}($m0)) * 30.436875)"
        val s = "($s0 + ($d0 + ($m0 - ${sql.trunc}($m0)) * 30.436875 - $d) * 86400)"
        shared(listOf(m, d, s)) { (months, days, seconds) ->
            val years = "${sql.trunc}($months / 12)"
            val hours = "${sql.trunc}($seconds / 3600)"
            val minutes = "(${sql.trunc}($seconds / 60) - $hours * 60)"
            val secs = "($seconds - ${sql.trunc}($seconds / 60) * 60)"
            fun unit(value: String, letter: String) = "CASE WHEN $value <> 0 THEN ${number(value)} || '$letter' ELSE '' END"
            "CASE WHEN $months = 0 AND $days = 0 AND $seconds = 0 THEN 'PT0S' WHEN $seconds IS NOT NULL THEN 'P' || ${unit(years, "Y")} || ${unit("($months - $years * 12)", "M")} || " +
                "${unit(days, "D")} || CASE WHEN $seconds <> 0 THEN 'T' || ${unit(hours, "H")} || ${unit(minutes, "M")} || ${unit(secs, "S")} ELSE '' END END"
        }
    }

    /** A field of a duration of the parts [parts], by Cypher's name for it: null for a name a duration has no field of. */
    private fun durationField(parts: Parts, name: String): String? {
        val (m, d, s) = Triple(parts.months, parts.days, parts.seconds)
        return when (name) {
            "years" -> "${sql.trunc}($m / 12)"
            "quarters" -> "${sql.trunc}($m / 3)"
            "months" -> m
            "weeks" -> "${sql.trunc}($d / 7)"
            "days" -> d
            "hours" -> "${sql.trunc}($s / 3600)"
            "minutes" -> "${sql.trunc}($s / 60)"
            "seconds" -> "${sql.trunc}($s)"
            "milliseconds" -> "${sql.trunc}($s * 1000)"
            "microseconds" -> "${sql.trunc}($s * 1000000)"
            "nanoseconds" -> "${sql.trunc}($s * 1000000000)"
            "quartersOfYear" -> "(${sql.trunc}($m / 3) - ${sql.trunc}($m / 12) * 4)"
            "monthsOfQuarter" -> "($m - ${sql.trunc}($m / 3) * 3)"
            "monthsOfYear" -> "($m - ${sql.trunc}($m / 12) * 12)"
            "daysOfWeek" -> "($d - ${sql.trunc}($d / 7) * 7)"
            "minutesOfHour" -> "(${sql.trunc}($s / 60) - ${sql.trunc}($s / 3600) * 60)"
            "secondsOfMinute" -> "(${sql.trunc}($s) - ${sql.trunc}($s / 60) * 60)"
            "millisecondsOfSecond" -> "(${sql.trunc}($s * 1000) - ${sql.trunc}($s) * 1000)"
            "microsecondsOfSecond" -> "(${sql.trunc}($s * 1000000) - ${sql.trunc}($s) * 1000000)"
            "nanosecondsOfSecond" -> "(${sql.trunc}($s * 1000000000) - ${sql.trunc}($s) * 1000000000)"
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
     * `duration.between`: the duration from the text [from] of [kind] to the text [to] of [toKind], in whole months
     * as a calendar counts them, then days, then seconds; with [unit] `months`, `days` or `seconds`, in that alone.
     * Two zoned values are read as the moments they are; where either is local, both are read on their wall clocks.
     */
    fun between(from: String, to: String, kind: Temporal, unit: String? = null, toKind: Temporal = kind): String {
        val zoned = kind.zoned && toKind.zoned
        fun read(text: String, of: Temporal) = if (zoned) sql.utc(text, of) else sql.local(text, of)
        return between(read(from, kind), read(to, toKind), kind in TIMES || toKind in TIMES, unit)
    }

    private fun between(from: String, to: String, times: Boolean, unit: String?): String = shared(listOf(from, to)) { (a, b) ->
        val months = if (times) "0" else sql.monthsBetween(a, b)
        when (unit) {
            "months" -> text(Parts(months, "0", "0"))
            "days" -> text(Parts("0", "${sql.trunc}(${sql.secondsBetween(a, b)} / 86400)", "0"))
            "seconds" -> text(Parts("0", "0", sql.secondsBetween(a, b)))
            else -> shared(listOf(months)) { (m) ->
                shared(listOf(sql.secondsBetween(sql.addMonths(a, m), b))) { (rest) -> text(Parts(m, "${sql.trunc}($rest / 86400)", "($rest - ${sql.trunc}($rest / 86400) * 86400)")) }
            }
        }
    }

    /** A number as Neo4j writes one in a duration: no zeros after the point, nor the point where there is no fraction. */
    private fun number(value: String) = "RTRIM(${sql.toChar}($value, 'FM9999999999990.999999999'), '.')"

    private fun literal(text: String) = "'${text.replace("'", "''")}'"

    companion object {
        /**
         * The canonical text of [kind] of the text [text], worked out as the store's SQL would work it out, for a
         * value the statement writes: null where the text is of no temporal kind.
         */
        fun canonical(text: String, kind: Temporal): String? {
            if (kind == Temporal.DURATION) return if (Regex(Temporal.DURATION.pattern).matches(text)) canonicalDuration(text) else null
            val from = Temporal.instants.firstOrNull { Regex(it.pattern).matches(text) } ?: return null
            val written = if (from.zoned) Regex(Temporal.OFFSET).find(text)!!.value else null
            val local = if (written != null) text.removeSuffix(written) else text
            val offset = written?.replace(Regex("^[+-]00:?00$"), "Z")?.replace(Regex("^([+-][0-9]{2})([0-9]{2})$"), "$1:$2")
            val clock = when (from) {
                Temporal.DATE -> java.time.LocalDate.parse(local).atStartOfDay()
                Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> java.time.LocalDateTime.parse(local)
                else -> java.time.LocalTime.parse(local).atDate(java.time.LocalDate.of(1970, 1, 1))
            }
            val micros = clock.nano / 1000
            val fraction = when {
                micros == 0 -> ""
                micros % 1000 == 0 -> ".%03d".format(java.util.Locale.ROOT, micros / 1000)
                else -> ".%06d".format(java.util.Locale.ROOT, micros)
            }
            val time = "%02d:%02d:%02d".format(java.util.Locale.ROOT, clock.hour, clock.minute, clock.second) + fraction
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

        /** The kinds of a time of day, which no months are between. */
        private val TIMES = setOf(Temporal.TIME, Temporal.LOCAL_TIME)

        /** The [INSTANT_FIELDS] that read the timestamp once, on every store: see [TemporalSql.year]. */
        private val READ_ONCE = setOf("year", "quarter", "month", "week", "day", "ordinalDay", "hour", "minute")

        /** The fields of a moment or a time of day read off its wall clock. */
        val INSTANT_FIELDS = setOf(
            "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfQuarter", "dayOfWeek", "weekDay",
            "hour", "minute", "second", "millisecond", "microsecond", "nanosecond",
        )

        /** The fields that give a date by the calendar's other fields, beside the year, in place of a month and a day: see [ofCalendar]. */
        val CALENDAR_FIELDS = setOf("week", "dayOfWeek", "ordinalDay", "quarter", "dayOfQuarter")

        /** The fields of a map that give another temporal value to make one of: see [over]. */
        val SELECTORS = setOf("date", "time", "datetime")

        /** The fields of a date by its month: see [over]. */
        private val DAY = listOf("year", "month", "day")

        /** The fields of a fraction of a second, which make the whole fraction where any is given: see [over]. */
        private val FRACTION = setOf("millisecond", "microsecond", "nanosecond")
    }
}
