package org.drivine.sample

import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.drivine.sample.view.*
import org.drivine.sample.view.loadAll
import org.drivine.test.TestCleanup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A view that holds itself is queried by its generated DSL, which builds each level only when it is read. */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class StatelessOrgChartDslTest @Autowired constructor(
    private val graphObjectManager: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    @BeforeEach
    fun setupTestData() {
        TestCleanup.beforeEach(persistenceManager, "testMarker", "org-chart-test")
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (root:Organization {uuid: randomUUID(), name: 'Head Office', testMarker: 'org-chart-test'})
                CREATE (sales:Organization {uuid: randomUUID(), name: 'Sales', testMarker: 'org-chart-test'})
                CREATE (east:Organization {uuid: randomUUID(), name: 'Sales East', testMarker: 'org-chart-test'})
                CREATE (root)-[:RUNS]->(sales)
                CREATE (sales)-[:RUNS]->(east)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a view that holds itself loads by its DSL`() {
        val charts = graphObjectManager.loadAll<OrgChart> {
            where { organization.name eq "Head Office" }
        }

        val chart = charts.single()
        assertEquals(listOf("Sales"), chart.units.map { it.organization.name })
        assertEquals(listOf("Sales East"), chart.units.single().units.map { it.organization.name })
    }

    @Test
    fun `a where on a held level reads that level's targets`() {
        val charts = graphObjectManager.loadAll<OrgChart> {
            where { units.organization.name eq "Sales" }
        }

        assertEquals(listOf("Head Office"), charts.map { it.organization.name })
    }

    @Test
    fun `a where two levels down reads the targets of the targets`() {
        val charts = graphObjectManager.loadAll<OrgChart> {
            where { units.units.organization.name eq "Sales East" }
        }

        assertEquals(listOf("Head Office"), charts.map { it.organization.name })
    }

    @Test
    fun `a where three levels down is refused, as the DSL reaches it but the query does not`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            graphObjectManager.loadAll<OrgChart> {
                where { units.units.units.organization.name eq "Sales East" }
            }
        }
        assertContains(refusal.message!!, "Nested relationship 'units_units' not found")
    }

    @Test
    fun `each level of the DSL is at the alias its relationship gives it`() {
        assertEquals("units", OrgChartQueryDsl.INSTANCE.units.nodeAlias)
        assertEquals("units_units", OrgChartQueryDsl.INSTANCE.units.units.nodeAlias)
    }
}
