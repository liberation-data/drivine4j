package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.schema.VectorIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import sample.graphproperty.DollarEmbeddedNode
import kotlin.test.assertEquals

/**
 * A vector search over an embedding whose property name begins with `$`, on each SQL store: layout 2 holds the
 * property as `$$embedding` (drivine-cypher-spec#161), and the search reads it there, by measuring every node
 * before the index is made and through the index after. The node comes back with its embedding.
 */
private fun verifyDollarKey(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) {
    val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)
    val spec = VectorIndexSpec("DollarEmbedded", "\$embedding", 4)
    runCatching { pm.indexes.drop(spec) }
    gom.save(DollarEmbeddedNode("d1", listOf(1.0f, 0.0f, 0.0f, 0.0f)))
    gom.save(DollarEmbeddedNode("d2", listOf(0.0f, 1.0f, 0.0f, 0.0f)))
    fun nearest() = gom.loadNearest(DollarEmbeddedNode::class.java, listOf(0.9f, 0.1f, 0.0f, 0.0f), topK = 2)
    try {
        val measured = nearest()
        assertEquals(listOf("d1", "d2"), measured.map { it.value.id }, "measured over every node")
        assertEquals(listOf(1.0f, 0.0f, 0.0f, 0.0f), measured.first().value.embedding)
        pm.indexes.ensure(spec)
        assertEquals(listOf("d1", "d2"), nearest().map { it.value.id }, "through the index")
    } finally {
        runCatching { pm.indexes.drop(spec) }
    }
}

@PostgresTests
class DollarKeyVectorSearchPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @Test
    fun `a search reads an embedding under a dollar key on Postgres`() {
        PostgresTestDatabase.clear(pm)
        verifyDollarKey(pm, registry)
    }
}

@OracleTests
class DollarKeyVectorSearchOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @Test
    fun `a search reads an embedding under a dollar key on Oracle`() {
        OracleTestDatabase.clear(pm)
        verifyDollarKey(pm, registry)
    }
}

class DollarKeyVectorSearchSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @Test
    fun `a search reads an embedding under a dollar key on SQLite`() {
        SqliteTestDatabase.clear(pm)
        verifyDollarKey(pm, registry)
    }
}
