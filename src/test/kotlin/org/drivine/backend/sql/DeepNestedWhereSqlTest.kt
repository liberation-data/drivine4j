package org.drivine.backend.sql

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSelect
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSelect
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSelect
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.UnderscoredRegion
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.junit.jupiter.api.Test
import sample.mapped.view.LocationHierarchy
import sample.mapped.view.TopLevelWithNestedGraphViews
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A `where` on a level of nested views below the second is an `EXISTS` for each level, one within
 * the other, on every SQL store, written without a database.
 */
class DeepNestedWhereSqlTest {

    private fun selects(view: Class<*>): Map<String, SqlSelect> = mapOf(
        "Postgres" to PostgresSelect(PostgresGraph("g"), view),
        "Oracle" to OracleSelect(OracleGraph("g"), view),
        "SQLite" to SqliteSelect(SqliteGraph("g"), view),
    )

    private fun eq(path: String, value: Any) = PropertyCondition(path, ComparisonOperator.EQUALS, value)

    private fun assertThreeLevels(view: Class<*>, path: String, vararg types: String) {
        selects(view).forEach { (store, select) ->
            val sql = assertNotNull(select.predicate(listOf(eq(path, "Paris"))), store)
            assertEquals(3, Regex("EXISTS").findAll(sql).count(), "$store: $sql")
            types.forEach { assertTrue("'$it'" in sql, "$store: $sql") }
            assertEquals(listOf<Any?>("Paris"), select.bindings().values.toList(), store)
        }
    }

    @Test
    fun `a where three levels down is an EXISTS for each level`() =
        assertThreeLevels(LocationHierarchy::class.java, "subLocations_subLocations_subLocations.name", "HAS_LOCATION")

    @Test
    fun `a where three levels down reads each level by the relationships of its own view`() =
        assertThreeLevels(TopLevelWithNestedGraphViews::class.java, "issues_raisedBy_employer.name", "HAS_ISSUES", "RAISED_BY", "WORKS_FOR")

    @Test
    fun `a where below a relationship whose name holds an underscore is read one name at a time`() =
        assertThreeLevels(UnderscoredRegion::class.java, "sub_regions_sub_regions_sub_regions.name", "HAS_LOCATION")
}
