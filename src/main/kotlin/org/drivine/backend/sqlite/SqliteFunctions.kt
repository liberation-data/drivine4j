package org.drivine.backend.sqlite

import org.sqlite.Function
import java.sql.Connection
import org.drivine.cypher.store.SqliteFunctions as Shared

/**
 * The functions Drivine registers on each connection to a SQLite store, which its statements call:
 * SQLite keeps no function of its own a statement could, and runs one written in the application's
 * language as readily as its own. Each is registered by [register], on a connection as it is opened
 * (see [SqliteDataSource]).
 *
 * - [WRITE] carries out a plan of steps: see [SqliteWriteFunction].
 * - [DELETE] deletes the nodes a statement selected, and counts them: see [SqliteDeleteFunction].
 * - [LOWER_COPIES] fills in the lowercased copies of a property, and counts the nodes it wrote: see
 *   [SqliteLowerCopiesFunction].
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
 * - [FLOAT_TEXT] writes a float as Java does, for `toString`, [SORT_KEY] orders a list as Cypher does, and [LIST_ORDER]
 *   compares two lists as Cypher does: see [SqliteFloatTextFunction], [SqliteSortKeyFunction] and [SqliteListOrderFunction].
 * - [TRUNC], [TO_CHAR] and [TEMPORAL] work out temporal values: see [SqliteTruncFunction], [SqliteToCharFunction]
 *   and [SqliteTemporalFunction].
 * - [CYPHER] carries out the procedure a statement that writes compiles to: see [SqliteCypherFunction].
 * - [FAIL] fails the statement with its text, where Neo4j fails on a value it reads: see [SqliteFailFunction].
 *
 * A function that reads only its arguments is registered as deterministic, so SQLite may index an
 * expression over it.
 */
internal object SqliteFunctions {

    // The functions' names, which the compiler's SQL calls too: see [Shared].
    const val WRITE = Shared.WRITE
    const val DELETE = Shared.DELETE
    const val LOWER_COPIES = Shared.LOWER_COPIES
    const val SIMILARITY = Shared.SIMILARITY
    const val REGEXP_COUNT = Shared.REGEXP_COUNT
    const val LOWER = Shared.LOWER
    const val EPOCH = Shared.EPOCH
    const val EQUAL = Shared.EQUAL
    const val CONTAINS = Shared.CONTAINS
    const val ADD = Shared.ADD
    const val CANONICAL = Shared.CANONICAL
    const val CYPHER = Shared.CYPHER
    const val UPPER = Shared.UPPER
    const val SPLIT = Shared.SPLIT
    const val REVERSE = Shared.REVERSE
    const val UUID = Shared.UUID
    const val REGEX_GROUP = Shared.REGEX_GROUP
    const val REGEX_REPLACE = Shared.REGEX_REPLACE
    const val TRUNC = Shared.TRUNC
    const val TO_CHAR = Shared.TO_CHAR
    const val TEMPORAL = Shared.TEMPORAL
    const val FLOAT_TEXT = Shared.FLOAT_TEXT
    const val SORT_KEY = Shared.SORT_KEY
    const val LIST_ORDER = Shared.LIST_ORDER
    const val MATH = Shared.MATH
    const val ROUND = Shared.ROUND
    const val FAIL = Shared.FAIL

    /** The name SQLite's `X REGEXP Y` calls, as `regexp(Y, X)`: as [Shared.REGEXP]. */
    const val REGEXP = Shared.REGEXP

    /** Registers every function on [connection], a connection of xerial's driver, for the statements run on it. */
    fun register(connection: Connection) {
        val patterns = SqlitePatterns()
        Function.create(connection, WRITE, SqliteWriteFunction(connection), 6, 0)
        Function.create(connection, DELETE, SqliteDeleteFunction(connection), 2, 0)
        Function.create(connection, LOWER_COPIES, SqliteLowerCopiesFunction(connection), 4, 0)
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
        Function.create(connection, LIST_ORDER, SqliteListOrderFunction(), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, ROUND, SqliteRoundFunction(), 3, Function.FLAG_DETERMINISTIC)
        // Not deterministic: SQLite must not work it out before a row that needs it.
        Function.create(connection, FAIL, SqliteFailFunction(), 1, 0)
        // Of as many arguments as its operation takes.
        Function.create(connection, TEMPORAL, SqliteTemporalFunction(), -1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, MATH, SqliteMathFunction(), -1, Function.FLAG_DETERMINISTIC)
    }
}
