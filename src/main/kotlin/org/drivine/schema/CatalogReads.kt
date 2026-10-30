package org.drivine.schema

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Where what a statement is written with is read from a store's catalog: Oracle's labelled indexes,
 * and the record of lowercased copies. A read never runs on the caller's thread: not in the caller's
 * transaction, not among the statements of the read it writes, and not by a reader that may hold the
 * pool's last connection.
 */
internal object CatalogReads {

    private val threads = AtomicLong()

    /**
     * Threads that read catalogs, made as needed and ended when idle; none keeps the process alive,
     * and none holds the class loader of the code that happened to start it, which a redeployed
     * application would otherwise keep until the thread ends.
     */
    val BACKGROUND: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "drivine-catalog-${threads.incrementAndGet()}").apply {
            isDaemon = true
            contextClassLoader = null
        }
    }
}
