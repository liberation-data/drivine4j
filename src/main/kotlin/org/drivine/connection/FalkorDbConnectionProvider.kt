package org.drivine.connection

import com.falkordb.FalkorDB
import com.falkordb.Driver
import org.drivine.mapper.FalkorDbResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.SchemaStatement
import org.slf4j.LoggerFactory
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.util.SafeEncoder

class FalkorDbConnectionProvider(
    override val name: String,
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val graphName: String,
    private val transactionMode: FalkorDbTransactionMode = FalkorDbTransactionMode.WARN,
    override val subtypeRegistry: SubtypeRegistry? = null,
    override val cypherDialect: CypherDialect = CypherDialect.FALKORDB,
) : ConnectionProvider, NativeSchemaCommandExecutor {

    private val logger = LoggerFactory.getLogger(FalkorDbConnectionProvider::class.java)

    override val type: DatabaseType = DatabaseType.FALKORDB

    private val driver: Driver = if (password != null) {
        FalkorDB.driver(host, port, "default", password)
    } else {
        FalkorDB.driver(host, port)
    }

    override fun connect(): Connection {
        val graph = driver.graph(graphName)
        return FalkorDbConnection(graph, FalkorDbResultMapper(subtypeRegistry), transactionMode)
    }

    override fun end() {
        driver.close()
    }

    /**
     * Executes a native Redis command against the FalkorDB server — used for schema operations
     * that are not expressible as Cypher (`GRAPH.CONSTRAINT CREATE` / `DROP`).
     *
     * The command name is the first element of [args]; [SchemaStatement.Native.GRAPH_NAME]
     * placeholders are substituted with this provider's graph name.
     */
    override fun executeNativeSchemaCommand(args: List<String>): Any? {
        require(args.isNotEmpty()) { "Native schema command requires at least a command name" }
        val substituted = args.map { if (it == SchemaStatement.Native.GRAPH_NAME) graphName else it }
        val command = ProtocolCommand { SafeEncoder.encode(substituted.first()) }
        val commandArgs = substituted.drop(1).toTypedArray()

        logger.debug("FalkorDB native schema command: {}", substituted.joinToString(" "))
        driver.connection.use { jedis ->
            val response = jedis.sendCommand(command, *commandArgs)
            return decodeReply(response)
        }
    }

    private fun decodeReply(reply: Any?): Any? = when (reply) {
        is ByteArray -> String(reply, Charsets.UTF_8)
        is List<*> -> reply.map { decodeReply(it) }
        else -> reply
    }
}