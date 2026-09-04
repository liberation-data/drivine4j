package org.drivine.connection

import org.assertj.core.api.Assertions.assertThat
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver

/**
 * [NativeDriverSource] is how maintenance code that speaks an engine's own client library
 * gets one: from the provider that built it, rather than by rebuilding it from properties
 * (a second pool, drifting config) or reaching into provider internals. Engines with no
 * such client do not implement it, so absence is a typed answer.
 */
class NativeDriverSourceTest {

    private fun neo4jProvider() = Neo4jConnectionProvider(
        name = "test",
        type = DatabaseType.NEO4J,
        host = "localhost",
        port = 7687,
        user = "neo4j",
        password = "unused",
        database = null,
        protocol = "bolt",
        config = emptyMap(),
    )

    @Test
    fun `a bolt provider hands out its own driver, not a fresh one`() {
        val provider = neo4jProvider()
        try {
            val driver = (provider as? NativeDriverSource<*>)?.nativeDriver
            assertThat(driver).isInstanceOf(Driver::class.java)
            /* The provider's instance, so callers share its pool and configuration —
             * a rebuilt driver would differ on each read. */
            assertThat(provider.nativeDriver).isSameAs(driver)
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a provider with no native client simply does not implement the capability`() {
        val provider = object : ConnectionProvider {
            override val name = "graph"
            override val type = DatabaseType.EMBABEL
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException()
            override fun end() = Unit
        }

        assertThat(provider as? NativeDriverSource<*>).isNull()
    }

    @Test
    fun `an in-process provider can supply a client of its own`() {
        val stand = object : Connection {
            override fun sessionId() = "in-process"
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList<Any>() as List<T>
            override fun startTransaction() = Unit
            override fun commitTransaction() = Unit
            override fun rollbackTransaction() = Unit
            override fun release(error: Throwable?) = Unit
        }
        /* Engines with no wire implement the client rather than dialling one; the capability
         * is what lets them answer at all. Modelled here with Connection standing in for a
         * driver type drivine4j does not depend on. */
        val provider = object : ConnectionProvider, NativeDriverSource<Connection> {
            override val name = "graph"
            override val type = DatabaseType.EMBABEL
            override val subtypeRegistry = null
            override val nativeDriver: Connection = stand
            override fun connect(): Connection = stand
            override fun end() = Unit
        }

        assertThat((provider as NativeDriverSource<*>).nativeDriver).isSameAs(stand)
    }
}
