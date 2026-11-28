package org.drivine.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertFalse

/**
 * `sqlite::memory:`'s directory goes, all of it, even while something still makes files in it, as the store's pool
 * opening a connection does while a signal's shutdown hook deletes it (#913).
 */
class ScratchTest {

    @Test
    fun `the directory goes while a connection keeps making its files there`(@TempDir tmp: Path) {
        repeat(200) { round ->
            val dir = Files.createDirectory(tmp.resolve("drivine-$round"))
            File(dir.toFile(), "memory.db").createNewFile()
            // The pool filling, as the hook runs: each connection it opens makes the file and its log again, until
            // there is no directory to make them in.
            val writer = thread {
                repeat(POOL) {
                    try {
                        for (name in listOf("memory.db", "memory.db-wal", "memory.db-shm")) File(dir.toFile(), name).createNewFile()
                    } catch (e: IOException) {
                        return@thread
                    }
                    Thread.sleep(1)
                }
            }
            Store.Scratch.at(dir).removeDir()
            writer.join(5_000)
            assertFalse(Files.exists(dir), "round $round left ${dir.toFile().list()?.toList()}")
        }
    }

    private companion object {
        /** Connections the pool opens: Hikari's default maximum. */
        const val POOL = 10
    }
}
