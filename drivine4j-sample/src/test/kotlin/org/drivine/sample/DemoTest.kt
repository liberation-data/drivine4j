package org.drivine.sample

import org.drivine.manager.CascadeType
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.*
import org.drivine.sample.view.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive demo showing off Drivine4j features:
 *
 * 1. Loading collections of GraphViews with filtering
 * 2. RelationshipFragment (properties on edges)
 * 3. Interface delegation (mix-ins)
 * 4. CASCADE policies for safe deletion
 * 5. Type-safe query DSL with code generation
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class DemoTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    private lateinit var acmeCorpId: UUID
    private lateinit var startupIncId: UUID
    private lateinit var alice: UUID
    private lateinit var bob: UUID
    private lateinit var carol: UUID

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'demo-test' DETACH DELETE n")
        )

        acmeCorpId = UUID.randomUUID()
        startupIncId = UUID.randomUUID()
        alice = UUID.randomUUID()
        bob = UUID.randomUUID()
        carol = UUID.randomUUID()

        // Create organizations
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (acme:Organization:Mapped {
                    uuid: '$acmeCorpId',
                    name: 'Acme Corp',
                    createdBy: 'demo-test'
                })
                CREATE (startup:Organization:Mapped {
                    uuid: '$startupIncId',
                    name: 'Startup Inc',
                    createdBy: 'demo-test'
                })
                """.trimIndent()
            )
        )

        // Create people with employment history (RelationshipFragment properties!)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (acme:Organization {uuid: '$acmeCorpId'})
                MATCH (startup:Organization {uuid: '$startupIncId'})

                CREATE (alice:Person:Mapped {
                    uuid: '$alice',
                    name: 'Alice Engineer',
                    bio: 'Senior Backend Developer',
                    createdBy: 'demo-test'
                })
                CREATE (alice)-[:WORKS_FOR {
                    startDate: date('2023-01-15'),
                    role: 'Senior Engineer'
                }]->(acme)

                CREATE (bob:Person:Mapped {
                    uuid: '$bob',
                    name: 'Bob Designer',
                    bio: 'UX Lead',
                    createdBy: 'demo-test'
                })
                CREATE (bob)-[:WORKS_FOR {
                    startDate: date('2022-06-01'),
                    role: 'Lead Designer'
                }]->(acme)

                CREATE (carol:Person:Mapped {
                    uuid: '$carol',
                    name: 'Carol Founder',
                    bio: 'CEO and Founder',
                    createdBy: 'demo-test'
                })
                CREATE (carol)-[:WORKS_FOR {
                    startDate: date('2021-03-10'),
                    role: 'CEO'
                }]->(startup)
                """.trimIndent()
            )
        )
    }

    // ============================================================================
    // DEMO 1: Loading Collections with Type-Safe Filtering
    // ============================================================================

    @Test
    fun `demo - load collection of people and verify relationships`() {
        // Load all PersonContext views - this gives you collections!
        val allPeople = graphObjectManager.loadAll<PersonContext> { }

        // We get a collection of fully hydrated GraphViews
        assertEquals(3, allPeople.size)

        // Filter in Kotlin to find Acme employees
        val acmeEmployees = allPeople.filter { ctx ->
            ctx.worksFor.any { it.name == "Acme Corp" }
        }
        assertEquals(2, acmeEmployees.size)

        // Each person has their organization relationships loaded
        val alice = acmeEmployees.find { it.person.name == "Alice Engineer" }
        assertNotNull(alice)
        assertEquals("Senior Backend Developer", alice.person.bio)
        assertEquals(1, alice.worksFor.size)
    }

    @Test
    fun `demo - filter collection by person bio using CONTAINS`() {
        // Load people whose bio contains "Lead"
        val leads = graphObjectManager.loadAll<PersonContext> {
            where {
                query.person.bio contains "Lead"
            }
        }

        assertEquals(1, leads.size)
        assertEquals("Bob Designer", leads[0].person.name)
        assertEquals("UX Lead", leads[0].person.bio)
    }

    @Test
    fun `demo - filter by null or absent properties`() {
        // First, create a person without a bio to test null filtering
        val personWithoutBio = UUID.randomUUID()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (p:Person:Mapped {
                    uuid: '$personWithoutBio',
                    name: 'Dave Minimalist',
                    createdBy: 'demo-test'
                })
                CREATE (p)-[:WORKS_FOR]->(:Organization:Mapped {
                    uuid: '${UUID.randomUUID()}',
                    name: 'Stealth Startup',
                    createdBy: 'demo-test'
                })
                """.trimIndent()
            )
        )

        // Query for people where bio IS NULL
        val peopleWithoutBio = graphObjectManager.loadAll<PersonContext> {
            where {
                query.person.bio.isNull()
            }
        }

        assertEquals(1, peopleWithoutBio.size)
        assertEquals("Dave Minimalist", peopleWithoutBio[0].person.name)
        assertNull(peopleWithoutBio[0].person.bio)

        // Query for people where bio IS NOT NULL
        val peopleWithBio = graphObjectManager.loadAll<PersonContext> {
            where {
                query.person.bio.isNotNull()
            }
        }

        assertEquals(3, peopleWithBio.size)
        assertTrue(peopleWithBio.all { it.person.bio != null })
    }

    // ============================================================================
    // DEMO 2: RelationshipFragment - Properties on Edges
    // ============================================================================

    @Test
    fun `demo - relationship properties with RelationshipFragment`() {
        // PersonCareer uses WorkHistory - a RelationshipFragment
        // This captures: startDate, role (edge properties) + target Organization (node)
        val aliceCareer = graphObjectManager.load(alice.toString(), PersonCareer::class.java)

        assertNotNull(aliceCareer)
        assertEquals("Alice Engineer", aliceCareer.person.name)

        // Access relationship properties!
        val employment = aliceCareer.employmentHistory[0]
        assertEquals(LocalDate.of(2023, 1, 15), employment.startDate)
        assertEquals("Senior Engineer", employment.role)
        assertEquals("Acme Corp", employment.target.name)
    }

    @Test
    fun `demo - load all careers and verify relationship properties`() {
        // Load all careers and verify RelationshipFragment data is present
        val allCareers = graphObjectManager.loadAll<PersonCareer> { }

        assertEquals(3, allCareers.size)

        // Find Alice and verify her relationship properties
        val alice = allCareers.find { it.person.name == "Alice Engineer" }!!
        assertEquals("Senior Engineer", alice.employmentHistory[0].role)
        assertEquals(LocalDate.of(2023, 1, 15), alice.employmentHistory[0].startDate)
    }

    // ============================================================================
    // DEMO 3: Interface Delegation (Mix-ins)
    // ============================================================================

    @Test
    fun `demo - interface delegation for polymorphic handling`() {
        // RaisedAndAssignedIssue implements Issue interface via delegation
        val issueId = UUID.randomUUID()
        val personId = UUID.randomUUID()

        // Create test issue
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (i:Issue {
                    uuid: '$issueId',
                    id: 999,
                    title: 'Bug in login',
                    body: 'Users cannot log in',
                    state: 'open',
                    locked: false,
                    createdBy: 'demo-test'
                })
                CREATE (p:Person:Mapped {
                    uuid: '$personId',
                    name: 'Dave Tester',
                    bio: 'QA Engineer',
                    createdBy: 'demo-test'
                })
                CREATE (i)-[:ASSIGNED_TO]->(p)
                CREATE (i)-[:RAISED_BY]->(p)
                """.trimIndent()
            )
        )

        val raisedIssue = graphObjectManager.load(issueId.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(raisedIssue)

        // RaisedAndAssignedIssue IS an Issue (via delegation)
        // This means you can pass it to functions expecting Issue interface
        processIssue(raisedIssue)

        // You still get all the GraphView benefits
        assertEquals("Dave Tester", raisedIssue.assignedTo[0].name)
        assertEquals("Dave Tester", raisedIssue.raisedBy.person.name)
    }

    // Helper to demonstrate polymorphism
    private fun processIssue(issue: Issue) {
        // Works with any Issue implementation!
        println("Processing issue: ${issue.title} (state: ${issue.state})")
        assertTrue(issue.title?.contains("Bug") == true)
    }

    // ============================================================================
    // DEMO 4: CASCADE Policies - Safe Deletion
    // ============================================================================

    @Test
    fun `demo - CASCADE NONE - default behavior keeps orphaned nodes`() {
        // Load Carol's career (CEO of Startup Inc)
        val carolCareer = graphObjectManager.load(carol.toString(), PersonCareer::class.java)!!

        // Remove the employment relationship by setting empty list
        val updated = carolCareer.copy(employmentHistory = emptyList())

        // Save with CASCADE NONE (default) - only removes relationship
        graphObjectManager.save(updated, CascadeType.NONE)

        // Carol still exists
        val carolAfter = graphObjectManager.load(carol.toString(), PersonCareer::class.java)
        assertNotNull(carolAfter)
        assertEquals(0, carolAfter.employmentHistory.size)

        // Startup Inc STILL EXISTS (orphaned but not deleted)
        val startupStillExists = graphObjectManager.load(startupIncId.toString(), Organization::class.java)
        assertNotNull(startupStillExists)
    }

    @Test
    fun `demo - CASCADE DELETE_ORPHAN - removes only orphaned nodes`() {
        val aliceId = UUID.randomUUID()
        val soloOrgId = UUID.randomUUID()

        // Create a person with exclusive relationship to an org (no one else works there)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (p:Person:Mapped {
                    uuid: '$aliceId',
                    name: 'Solo Alice',
                    bio: 'Only employee',
                    createdBy: 'demo-test'
                })
                CREATE (o:Organization:Mapped {
                    uuid: '$soloOrgId',
                    name: 'Solo Org',
                    createdBy: 'demo-test'
                })
                CREATE (p)-[:WORKS_FOR {
                    startDate: date('2024-01-01'),
                    role: 'Everything'
                }]->(o)
                """.trimIndent()
            )
        )

        // Load and remove the relationship
        val career = graphObjectManager.load(aliceId.toString(), PersonCareer::class.java)!!
        val updated = career.copy(employmentHistory = emptyList())

        // Save with DELETE_ORPHAN - deletes org since no other relationships exist
        graphObjectManager.save(updated, CascadeType.DELETE_ORPHAN)

        // Solo Org is GONE (was orphaned)
        val orgExists = graphObjectManager.load(soloOrgId.toString(), Organization::class.java)
        assertNull(orgExists)

        // But Acme Corp STILL EXISTS (Alice and Bob both reference it)
        val acmeExists = graphObjectManager.load(acmeCorpId.toString(), Organization::class.java)
        assertNotNull(acmeExists)
    }

    @Test
    fun `demo - CASCADE DELETE_ALL - removes target nodes unconditionally`() {
        val tempPersonId = UUID.randomUUID()
        val tempOrgId = UUID.randomUUID()

        // Create temporary data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (p:Person:Mapped {
                    uuid: '$tempPersonId',
                    name: 'Temp Person',
                    bio: 'Temporary',
                    createdBy: 'demo-test'
                })
                CREATE (o:Organization:Mapped {
                    uuid: '$tempOrgId',
                    name: 'Temp Org',
                    createdBy: 'demo-test'
                })
                CREATE (p)-[:WORKS_FOR {
                    startDate: date('2024-01-01'),
                    role: 'Temp Role'
                }]->(o)
                """.trimIndent()
            )
        )

        // Load and clear relationships
        val career = graphObjectManager.load(tempPersonId.toString(), PersonCareer::class.java)!!
        val updated = career.copy(employmentHistory = emptyList())

        // Save with DELETE_ALL - ALWAYS deletes target nodes
        // WARNING: Use with caution! This permanently deletes data.
        graphObjectManager.save(updated, CascadeType.DELETE_ALL)

        // Temp Org is GONE
        val orgExists = graphObjectManager.load(tempOrgId.toString(), Organization::class.java)
        assertNull(orgExists)
    }

    // ============================================================================
    // DEMO 5: Generated Query DSL - Type Safety
    // ============================================================================

    @Test
    fun `demo - generated DSL provides compile-time safety`() {
        // The query DSL is GENERATED from your @GraphView definitions
        // This means you get:
        // 1. IntelliJ autocomplete
        // 2. Compile-time type checking
        // 3. Refactoring support

        val results = graphObjectManager.loadAll<PersonCareer> {
            where {
                // query.person gives you autocomplete on Person properties
                query.person.name eq "Alice Engineer"
            }
        }

        assertEquals(1, results.size)
        assertEquals("Alice Engineer", results[0].person.name)
        assertEquals("Senior Engineer", results[0].employmentHistory[0].role)

        // The DSL is type-safe - you get compile errors if you typo properties!
        // Try: query.person.namee (will not compile)
        // Try: query.person.nonExistentField (will not compile)
    }
}
