package org.drivine.backend.sqlite

import java.time.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.drivine.cypher.store.SqliteTimestamps as Shared

/**
 * A timestamp as compiled Cypher holds one on SQLite, which has no type for it: the text of a wall clock to the
 * microsecond, `2024-03-15T10:30:00.000000`, every one as long as every other, so two compare and order as their
 * clocks do, as text. See `SqliteTemporalSql`.
 */
internal object SqliteTimestamps {

    /** The timestamp [text], or null where it is null. */
    fun read(text: String?): LocalDateTime? = text?.let { LocalDateTime.parse(it.replace(' ', 'T')) }

    /** [clock] as a timestamp's text, to the microsecond. */
    fun write(clock: LocalDateTime): String = Shared.write(clock.toKotlinLocalDateTime())
}
