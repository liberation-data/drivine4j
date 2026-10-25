package org.drivine.backend.sqlite

import org.sqlite.Function
import java.sql.Connection

/**
 * The functions Drivine registers on each connection to a SQLite store, which its statements call:
 * SQLite keeps no function of its own a statement could, and runs one written in the application's
 * language as readily as its own. Each is registered by [register], on a connection as it is opened
 * (see [SqliteDataSource]).
 *
 * - [WRITE] carries out a plan of steps: see [SqliteWriteFunction].
 * - [DELETE] deletes the nodes a statement selected, and counts them: see [SqliteDeleteFunction].
 * - [SIMILARITY] scores an embedding against a vector: see [SqliteSimilarityFunction].
 * - [REGEXP_COUNT] counts the matches of a regular expression, and [REGEXP] is SQLite's `REGEXP`
 *   operator: see [SqliteRegexpCountFunction] and [SqliteRegexpFunction].
 * - [LOWER] lowers text as Unicode does: see [SqliteLowerFunction].
 * - [EPOCH] reads a moment as nanoseconds since the epoch: see [SqliteEpochFunction].
 *
 * Compiled Cypher calls the rest, for what Cypher does with its values, JSON text each, that SQLite does not:
 *
 * - [EQUAL], [CONTAINS], [CANONICAL] and [ADD] compare, group and add them: see [SqliteEqualFunction],
 *   [SqliteContainsFunction], [SqliteCanonicalFunction] and [SqliteAddFunction].
 * - [UPPER], [SPLIT], [REVERSE] and [UUID] are Cypher's `toUpper`, `split`, `reverse` and `randomUUID`: see
 *   [SqliteUpperFunction], [SqliteSplitFunction], [SqliteReverseFunction] and [SqliteUuidFunction].
 * - [MATH] and [ROUND] work out Cypher's functions of numbers, `exp`, `log`, `sin` …, and `round` by a mode, as
 *   Neo4j does: see [SqliteMathFunction] and [SqliteRoundFunction].
 * - [REGEX_GROUP] and [REGEX_REPLACE] read and replace what a regular expression matches: see
 *   [SqliteRegexGroupFunction] and [SqliteRegexReplaceFunction].
 * - [FLOAT_TEXT] writes a float as Java does, for `toString`, and [SORT_KEY] orders a list as Cypher does: see
 *   [SqliteFloatTextFunction] and [SqliteSortKeyFunction].
 * - [TRUNC], [TO_CHAR] and [TEMPORAL] work out temporal values: see [SqliteTruncFunction], [SqliteToCharFunction]
 *   and [SqliteTemporalFunction].
 * - [CYPHER] carries out the procedure a statement that writes compiles to: see [SqliteCypherFunction].
 *
 * A function that reads only its arguments is registered as deterministic, so SQLite may index an
 * expression over it.
 */
internal object SqliteFunctions {

    const val WRITE = "drivine_write"
    const val DELETE = "drivine_delete"
    const val SIMILARITY = "drivine_similarity"
    const val REGEXP_COUNT = "drivine_regexp_count"
    const val LOWER = "drivine_lower"
    const val EPOCH = "drivine_epoch"
    const val EQUAL = "drivine_equal"
    const val CONTAINS = "drivine_contains"
    const val ADD = "drivine_add"
    const val CANONICAL = "drivine_canonical"
    const val CYPHER = "drivine_cypher"
    const val UPPER = "drivine_upper"
    const val SPLIT = "drivine_split"
    const val REVERSE = "drivine_reverse"
    const val UUID = "drivine_uuid"
    const val REGEX_GROUP = "drivine_regex_group"
    const val REGEX_REPLACE = "drivine_regexp_replace"
    const val TRUNC = "drivine_trunc"
    const val TO_CHAR = "drivine_to_char"
    const val TEMPORAL = "drivine_temporal"
    const val FLOAT_TEXT = "drivine_float_text"
    const val SORT_KEY = "drivine_sort_key"
    const val MATH = "drivine_math"
    const val ROUND = "drivine_round"

    /** The name SQLite's `X REGEXP Y` calls, as `regexp(Y, X)`. */
    const val REGEXP = "regexp"

    /** Registers every function on [connection], a connection of xerial's driver, for the statements run on it. */
    fun register(connection: Connection) {
        val patterns = SqlitePatterns()
        Function.create(connection, WRITE, SqliteWriteFunction(connection), 6, 0)
        Function.create(connection, DELETE, SqliteDeleteFunction(connection), 2, 0)
        Function.create(connection, CYPHER, SqliteCypherFunction(connection), 2, 0)
        Function.create(connection, SIMILARITY, SqliteSimilarityFunction(), 3, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REGEXP_COUNT, SqliteRegexpCountFunction(patterns), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REGEXP, SqliteRegexpFunction(patterns), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, LOWER, SqliteLowerFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, EPOCH, SqliteEpochFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, EQUAL, SqliteEqualFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, CONTAINS, SqliteContainsFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, ADD, SqliteAddFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, CANONICAL, SqliteCanonicalFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, UPPER, SqliteUpperFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, SPLIT, SqliteSplitFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REVERSE, SqliteReverseFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, UUID, SqliteUuidFunction(), 0, 0)
        Function.create(connection, REGEX_GROUP, SqliteRegexGroupFunction(patterns), 3, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REGEX_REPLACE, SqliteRegexReplaceFunction(patterns), 3, Function.FLAG_DETERMINISTIC)
        Function.create(connection, TRUNC, SqliteTruncFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, TO_CHAR, SqliteToCharFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, FLOAT_TEXT, SqliteFloatTextFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, SORT_KEY, SqliteSortKeyFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, ROUND, SqliteRoundFunction(), 3, Function.FLAG_DETERMINISTIC)
        // Of as many arguments as its operation takes.
        Function.create(connection, TEMPORAL, SqliteTemporalFunction(), -1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, MATH, SqliteMathFunction(), -1, Function.FLAG_DETERMINISTIC)
    }
}
