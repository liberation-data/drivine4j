package org.drivine.test

import org.testcontainers.containers.GenericContainer
import redis.clients.jedis.Jedis

/**
 * One FalkorDB for the test JVM, which the cross-engine test classes share in place of a container
 * of their own each: see [SharedNeo4j]. It is the image the classes started for themselves,
 * `falkordb/falkordb:latest` as `gradle/test-images.txt` pins it. [FalkorDbTestContainer] is the one
 * the Spring tests use.
 */
object SharedFalkorDb {

    private val container: GenericContainer<*> by lazy { sharedContainer("falkordb/falkordb:latest", 6379) }

    /**
     * The shared container, emptied for the class that asks: each class calls this once, from its
     * companion, before anything of its own runs. A FalkorDB graph is a Redis key, its indexes with
     * it, so `FLUSHALL` removes every graph an earlier class made, whatever it named it.
     */
    @JvmStatic
    fun forClass(): GenericContainer<*> {
        Jedis(container.host, container.getMappedPort(6379)).use { it.flushAll() }
        return container
    }
}
