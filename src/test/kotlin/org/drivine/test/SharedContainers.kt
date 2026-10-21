package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * A container of [image] started for [SharedFalkorDb] or [SharedMemgraph], with the longer wait and
 * the second attempt a start on a busy machine needs: one that fails takes every class with it.
 */
internal fun sharedContainer(image: String, port: Int): GenericContainer<*> =
    GenericContainer(DockerImageName.parse(image))
        .withExposedPorts(port)
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
        .withStartupAttempts(2)
        .also { it.start() }

/**
 * Runs [use] with an auto-commit manager over Bolt to [container], and ends its connection after. It
 * is auto-commit because Memgraph refuses a storage mode switch while a transaction is open.
 */
internal fun <T> overBolt(
    container: GenericContainer<*>,
    type: DatabaseType,
    cypherDialect: CypherDialect,
    user: String,
    password: String,
    database: String?,
    use: (NonTransactionalPersistenceManager) -> T,
): T {
    val provider = Neo4jConnectionProvider(
        name = "shared-${type.value.lowercase()}", type = type,
        host = container.host, port = container.getMappedPort(7687),
        user = user, password = password, database = database,
        config = emptyMap(), subtypeRegistry = SubtypeRegistry(), cypherDialect = cypherDialect,
    )
    try {
        return use(NonTransactionalPersistenceManager(provider, database ?: type.value.lowercase(), type, SubtypeRegistry()))
    } finally {
        provider.end()
    }
}
