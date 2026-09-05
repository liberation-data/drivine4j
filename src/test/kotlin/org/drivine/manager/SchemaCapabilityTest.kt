package org.drivine.manager

import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test

/**
 * Schema capability is a property of the engine's grammar, and callers that provision at startup
 * need it before issuing DDL. It was reachable only from [ConnectionProvider]; the managers are
 * what those callers actually hold, so it has to be answerable there — otherwise they resort to
 * matching [DatabaseType] against a set of engines they happen to know, which is wrong the moment
 * a new one appears.
 */
class SchemaCapabilityTest {

    private class StandInConnection : Connection {
        override fun sessionId(): String = "stand-in"
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList<Any>() as List<T>
        override fun startTransaction() = Unit
        override fun commitTransaction() = Unit
        override fun rollbackTransaction() = Unit
        override fun release(error: Throwable?) = Unit
    }

    private class StandInProvider(
        override val name: String,
        override val cypherDialect: CypherDialect,
    ) : ConnectionProvider {
        override val type: DatabaseType = DatabaseType.EMBABEL
        override val subtypeRegistry = null
        override fun connect(): Connection = StandInConnection()
        override fun end() = Unit
    }

    private fun managerFor(dialect: CypherDialect): PersistenceManager {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        registry.register(StandInProvider("graph", dialect))
        return PersistenceManagerFactory(registry, TransactionContextHolder(registry)).get("graph")
    }

    @Test
    fun `an engine whose grammar has schema DDL says so through the manager`() {
        assertThat(managerFor(CypherDialect.NEO4J_5).supportsSchemaManagement).isTrue
    }

    @Test
    fun `an engine with no schema DDL says so too, rather than being guessed at by type`() {
        /* OPEN_CYPHER resolves to UnsupportedSchemaGrammar, which throws on every operation */
        assertThat(managerFor(CypherDialect.OPEN_CYPHER).supportsSchemaManagement).isFalse
    }

    @Test
    fun `a transactional manager with no schema source cannot run DDL and admits it`() {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        registry.register(StandInProvider("graph", CypherDialect.NEO4J_5))
        val orphan = TransactionalPersistenceManager(
            TransactionContextHolder(registry),
            "graph",
            DatabaseType.EMBABEL,
            registry.subtypeRegistry,
            CypherDialect.NEO4J_5.grammar(),
        )
        assertThat(orphan.supportsSchemaManagement).isFalse
    }
}
