package org.drivine.backend.sqlite

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

/** [SqliteTimestamps.write], which the compiler's writing makes, writes a timestamp as java.time's pattern did before it. */
class SqliteTimestampsTest {

    private val pattern = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")

    @Test
    fun `a timestamp is written to the microsecond as the pattern writes it, at every year`() {
        val clocks = listOf(
            LocalDateTime.of(2024, 1, 2, 3, 4, 5, 6_789),
            LocalDateTime.of(1970, 12, 31, 23, 59, 59, 999_999_999),
            LocalDateTime.of(0, 1, 1, 0, 0),
            LocalDateTime.of(-1, 6, 15, 12, 0, 0, 1_000),
            LocalDateTime.of(-12345, 2, 28, 1, 2, 3),
            LocalDateTime.of(999, 3, 4, 5, 6, 7),
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000),
            LocalDateTime.of(10000, 1, 1, 0, 0),
            LocalDateTime.MIN,
            LocalDateTime.MAX,
        )
        for (clock in clocks) assertEquals(pattern.format(clock), SqliteTimestamps.write(clock), "$clock")
    }
}
