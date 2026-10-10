package org.drivine.migration

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.StaleObjectException
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.Replace
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.stateless.Claim
import sample.stateless.ClaimAnyEmployers
import sample.stateless.ClaimCircle
import sample.stateless.HumanClaimSources
import sample.stateless.HumanClaimsLoaded
import sample.stateless.ClaimCompaniesLoaded
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimOwners
import sample.stateless.ClaimView
import sample.stateless.CorporationStaff
import sample.stateless.Human
import sample.stateless.HumanBackers
import sample.stateless.HumanClaims
import sample.stateless.HumanFollowers
import sample.stateless.HumanGroups
import sample.stateless.HumanHoldings
import sample.stateless.HumanMentions
import sample.stateless.HumanMentionsLoaded
import sample.stateless.MemoPeople
import sample.stateless.OrganizationParts
import sample.stateless.ThingClaims
import sample.stateless.ThingEmployers
import sample.stateless.VipClaims
import sample.stateless.VipFollowers
import sample.stateless.VipMentions

/**
 * [RelationshipDirectionRepair] and [PathRelationshipReport] on Neo4j, FalkorDB and Memgraph.
 *
 * Before 0.1.0 a view save wrote a relationship field declared `INCOMING` as outgoing. The graphs
 * here hold such relationships, written with Cypher as the old save wrote them: from the view's root
 * to the target, where the field reads them from the target to the root.
 */
abstract class RelationshipDirectionRepairContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val repair get() = RelationshipDirectionRepair(pm)
    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun relationships(): List<String> = pm.query(
        QuerySpecification.withStatement(
            "MATCH (a)-[r]->(b) RETURN a.id + ' -' + type(r) + coalesce(' ' + toString(r.page), '') + '-> ' + b.id"
        ).transform(String::class.java)
    ).sorted()

    @BeforeEach
    fun seed() {
        run("MATCH (n) DETACH DELETE n")
        run("CREATE (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'}), (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'}), (:Claim {id: 'c3', text: 'three'})")
        // As the old save wrote them: from the root (a person) to the claim.
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (h)-[:MENTIONS {page: 7}]->(c)")
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c2'}) CREATE (h)-[:MENTIONS]->(c)")
        // As the field reads them.
        run("MATCH (h:Human {id: 'bob'}), (c:Claim {id: 'c3'}) CREATE (c)-[:MENTIONS]->(h)")
    }

    @Test
    fun `the report counts the relationships of an incoming field that point away from the root`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(HumanClaims::class.java, finding.view)
        assertEquals("claims", finding.field)
        assertEquals("MENTIONS", finding.type)
        assertEquals(listOf("Human"), finding.rootLabels)
        assertEquals(listOf("Claim"), finding.targetLabels)
        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.rightWay)
        assertNull(finding.ambiguity)
    }

    @Test
    fun `the report changes nothing`() {
        val before = relationships()

        repair.report(HumanClaims::class.java)

        assertEquals(before, relationships())
    }

    @Test
    fun `a view with no writable incoming field has nothing to report`() {
        assertEquals(emptyList(), repair.report(ClaimView::class.java))
    }

    @Test
    fun `repair turns the relationships round, keeps their properties, and the view loads them`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(2, repair.repair(finding))

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
        val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        assertEquals(setOf("c1", "c2"), assertNotNull(stateless.load<HumanClaims>("ada")).claims.map { it.id }.toSet())
        assertEquals(0, repair.report(HumanClaims::class.java).single().wrongWay)
    }

    @Test
    fun `repair in small batches turns every relationship round once`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(2, repair.repair(finding, batchSize = 1))

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `repairing twice changes nothing the second time`() {
        val finding = repair.report(HumanClaims::class.java).single()
        repair.repair(finding)
        val after = relationships()

        assertEquals(0, repair.repair(repair.report(HumanClaims::class.java).single()))

        assertEquals(after, relationships())
    }

    @Test
    fun `a relationship that already points the right way is not duplicated`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(h)")

        assertEquals(2, repair.repair(repair.report(HumanClaims::class.java).single()))

        // One relationship, with the page the one turned round held and the other lacked.
        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a relationship that already points the right way keeps its own properties`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS {page: 9}]->(h)")

        repair.repair(repair.report(HumanClaims::class.java).single())

        assertEquals(listOf("c1 -MENTIONS 9-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a batch size that is not positive is refused`() {
        val finding = repair.report(HumanClaims::class.java).single()
        val before = relationships()

        assertFailsWith<IllegalArgumentException> { repair.repair(finding, batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { repair.repair(finding, batchSize = -1) }

        assertEquals(before, relationships())
    }

    @Test
    fun `a class that is not a view is refused`() {
        val direction = assertFailsWith<IllegalArgumentException> { repair.report(Human::class.java) }
        val path = assertFailsWith<IllegalArgumentException> { PathRelationshipReport(pm).report(Claim::class.java) }

        assertEquals("Human is not a @GraphView.", direction.message)
        assertEquals("Claim is not a @GraphView.", path.message)
    }

    @Test
    fun `the report reaches an incoming field of a view nested in the one it is given`() {
        val finding = repair.report(MemoPeople::class.java).single()

        assertEquals(HumanClaims::class.java, finding.view)
        assertEquals("claims", finding.field)
        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.rightWay)
    }

    @Test
    fun `a field is ambiguous when another view declares the same relationship pointing away from the root`() {
        val finding = repair.report(HumanClaims::class.java, HumanMentions::class.java).single()

        assertNotNull(finding.ambiguity, "HumanMentions says a person mentions claims, so those relationships may be meant")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(2, repair.report(HumanClaims::class.java).single().wrongWay, "a refused repair changes nothing")

        assertEquals(2, repair.repair(finding, force = true))
    }

    @Test
    fun `a field is ambiguous when a view rooted at a subtype declares the relationship pointing away from the root`() {
        run("CREATE (:VipHuman:Human {id: 'vic', name: 'Vic'})")
        run("MATCH (h:Human {id: 'vic'}), (c:Claim {id: 'c3'}) CREATE (h)-[:MENTIONS]->(c)")
        val before = relationships()

        val finding = repair.report(HumanClaims::class.java, VipMentions::class.java).single()

        assertEquals(3, finding.wrongWay)
        assertNotNull(finding.ambiguity, "a VipHuman is a Human, and VipMentions says one mentions claims")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(before, relationships())
    }

    @Test
    fun `a field rooted at a subtype is ambiguous when a view of the supertype declares the relationship pointing away from the root`() {
        val finding = repair.report(VipClaims::class.java, HumanMentions::class.java).single()

        assertEquals(listOf("VipHuman", "Human"), finding.rootLabels)
        assertNotNull(finding.ambiguity, "HumanMentions says a person mentions claims, and a VipHuman is one")
    }

    @Test
    fun `a field is ambiguous when another view only loads the same relationship pointing away from the root`() {
        val before = relationships()

        val finding = repair.report(HumanClaims::class.java, HumanMentionsLoaded::class.java).single()

        assertEquals(HumanClaims::class.java, finding.view, "a field that is only loaded is not itself reported")
        assertNotNull(finding.ambiguity, "HumanMentionsLoaded reads a person's mentions, which other code writes")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(before, relationships())
    }

    @Test
    fun `a finding says whether force can repair it`() {
        val ambiguous = repair.report(HumanClaims::class.java, HumanMentions::class.java).single()
        val sameNodes = repair.report(HumanFollowers::class.java).single()

        assertNotNull(ambiguous.ambiguity)
        assertTrue(ambiguous.repairable, "force turns an ambiguous finding round")
        assertNotNull(sameNodes.ambiguity)
        assertTrue(!sameNodes.repairable, "nothing turns round a relationship between nodes of one kind")
        assertTrue(repair.report(HumanClaims::class.java).single().repairable)
    }

    @Test
    fun `relationships that already point the right way are not counted, however many there are`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS {page: 8}]->(h), (c)-[:MENTIONS {page: 9}]->(h)")

        assertEquals(2, repair.repair(repair.report(HumanClaims::class.java).single()), "the two that pointed the wrong way")

        assertEquals(
            listOf("c1 -MENTIONS 8-> ada", "c1 -MENTIONS 9-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"),
            relationships(),
        )
    }

    @Test
    fun `several relationships that point the wrong way between two nodes become one, and each is counted`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (h)-[:MENTIONS {page: 7}]->(c)")
        val finding = repair.report(HumanClaims::class.java).single()
        assertEquals(3, finding.wrongWay)

        assertEquals(3, repair.repair(finding))

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    // Both ends of a relationship between two corporations are an organization and a company.
    private fun seedCorporations() {
        run("CREATE (:Organization {id: 'group', name: 'Group'}), (:Company {id: 'acme', name: 'Acme'})")
        run("CREATE (:Company:Organization {id: 'globex', name: 'Globex'}), (:Company:Organization {id: 'hooli', name: 'Hooli'})")
        // As the old save wrote them: from the root (an organization) to the company.
        run("MATCH (o:Organization {id: 'group'}), (c:Company {id: 'acme'}) CREATE (o)-[:PART_OF]->(c)")
        run("MATCH (o:Organization {id: 'group'}), (c:Company {id: 'globex'}) CREATE (o)-[:PART_OF]->(c)")
        // As the field reads them.
        run("MATCH (c:Company {id: 'acme'}), (o:Organization {id: 'hooli'}) CREATE (c)-[:PART_OF]->(o)")
        run("MATCH (c:Company {id: 'globex'}), (o:Organization {id: 'hooli'}) CREATE (c)-[:PART_OF]->(o)")
    }

    @Test
    fun `the report counts apart a relationship between two nodes that each carry the labels of both ends`() {
        seedCorporations()

        val finding = repair.report(OrganizationParts::class.java).single()

        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.rightWay)
        assertEquals(1, finding.eitherWay, "globex and hooli are each a company and an organization")
        assertNull(finding.ambiguity)
        assertTrue(finding.repairable)
    }

    @Test
    fun `repair leaves alone a relationship between two nodes that each carry the labels of both ends`() {
        seedCorporations()

        assertEquals(2, repair.repair(repair.report(OrganizationParts::class.java).single()))

        assertEquals(
            listOf(
                "acme -PART_OF-> group", "acme -PART_OF-> hooli", "ada -MENTIONS 7-> c1", "ada -MENTIONS-> c2",
                "c3 -MENTIONS-> bob", "globex -PART_OF-> group", "globex -PART_OF-> hooli",
            ),
            relationships(),
        )
    }

    @Test
    fun `repairing twice changes nothing the second time where nodes carry the labels of both ends`() {
        seedCorporations()
        repair.repair(repair.report(OrganizationParts::class.java).single())
        val after = relationships()

        val again = repair.report(OrganizationParts::class.java).single()
        assertEquals(0, again.wrongWay)
        assertEquals(1, again.eitherWay)
        assertEquals(0, repair.repair(again))
        assertEquals(0, repair.repair(again, force = true), "forced or not")

        assertEquals(after, relationships())
    }

    @Test
    fun `a field whose root is a kind of its target is repaired when forced, and only where the store says which end is which`() {
        run("CREATE (:VipHuman:Human {id: 'vip', name: 'Vip'}), (:VipHuman:Human {id: 'star', name: 'Star'})")
        // As the old save wrote a follower: from the root (a VIP) to the person.
        run("MATCH (v:VipHuman {id: 'vip'}), (h:Human {id: 'ada'}) CREATE (v)-[:FOLLOWS]->(h)")
        // Between two VIPs either can be the root.
        run("MATCH (v:VipHuman {id: 'vip'}), (s:VipHuman {id: 'star'}) CREATE (s)-[:FOLLOWS]->(v)")

        val finding = repair.report(VipFollowers::class.java).single()

        assertEquals(1, finding.wrongWay)
        assertEquals(1, finding.eitherWay)
        assertNotNull(finding.ambiguity, "a VIP who follows a person may be meant")
        assertTrue(finding.repairable, "ada is no VIP, so she cannot be the root")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }

        assertEquals(1, repair.repair(finding, force = true))

        assertTrue("ada -FOLLOWS-> vip" in relationships())
        assertTrue("star -FOLLOWS-> vip" in relationships(), "left as it was")
        assertEquals(0, repair.repair(repair.report(VipFollowers::class.java).single(), force = true), "a second run changes nothing")
    }

    @Test
    fun `a field whose root has no label is reported, and repaired only when forced`() {
        val before = relationships()

        val finding = repair.report(ThingClaims::class.java).single()

        assertEquals(emptyList(), finding.rootLabels)
        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.rightWay)
        assertEquals(0, finding.eitherWay)
        assertNotNull(finding.ambiguity, "a node of any label can be a claim")
        assertTrue(finding.repairable, "a person is no claim, so the store says which end is the claim")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(before, relationships())

        assertEquals(2, repair.repair(finding, force = true))
        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a finding built by hand is refused for its labels, and the refusal says why`() {
        run("MATCH (a:Human {id: 'ada'}), (b:Human {id: 'bob'}) CREATE (a)-[:FOLLOWS]->(b)")
        val before = relationships()
        val finding = repair.report(HumanFollowers::class.java).single().copy(ambiguity = null)

        val refused = assertFailsWith<IllegalStateException> { repair.repair(finding) }

        assertEquals(
            "HumanFollowers.followers cannot be repaired: its root and its target can be the same nodes, " +
                "so nothing tells a relationship written the wrong way from one that is meant.",
            refused.message,
        )
        assertEquals(before, relationships())
    }

    // ----- A path field written as a direct relationship -----

    private fun seedPath() {
        run("CREATE (:Company {id: 'acme', name: 'Acme'}), (:Company {id: 'initech', name: 'Initech'})")
        run("MATCH (c:Claim {id: 'c3'}), (h:Human {id: 'bob'}), (o:Company {id: 'acme'}) CREATE (h)-[:WORKS_AT]->(o)")
        // As the old save wrote the path field: straight from the claim to the company.
        run("MATCH (c:Claim {id: 'c3'}), (o:Company {id: 'acme'}) CREATE (c)-[:MENTIONS]->(o)")
    }

    @Test
    fun `the path report counts direct relationships of the first hop's type to the path's end`() {
        seedPath()
        val before = relationships()

        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java).single()

        assertEquals("employers", finding.field)
        assertEquals("MENTIONS", finding.type)
        assertEquals(listOf("Claim"), finding.rootLabels)
        assertEquals(listOf("Company"), finding.targetLabels)
        assertEquals(1, finding.direct)
        assertNull(finding.ambiguity)
        assertEquals(before, relationships(), "the report changes nothing")
    }

    @Test
    fun `the path report's statement removes what it counted and nothing else`() {
        seedPath()
        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java).single()

        run(finding.removalStatement)

        assertEquals(0, PathRelationshipReport(pm).report(ClaimEmployers::class.java).single().direct)
        assertEquals(listOf("ada -MENTIONS 7-> c1", "ada -MENTIONS-> c2", "bob -WORKS_AT-> acme", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a path finding is ambiguous when a view declares that relationship directly`() {
        seedPath()

        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java, ClaimView::class.java).single()

        assertEquals(1, finding.direct)
        assertNotNull(finding.ambiguity, "ClaimView.companies says a claim mentions companies")
    }

    @Test
    fun `a path finding is ambiguous when a view only loads that relationship directly`() {
        seedPath()

        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java, ClaimCompaniesLoaded::class.java).single()

        assertEquals(1, finding.direct)
        assertNotNull(finding.ambiguity, "ClaimCompaniesLoaded.companies reads a claim's companies, which other code writes")
    }

    @Test
    fun `a path finding is ambiguous when the first hop names no label`() {
        val finding = PathRelationshipReport(pm).report(ClaimAnyEmployers::class.java).single()

        assertNotNull(finding.ambiguity, "a first hop to any node may itself reach a company")
    }

    @Test
    fun `a path finding is ambiguous when the first hop reaches the kind of node the path ends at`() {
        val finding = PathRelationshipReport(pm).report(HumanHoldings::class.java).single()

        assertEquals("OWNS", finding.type)
        assertNotNull(finding.ambiguity, "a person owns a company directly, as the first hop says")
    }

    @Test
    fun `a path finding is ambiguous when a node the first hop reaches can be one the path ends at`() {
        run("CREATE (:Company:Organization {id: 'globex', name: 'Globex'})")
        run("MATCH (h:Human {id: 'ada'}), (o:Organization {id: 'globex'}) CREATE (h)-[:OWNS]->(o)")

        assertNull(
            PathRelationshipReport(pm).report(HumanGroups::class.java).single().ambiguity,
            "no view given says a company can be an organization",
        )
        val finding = PathRelationshipReport(pm).report(HumanGroups::class.java, CorporationStaff::class.java).single()

        assertEquals(1, finding.direct, "a genuine first hop, which the removal statement would delete")
        assertNotNull(finding.ambiguity, "a Corporation is a Company and an Organization")
    }

    @Test
    fun `a path finding is ambiguous when another path's first hop is that relationship`() {
        seedPath()

        assertNull(PathRelationshipReport(pm).report(ClaimEmployers::class.java).single().ambiguity)
        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java, ClaimOwners::class.java)
            .single { it.field == "employers" }

        assertEquals(1, finding.direct, "what may be a first hop of ClaimOwners.owners, which the removal statement would delete")
        assertNotNull(finding.ambiguity, "ClaimOwners.owners goes from a claim to a company by MENTIONS")
    }

    @Test
    fun `a path finding is not ambiguous for a first hop that points at the root`() {
        val finding = PathRelationshipReport(pm).report(HumanBackers::class.java).single()

        assertEquals("BACKS", finding.type)
        assertNull(finding.ambiguity, "the first hop runs from a company to a person, and the old save wrote from a person to a company")
    }

    @Test
    fun `the path report counts and removes for a root that has no label`() {
        seedPath()

        val finding = PathRelationshipReport(pm).report(ThingEmployers::class.java).single()

        assertEquals(emptyList(), finding.rootLabels)
        assertEquals(1, finding.direct)
        run(finding.removalStatement)
        assertEquals(listOf("ada -MENTIONS 7-> c1", "ada -MENTIONS-> c2", "bob -WORKS_AT-> acme", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a field between two nodes of one label cannot be repaired`() {
        run("MATCH (a:Human {id: 'ada'}), (b:Human {id: 'bob'}) CREATE (a)-[:FOLLOWS]->(b)")
        val before = relationships()

        val finding = repair.report(HumanFollowers::class.java).single()

        assertNotNull(finding.ambiguity)
        assertFailsWith<IllegalStateException> { repair.repair(finding, force = true) }
        assertEquals(before, relationships())
    }

    @Test
    fun `a field is ambiguous when a path's hop is the same relationship pointing away from the root`() {
        val before = relationships()

        val finding = repair.report(HumanClaims::class.java, HumanClaimSources::class.java).single()

        assertNotNull(finding.ambiguity, "a person's mentions may be the first hop of HumanClaimSources.companies")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(before, relationships())
    }

    @Test
    fun `a field declared read-only is reported, for the old save wrote it too`() {
        val finding = repair.report(HumanClaimsLoaded::class.java).single()

        assertEquals("claims", finding.field)
        assertEquals(2, finding.wrongWay)
    }

    @Test
    fun `the report counts the relationships a repair would merge into one that already points the right way`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS {page: 9}]->(h)")

        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.collisions, "ada and c1 have one each way")
    }

    @Test
    fun `a relationship merged into one that points the right way gives it the properties it lacks`() {
        run("MATCH (h:Human {id: 'ada'})-[r:MENTIONS]->(c:Claim {id: 'c1'}) SET r.note = 'kept'")
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS {page: 9}]->(h)")

        repair.repair(repair.report(HumanClaims::class.java).single())

        assertEquals(
            listOf("9/kept"),
            pm.query(
                QuerySpecification.withStatement("MATCH (:Claim {id: 'c1'})-[r:MENTIONS]->(:Human {id: 'ada'}) RETURN toString(r.page) + '/' + r.note")
                    .transform(String::class.java)
            ),
        )
    }

    @Test
    fun `repair gives both ends a new relationship token, so a replace of an object loaded before it is refused`() {
        val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        val loaded = stateless.save(ClaimCircle(Claim("k1", "one")))
        // As the old save wrote an endorser: from the root (the claim) to the person.
        run("MATCH (c:Claim {id: 'k1'}), (h:Human {id: 'ada'}) CREATE (c)-[:ENDORSES]->(h)")

        assertEquals(1, repair.repair(repair.report(ClaimCircle::class.java).single()))

        assertFailsWith<StaleObjectException> { stateless.save(loaded, Replace(ClaimCircle::endorsers)) }
        assertTrue("ada -ENDORSES-> k1" in relationships(), "the relationship the repair turned round is still there")
    }
}

@Testcontainers
class RelationshipDirectionRepairNeo4jTest : RelationshipDirectionRepairContract() {
    companion object {
        private const val PASSWORD = "directionrepair"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-direction-repair", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@Testcontainers
class RelationshipDirectionRepairFalkorDbTest : RelationshipDirectionRepairContract() {
    companion object {
        private const val GRAPH = "directionrepair"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-direction-repair", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@Testcontainers
class RelationshipDirectionRepairMemgraphTest : RelationshipDirectionRepairContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-direction-repair", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}
