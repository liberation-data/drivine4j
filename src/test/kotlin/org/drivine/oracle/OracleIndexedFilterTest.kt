package org.drivine.oracle

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.manager.NodeRef
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.nodeRef
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.dsl.query
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import sample.patch.ClaimQueryDsl
import sample.patch.ClaimViewQueryDsl
import sample.patch.HumanProperties
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import kotlin.test.assertEquals

/** A node with no label, found by its id alone. */
@NodeFragment
data class Tag(@NodeId val id: String, val name: String)

/** The claims that mention a person, read against the direction of the relationship. */
@GraphView
data class MentionedIn(
    @Root val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

class MentionedInQueryDsl {
    val human = HumanProperties("human")
    val claims = ClaimQueryDsl("claims")

    companion object {
        val INSTANCE = MentionedInQueryDsl()
    }
}

/**
 * Each statement in which a node is found by a property that a range index is over, and nothing
 * else of that node is read.
 *
 * Oracle Free 23.26 drops such a node from the plan when a foreign key joins it, and the test of its
 * property with it: the statement then answers for every node, or with the value bound, the server
 * process ends (`ORA-07445` in `kkocfbMarkBindFroCB`, `ORA-03113` to the client). It takes an index
 * over `JSON_VALUE` of the property, which a range index is; a uniqueness constraint's index, over a
 * `CASE`, is not one it is seen with. Each graph here has ten claims, so a test that is dropped
 * answers for ten.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleIndexedFilterTest {

    private val pm = OracleTestDatabase.graphManager("indexed")
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    @BeforeAll
    fun index() {
        // Today a range index is over the property of every node, whatever its label: one index over `id`, one over `name`.
        listOf("Claim" to "id", "Human" to "name").forEach { (label, property) -> pm.indexes.ensure(RangeIndexSpec(label, property)) }
        // A tag has no label for a range index to name, so the index the bug needs, over the `id` of every
        // node, is made here as well, in case a range index comes to cover only the nodes of its label.
        // While Claim's is that index, Oracle refuses a second over the same expression (ORA-01408).
        OracleTestDatabase.run(
            """
            DECLARE e_indexed EXCEPTION; PRAGMA EXCEPTION_INIT(e_indexed, -1408);
            BEGIN EXECUTE IMMEDIATE 'CREATE INDEX IF NOT EXISTS indexed_any_id ON indexed_node (JSON_VALUE(props, ''$.id''))';
            EXCEPTION WHEN e_indexed THEN NULL; END;
            """
        )
    }

    /** Claim `ci` mentions `hi` and the person after; tag `t1` tags `c1` and `c6`, `t2` tags `c2` and `c7`, and so on; each claim is filed under its tags. */
    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        val people = (1..10).map { Human("h$it", "Human $it") }
        (1..10).forEach { i -> manager.save(ClaimView(Claim("c$i", "claim $i"), people = listOf(people[i - 1], people[i % 10]))) }
        (1..5).forEach { t ->
            manager.save(Tag("t$t", "tag $t"))
            listOf(t, t + 5).forEach { c ->
                manager.edges.relate(NodeRef(Tag::class.java, "t$t"), nodeRef<Claim>("c$c"), "TAGS")
                manager.edges.relate(nodeRef<Claim>("c$c"), NodeRef(Tag::class.java, "t$t"), "FILED_UNDER")
            }
        }
    }

    private fun ids(claims: List<Claim>) = claims.map { it.id }.toSet()

    @Test
    fun `the nodes related to a node with no label are those of that node, in each direction`() {
        val tag = NodeRef(Tag::class.java, "t1")

        assertEquals(setOf("c1", "c6"), ids(manager.edges.loadRelated(tag, "TAGS", Direction.OUTGOING, Claim::class.java)))
        assertEquals(setOf("c1", "c6"), ids(manager.edges.loadRelated(tag, "FILED_UNDER", Direction.INCOMING, Claim::class.java)))
        assertEquals(setOf("c1", "c6"), ids(manager.edges.loadRelated(tag, "TAGS", Direction.UNDIRECTED, Claim::class.java)))
    }

    @Test
    fun `the nodes related to a node with a label are those of that node, in each direction`() {
        assertEquals(setOf("h3", "h4"), manager.edges.loadRelated(nodeRef<Claim>("c3"), "MENTIONS", Direction.OUTGOING, Human::class.java).map { it.id }.toSet())
        assertEquals(setOf("c2", "c3"), ids(manager.edges.loadRelated(nodeRef<Human>("h3"), "MENTIONS", Direction.INCOMING, Claim::class.java)))
        assertEquals(setOf("c2", "c3"), ids(manager.edges.loadRelated(nodeRef<Human>("h3"), "MENTIONS", Direction.UNDIRECTED, Claim::class.java)))
    }

    @Test
    fun `a view is loaded and counted by an indexed property of a related node, in each direction`() {
        val dsl = ClaimViewQueryDsl.INSTANCE
        assertEquals(setOf("c2", "c3"), manager.loadAll(ClaimView::class.java, dsl) { where { query.people.id eq "h3" } }.map { it.claim.id }.toSet())
        assertEquals(setOf("c2", "c3"), manager.loadAll(ClaimView::class.java, dsl) { where { query.people.name eq "Human 3" } }.map { it.claim.id }.toSet())
        assertEquals(2, manager.count(ClaimView::class.java, dsl) { where { query.people.id eq "h3" } })

        val incoming = MentionedInQueryDsl.INSTANCE
        assertEquals(setOf("h3", "h4"), manager.loadAll(MentionedIn::class.java, incoming) { where { query.claims.id eq "c3" } }.map { it.human.id }.toSet())
        assertEquals(2, manager.count(MentionedIn::class.java, incoming) { where { query.claims.id eq "c3" } })
    }

    @Test
    fun `a patch is applied only where an indexed property of a related node meets its condition`() {
        val dsl = ClaimViewQueryDsl.INSTANCE
        assertEquals(true, manager.patch("c3", ClaimView::class.java, dsl) { fields { query.claim.note setTo "one" }; where { query.people.id eq "h3" } }.applied)
        assertEquals(false, manager.patch("c3", ClaimView::class.java, dsl) { fields { query.claim.note setTo "two" }; where { query.people.id eq "h9" } }.applied)
        assertEquals("one", manager.load("c3", Claim::class.java)!!.note)
    }

    @Test
    fun `a delete takes only the roots whose related node has the indexed property`() {
        assertEquals(2, manager.deleteAll(ClaimView::class.java, ClaimViewQueryDsl.INSTANCE) { where { query.people.id eq "h3" } })
        assertEquals(8, manager.count(Claim::class.java))
    }
}
