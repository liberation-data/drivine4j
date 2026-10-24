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

    /** The name SQLite's `X REGEXP Y` calls, as `regexp(Y, X)`. */
    const val REGEXP = "regexp"

    /** Registers every function on [connection], a connection of xerial's driver, for the statements run on it. */
    fun register(connection: Connection) {
        val patterns = SqlitePatterns()
        Function.create(connection, WRITE, SqliteWriteFunction(connection), 6, 0)
        Function.create(connection, DELETE, SqliteDeleteFunction(connection), 2, 0)
        Function.create(connection, SIMILARITY, SqliteSimilarityFunction(), 3, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REGEXP_COUNT, SqliteRegexpCountFunction(patterns), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, REGEXP, SqliteRegexpFunction(patterns), 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, LOWER, SqliteLowerFunction(), 1, Function.FLAG_DETERMINISTIC)
        Function.create(connection, EPOCH, SqliteEpochFunction(), 1, Function.FLAG_DETERMINISTIC)
    }
}
