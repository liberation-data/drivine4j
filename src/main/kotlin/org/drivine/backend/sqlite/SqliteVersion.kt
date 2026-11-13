package org.drivine.backend.sqlite

import org.slf4j.LoggerFactory

/**
 * The SQLite a store's connections run, read with `select sqlite_version()` the first time a Cypher statement is
 * compiled, and kept: a provider shares one between its connections, and a connection made on its own has its own.
 *
 * Compiled Cypher needs SQLite 3.53.2 or later, though the application supplies the driver, `org.xerial:sqlite-jdbc`, and
 * may supply an older one. Before 3.53.2 a compiled statement writes a float to 15 digits (drivine-python#376): xerial's
 * 3.53.0.0 and 3.53.1.0 write `json_array(0.1 + 0.2)` as `[0.3]`, and 3.53.2.0 on as `[0.30000000000000004]`. 3.49
 * refuses a `CALL` subquery that groups by a variable it imports; and before 3.47, `-> -1`, which `last()` and a
 * negative list index compile to, is refused (#565). There a Cypher statement is refused with why ([refusal]), before
 * it is compiled. Plain SQL, the schema manager and the object managers, which write SQL of their own, are not refused,
 * though the object managers' conditions and orders are compiled by the module too, and a grouped read's least or
 * greatest value and a path's order are an ordered aggregate (`json_group_array(… ORDER BY …)`), which SQLite before 3.44
 * refuses.
 */
internal class SqliteVersion {

    /** The version read, once it is. */
    @Volatile
    private var version: String? = null

    /**
     * Why compiled Cypher does not run on the SQLite [connection] is a connection to: one older than 3.53.2. Null for
     * 3.53.2 or later, and where the version cannot be read, which refuses nothing: the statement says what is wrong as it
     * runs. A version that cannot be read is not kept, so the next Cypher statement reads it again.
     */
    fun refusal(connection: java.sql.Connection): String? {
        val version = version ?: read(connection)?.also {
            version = it
            refusal(it)?.let { why -> logger.warn("{}", why) }
        }
        return version?.let(::refusal)
    }

    /** `sqlite_version()` on [connection]; null where it cannot be read. */
    private fun read(connection: java.sql.Connection): String? = try {
        connection.prepareStatement(QUERY).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    } catch (e: Exception) {
        logger.debug("Could not read the version of SQLite.", e)
        null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SqliteVersion::class.java)

        /** How the version is read. */
        internal const val QUERY = "select sqlite_version()"

        /** The oldest SQLite compiled Cypher runs on, 3.53.2: see the class. */
        private const val MAJOR = 3
        private const val MINOR = 53
        private const val PATCH = 2

        /**
         * Why compiled Cypher does not run on SQLite [version], as `sqlite_version()` gives it, `3.46.1`: one older than
         * 3.53.2. Null for 3.53.2 or later, and for a version that is no version, which refuses nothing. Each part is read
         * by its leading digits, so text after them, as in `3.46.1-rc1`, is not taken for no version; a missing patch is 0.
         */
        internal fun refusal(version: String): String? {
            val parts = version.trim().split('.').take(3).map { it.takeWhile(Char::isDigit).toIntOrNull() ?: return null }
            if (parts.size < 2) return null
            val (major, minor) = parts
            val patch = parts.getOrElse(2) { 0 }
            if (major > MAJOR || (major == MAJOR && (minor > MINOR || (minor == MINOR && patch >= PATCH)))) return null
            return "Compiled Cypher needs SQLite $MAJOR.$MINOR.$PATCH or later; this is SQLite ${version.trim()}. " +
                "Upgrade sqlite-jdbc (org.xerial:sqlite-jdbc $MAJOR.$MINOR.$PATCH.0 or later). Plain SQL over this provider still runs; Cypher does not."
        }
    }
}
