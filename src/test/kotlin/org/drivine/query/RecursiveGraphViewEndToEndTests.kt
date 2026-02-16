package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.mapped.fragment.Location
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person
import sample.mapped.view.LocationHierarchy
import sample.mapped.view.OrgPersonView
import sample.mapped.view.PersonOrgView
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for recursive @GraphRelationship support.
 * Tests the complete flow: create hierarchical data in Neo4j -> load via recursive @GraphView -> verify deserialization.
 *
 * Uses real location data from locations.json (continent → region → country) to test
 * recursive self-referential @GraphView models against a live Neo4j testcontainer.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class RecursiveGraphViewEndToEndTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'recursive-test' DETACH DELETE n")
        )

        // Load locations.json and create the hierarchy in Neo4j
        val mapper = ObjectMapper()
        val locationsJson: Map<String, Map<String, Map<String, Any>>> =
            mapper.readValue(javaClass.classLoader.getResourceAsStream("locations.json")!!)

        val statements = mutableListOf<String>()

        locationsJson.forEach { (continent, regions) ->
            val continentVar = toVar(continent)
            statements.add(
                """CREATE ($continentVar:Location {
                    uuid: '${UUID.randomUUID()}',
                    name: '$continent',
                    type: 'continent',
                    createdBy: 'recursive-test'
                })""".trimIndent()
            )

            regions.forEach { (region, countries) ->
                val regionVar = toVar(region)
                statements.add(
                    """CREATE ($regionVar:Location {
                        uuid: '${UUID.randomUUID()}',
                        name: '${escapeCypher(region)}',
                        type: 'region',
                        createdBy: 'recursive-test'
                    })""".trimIndent()
                )
                statements.add("CREATE ($continentVar)-[:HAS_LOCATION]->($regionVar)")

                countries.keys.forEach { country ->
                    val countryVar = toVar(country)
                    statements.add(
                        """CREATE ($countryVar:Location {
                            uuid: '${UUID.randomUUID()}',
                            name: '${escapeCypher(country)}',
                            type: 'country',
                            createdBy: 'recursive-test'
                        })""".trimIndent()
                    )
                    statements.add("CREATE ($regionVar)-[:HAS_LOCATION]->($countryVar)")
                }
            }
        }

        persistenceManager.execute(
            QuerySpecification.withStatement(statements.joinToString("\n"))
        )

        // Create chain cycle test data: Person → Org → Person → Org
        val aliceUuid = UUID.randomUUID()
        val bobUuid = UUID.randomUUID()
        val carolUuid = UUID.randomUUID()
        val acmeUuid = UUID.randomUUID()
        val betaUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (alice:Person:Mapped {
                    uuid: '$aliceUuid',
                    name: 'Alice',
                    bio: 'Engineer',
                    createdBy: 'recursive-test'
                })
                CREATE (bob:Person:Mapped {
                    uuid: '$bobUuid',
                    name: 'Bob',
                    bio: 'Designer',
                    createdBy: 'recursive-test'
                })
                CREATE (carol:Person:Mapped {
                    uuid: '$carolUuid',
                    name: 'Carol',
                    bio: 'Manager',
                    createdBy: 'recursive-test'
                })
                CREATE (acme:Organization:Mapped {
                    uuid: '$acmeUuid',
                    name: 'Acme Corp',
                    createdBy: 'recursive-test'
                })
                CREATE (beta:Organization:Mapped {
                    uuid: '$betaUuid',
                    name: 'Beta Inc',
                    createdBy: 'recursive-test'
                })
                CREATE (alice)-[:WORKS_FOR]->(acme)
                CREATE (acme)-[:EMPLOYS]->(bob)
                CREATE (bob)-[:WORKS_FOR]->(beta)
                CREATE (beta)-[:EMPLOYS]->(carol)
                """.trimIndent()
            )
        )
    }

    // =========================================================================
    // LOCATION HIERARCHY TESTS (self-referential recursive)
    // =========================================================================

    @Test
    fun `should load full 3-level location hierarchy for a continent`() {
        val results = graphObjectManager.loadAll(
            LocationHierarchy::class.java,
            "location.type = 'continent' AND location.name = 'Europe'"
        )

        assertEquals(1, results.size)
        val europe = results[0]
        assertEquals("Europe", europe.location.name)
        assertEquals("continent", europe.location.type)

        // Europe has 4 regions
        assertEquals(4, europe.subLocations.size)
        val regionNames = europe.subLocations.map { it.location.name }.sorted()
        assertEquals(
            listOf("Eastern Europe", "Northern Europe", "Southern Europe", "Western Europe"),
            regionNames
        )

        // Each region should have countries
        val westernEurope = europe.subLocations.find { it.location.name == "Western Europe" }!!
        assertEquals("region", westernEurope.location.type)
        assertTrue(westernEurope.subLocations.isNotEmpty())

        val countryNames = westernEurope.subLocations.map { it.location.name }.sorted()
        assertTrue(countryNames.contains("France"))
        assertTrue(countryNames.contains("Germany"))
        assertTrue(countryNames.contains("Switzerland"))

        // Countries (leaf nodes) should have empty subLocations
        val france = westernEurope.subLocations.find { it.location.name == "France" }!!
        assertEquals("country", france.location.type)
        assertTrue(france.subLocations.isEmpty(), "Leaf country should have empty subLocations")
    }

    @Test
    fun `should load leaf location with empty subLocations`() {
        val results = graphObjectManager.loadAll(
            LocationHierarchy::class.java,
            "location.name = 'Canada'"
        )

        assertEquals(1, results.size)
        val canada = results[0]
        assertEquals("Canada", canada.location.name)
        assertEquals("country", canada.location.type)
        assertTrue(canada.subLocations.isEmpty(), "Leaf country should have no sub-locations")
    }

    @Test
    fun `should load all continents with populated hierarchies`() {
        val results = graphObjectManager.loadAll(
            LocationHierarchy::class.java,
            "location.type = 'continent'"
        )

        assertEquals(5, results.size)
        val continentNames = results.map { it.location.name }.sorted()
        assertEquals(listOf("Africa", "America", "Asia", "Europe", "Oceania"), continentNames)

        // Each continent should have regions with countries
        results.forEach { continent ->
            assertTrue(continent.subLocations.isNotEmpty(),
                "${continent.location.name} should have regions")
            continent.subLocations.forEach { region ->
                assertEquals("region", region.location.type,
                    "Children of ${continent.location.name} should be regions")
                assertTrue(region.subLocations.isNotEmpty(),
                    "${region.location.name} should have countries")
                region.subLocations.forEach { country ->
                    assertEquals("country", country.location.type,
                        "Children of ${region.location.name} should be countries")
                    assertTrue(country.subLocations.isEmpty(),
                        "${country.location.name} should have no children")
                }
            }
        }
    }

    @Test
    fun `should load hierarchy with maxDepth 1 using LocationHierarchyShallow`() {
        val results = graphObjectManager.loadAll(
            LocationHierarchyShallow::class.java,
            "location.type = 'continent' AND location.name = 'Africa'"
        )

        assertEquals(1, results.size)
        val africa = results[0]
        assertEquals("Africa", africa.location.name)

        // Should have regions (depth 1)
        assertTrue(africa.subLocations.isNotEmpty(), "Should have regions at depth 1")
        val regionNames = africa.subLocations.map { it.location.name }.sorted()
        assertTrue(regionNames.contains("Northern Africa"))
        assertTrue(regionNames.contains("Western Africa"))

        // Regions should NOT have countries (maxDepth=1 means only 1 level expanded)
        africa.subLocations.forEach { region ->
            assertTrue(region.subLocations.isEmpty(),
                "${region.location.name} should have empty subLocations with maxDepth=1")
        }
    }

    @Test
    fun `should handle location with no children gracefully`() {
        val results = graphObjectManager.loadAll(
            LocationHierarchy::class.java,
            "location.name = 'Japan'"
        )

        assertEquals(1, results.size)
        val japan = results[0]
        assertEquals("Japan", japan.location.name)
        assertNotNull(japan.subLocations, "subLocations should not be null")
        assertTrue(japan.subLocations.isEmpty(), "Japan has no sub-locations")
    }

    // =========================================================================
    // CHAIN CYCLE TESTS (Person → Org → Person → Org)
    // =========================================================================

    @Test
    fun `should load chain cycle PersonOrgView with nested employer and employees`() {
        val results = graphObjectManager.loadAll(
            PersonOrgView::class.java,
            "person.name = 'Alice'"
        )

        assertEquals(1, results.size)
        val alice = results[0]
        assertEquals("Alice", alice.person.name)

        // Alice works for Acme Corp (first visit to OrgPersonView)
        assertNotNull(alice.employer, "Alice should have an employer")
        assertEquals("Acme Corp", alice.employer!!.org.name)

        // Acme Corp employs Bob (employees maxDepth=2, second visit to PersonOrgView allowed)
        assertTrue(alice.employer!!.employees.isNotEmpty(), "Acme should have employees")
        val bob = alice.employer!!.employees.find { it.person.name == "Bob" }
        assertNotNull(bob, "Acme should employ Bob")

        // Bob's employer is null — chain cycle terminates here because:
        // employer has maxDepth=1 (default), OrgPersonView already visited once → terminate
        assertNull(bob.employer,
            "Bob's employer should be null (chain cycle terminated by visit count)")
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private var varCounter = 0

    private fun toVar(name: String): String {
        varCounter++
        return "loc_${varCounter}"
    }

    private fun escapeCypher(value: String): String {
        return value.replace("'", "\\'")
    }
}
