package org.drivine.connection

import com.falkordb.FalkorDB
import com.falkordb.Driver
import org.drivine.mapper.FalkorDbResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect

class FalkorDbConnectionProvider(
    override val name: String,
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val graphName: String,
    private val transactionMode: FalkorDbTransactionMode = FalkorDbTransactionMode.WARN,
    override val subtypeRegistry: SubtypeRegistry? = null,
    override val cypherDialect: CypherDialect = CypherDialect.OPEN_CYPHER,
) : ConnectionProvider {

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
}