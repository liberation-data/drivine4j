package org.drivine.connection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.DrivineException
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test

/**
 * The IN_PROCESS seam: an engine living inside the application supplies its
 * own [ConnectionProvider] instead of connection properties. The registry
 * accepts it, the persistence-manager stack builds over it generically, and
 * the property path fails with directions rather than a puzzle.
 */
class InProcessProviderSpiTest {

    private class FakeInProcessConnection : Connection {
        override fun sessionId(): String = "in-process"

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> =
            listOf("mapped-by-the-engine") as List<T>

        override fun startTransaction() = Unit
        override fun commitTransaction() = Unit
        override fun rollbackTransaction() = Unit
        override fun release(error: Throwable?) = Unit
    }

    private class FakeInProcessProvider(override val name: String) : ConnectionProvider {
        override val type: DatabaseType = DatabaseType.IN_PROCESS
        override val subtypeRegistry: org.drivine.mapper.SubtypeRegistry? = null
        override fun connect(): Connection = FakeInProcessConnection()
        override fun end() = Unit
    }

    @Test
    fun `an IN_PROCESS datasource entry is left for the application's provider`() {
        val registry = DatabaseRegistry(
            DataSourceMap(
                mutableMapOf(
                    "graph" to ConnectionProperties(type = DatabaseType.IN_PROCESS, host = "unused"),
                ),
            ),
        )
        assertThat(registry.connectionProvider("graph")).isNull()

        registry.register(FakeInProcessProvider("graph"))
        assertThat(registry.connectionProvider("graph")).isNotNull
        assertThat(registry.connectionProvider("graph")!!.type).isEqualTo(DatabaseType.IN_PROCESS)
    }

    @Test
    fun `the persistence-manager stack builds generically over a registered provider`() {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        registry.register(FakeInProcessProvider("graph"))
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))

        val manager = factory.get("graph")
        val rows: List<String> = manager.query(
            QuerySpecification.withStatement("RETURN 1").transform(String::class.java),
        )
        assertThat(rows).containsExactly("mapped-by-the-engine")
        assertThat(manager.type).isEqualTo(DatabaseType.IN_PROCESS)
    }

    @Test
    fun `connection properties cannot build an IN_PROCESS provider, and say so`() {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        assertThatThrownBy {
            registry.builder()
                .withType(DatabaseType.IN_PROCESS)
                .host("irrelevant")
                .register("graph")
        }.isInstanceOf(DrivineException::class.java)
            .hasMessageContaining("register it as a bean")
    }
}
