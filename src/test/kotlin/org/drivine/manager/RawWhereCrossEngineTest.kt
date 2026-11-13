package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import sample.graphproperty.DollarScoredNode
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * A raw `whereClause` on a SQL store, a property of the root written as Cypher writes it: read as text, a temporal value
 * as the ISO 8601 text layout 2's tag holds (drivine-cypher-spec#161), as layout 1 held it, and a property whose name
 * begins with `$` where layout 2 holds it, `$$…`.
 */
private fun verifyRawWhere(pm: NonTransactionalPersistenceManager) {
    val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    gom.save(TemporalsNode.at("base"))
    gom.save(TemporalsNode.at("later", 10).copy(date = LocalDate.of(2026, 6, 4)))
    assertEquals(listOf("base"), gom.loadAll(TemporalsNode::class.java, "n.date = '2026-06-03'").map { it.id }, "a date equal to its text")
    assertEquals(listOf("later"), gom.loadAll(TemporalsNode::class.java, "n.date > '2026-06-03'").map { it.id }, "a date after a text")
    assertEquals(2L, gom.count(TemporalsNode::class.java, "n.instant >= '2026-06-03T02:25:23.449563Z'"), "a moment's text")
    assertEquals(1, gom.deleteAll(TemporalsNode::class.java, "n.date = '2026-06-04'"), "a delete of a date equal to its text")

    gom.saveAll(listOf(DollarScoredNode("a", "/install", 5), DollarScoredNode("b", "/agents", 3)))
    assertEquals(listOf("b"), gom.loadAll(DollarScoredNode::class.java, "n.`\$key` = 'b'").map { it.key }, "a property under a dollar key")
}

@PostgresTests
class RawWherePostgresTest {
    private val pm = PostgresTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a raw whereClause reads a temporal property as its text on Postgres`() {
        PostgresTestDatabase.clear(pm)
        verifyRawWhere(pm)
    }
}

@OracleTests
class RawWhereOracleTest {
    private val pm = OracleTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a raw whereClause reads a temporal property as its text on Oracle`() {
        OracleTestDatabase.clear(pm)
        verifyRawWhere(pm)
    }
}

class RawWhereSqliteTest {
    private val pm = SqliteTestDatabase.graphManager("contracts", SubtypeRegistry())

    @Test
    fun `a raw whereClause reads a temporal property as its text on SQLite`() {
        SqliteTestDatabase.clear(pm)
        verifyRawWhere(pm)
    }
}
