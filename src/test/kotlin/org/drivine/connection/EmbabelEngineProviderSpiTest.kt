package org.drivine.connection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.DrivineException
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test

/**
 * The seam for an engine with no wire ([DatabaseType.buildableFromProperties]
 * false): it supplies its own [ConnectionProvider] instead of connection
 * properties. The registry accepts it, the persistence-manager stack builds
 * over it generically, and the property path fails with directions rather
 * than a puzzle.
 */
class EmbabelEngineProviderSpiTest {

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
        override val type: DatabaseType = DatabaseType.EMBABEL
        override val subtypeRegistry: org.drivine.mapper.SubtypeRegistry? = null
        override fun connect(): Connection = FakeInProcessConnection()
        override fun end() = Unit
    }

    @Test
    fun `a wireless datasource entry is left for the application's provider`() {
        val registry = DatabaseRegistry(
            DataSourceMap(
                mutableMapOf(
                    "graph" to ConnectionProperties(type = DatabaseType.EMBABEL),
                ),
            ),
        )
        assertThat(registry.connectionProvider("graph")).isNull()

        registry.register(FakeInProcessProvider("graph"))
        assertThat(registry.connectionProvider("graph")).isNotNull
        assertThat(registry.connectionProvider("graph")!!.type).isEqualTo(DatabaseType.EMBABEL)
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
        assertThat(manager.type).isEqualTo(DatabaseType.EMBABEL)
    }

    @Test
    fun `connection properties cannot build an EMBABEL provider, and say so`() {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        assertThatThrownBy {
            registry.builder()
                .withType(DatabaseType.EMBABEL)
                .register("graph")
        }.isInstanceOf(DrivineException::class.java)
            .hasMessageContaining("EMBABEL engines supply their own ConnectionProvider")
    }

    @Test
    fun `a wire engine still requires a host`() {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        assertThatThrownBy {
            registry.builder().withType(DatabaseType.NEO4J).register("graph")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Host config is required")
    }
}
