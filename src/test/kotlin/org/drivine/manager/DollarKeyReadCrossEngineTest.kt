package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SqlCypherFixtures
import org.junit.jupiter.api.Test
import sample.graphproperty.DollarScoredNode
import kotlin.test.assertEquals

/** The query object of [DollarScoredNode], by the names its properties are stored under. */
private class DollarScoredQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val page = StringPropertyReference(nodeAlias, "page")
    val score = PropertyReference<Long>(nodeAlias, "\$score")
}

/**
 * The reads a SQL store writes of a property itself, and not through the module's fragments, of a property whose name
 * begins with `$`, which layout 2 holds as `$$…` (drivine-cypher-spec#161): a save writes the id there, as Cypher reads it,
 * and a load by that id and a grouped read's sum and mean of a number stored so read them there.
 */
private fun verifyDollarKeys(pm: NonTransactionalPersistenceManager) {
    val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    gom.saveAll(
        listOf(
            DollarScoredNode("a", "/install", 5),
            DollarScoredNode("b", "/install", 3),
            DollarScoredNode("c", "/agents", 4),
        )
    )
    val cypher = "MATCH (n:DollarScored) WHERE n.`\$key` = 'b' RETURN n.`\$score`"
    assertEquals(listOf(3L), SqlCypherFixtures.over(pm).query(QuerySpecification.withStatement(cypher).transform(Long::class.java)), "Cypher reads the id and the number where they are held")
    assertEquals(DollarScoredNode("b", "/install", 3), gom.load("b", DollarScoredNode::class.java), "a load by an id under a dollar key")
    val rows = gom.groups.load(DollarScoredNode::class.java, DollarScoredQueryDsl()) {
        val page = key(query.page)
        val total = sum(query.score)
        val mean = avg(query.score)
        rows { listOf(it[page], it[total], it[mean]) }
    }
    assertEquals(listOf(listOf("/agents", 4L, 4.0), listOf("/install", 8L, 4.0)), rows, "a sum and a mean of a number under a dollar key")
}

@PostgresTests
class DollarKeyReadPostgresTest {
    private val pm = PostgresTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a load by id and a sum read a property under a dollar key on Postgres`() {
        PostgresTestDatabase.clear(pm)
        verifyDollarKeys(pm)
    }
}

@OracleTests
class DollarKeyReadOracleTest {
    private val pm = OracleTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a load by id and a sum read a property under a dollar key on Oracle`() {
        OracleTestDatabase.clear(pm)
        verifyDollarKeys(pm)
    }
}

class DollarKeyReadSqliteTest {
    private val pm = SqliteTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a load by id and a sum read a property under a dollar key on SQLite`() {
        SqliteTestDatabase.clear(pm)
        verifyDollarKeys(pm)
    }
}
