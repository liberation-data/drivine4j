package org.drivine.manager

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.nodelabels.ClashingNode
import sample.nodelabels.MemberNode
import sample.nodelabels.MixedBagNode
import sample.nodelabels.Role
import sample.nodelabels.ThingNode
import sample.nodelabels.ThingView
import sample.nodelabels.TrespassingNode

/**
 * `@NodeLabels` and the flat `@PropertyBag`, verified on Neo4j, FalkorDB and Memgraph.
 *
 * A labels field is read from the node's labels and written as real labels. A save adds; a save under
 * [NullPolicy.CLEAR] also removes, but only members of a closed set — a label no field speaks for is
 * never touched. `ownGom` gives a manager with a session of its own, standing in for another process:
 * what a save removes must not depend on who loaded the object. A flat bag stores each entry under its bare key and reads back every property that
 * nothing else declares.
 */
private fun verify(gom: GraphObjectManager, pm: PersistenceManager, ownGom: () -> GraphObjectManager) {
    fun labelsOf(id: String): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) UNWIND labels(n) AS l RETURN l")
            .bind(mapOf("id" to id)).transform(String::class.java)
    ).toSet()

    // ----- Closed set: saved as labels, read back as the enum -----
    gom.save(MemberNode("m1", "Ada", setOf(Role.Admin, Role.Author)))
    assertEquals(setOf("Member", "Admin", "Author"), labelsOf("m1"))
    assertEquals(setOf(Role.Admin, Role.Author), gom.load("m1", MemberNode::class.java)!!.roles)

    // ----- A label outside the enum is not the field's: ignored on load, left alone on save -----
    pm.execute(QuerySpecification.withStatement("MATCH (n {id: 'm1'}) SET n:Audited"))
    val audited = gom.load("m1", MemberNode::class.java)!!
    assertEquals(setOf(Role.Admin, Role.Author), audited.roles)

    // ----- Default save (merge-patch): a dropped member stays -----
    gom.save(audited.copy(roles = setOf(Role.Reviewer)))
    assertEquals(setOf("Member", "Admin", "Author", "Reviewer", "Audited"), labelsOf("m1"))

    // ----- CLEAR: the field is the set; members it lacks go, everything else stays -----
    gom.save(MemberNode("m1", "Ada", setOf(Role.Reviewer)), nullPolicy = NullPolicy.CLEAR)
    assertEquals(setOf("Member", "Reviewer", "Audited"), labelsOf("m1"))
    assertEquals(setOf(Role.Reviewer), gom.load("m1", MemberNode::class.java)!!.roles)

    // ----- CLEAR with an empty set removes every member, and never the fragment's own label -----
    gom.save(MemberNode("m1", "Ada", emptySet()), nullPolicy = NullPolicy.CLEAR)
    assertEquals(setOf("Member", "Audited"), labelsOf("m1"))
    assertTrue(gom.load("m1", MemberNode::class.java)!!.roles.isEmpty())

    // ----- Open set: any label, including one that is not a plain identifier -----
    gom.save(ThingNode("t1", "Lyre", setOf("Instrument", "String Instrument"), mapOf("strings" to 7, "source-ref" to "kb")))
    assertEquals(setOf("Thing", "Instrument", "String Instrument"), labelsOf("t1"))
    val thing = gom.load("t1", ThingNode::class.java)!!
    assertEquals(setOf("Thing", "Instrument", "String Instrument"), thing.labels, "an open set reads every label")

    // ----- Open set, default save: adds, and removes nothing -----
    gom.save(ThingNode("t1", "Lyre", setOf("Antique"), thing.properties))
    assertEquals(setOf("Thing", "Instrument", "String Instrument", "Antique"), labelsOf("t1"))

    // ----- Open set, CLEAR from an object that was never loaded: the labels this field wrote and no
    // longer holds are removed; one written by other code is not, nor is the fragment's own -----
    pm.execute(QuerySpecification.withStatement("MATCH (n {id: 't1'}) SET n:Audited"))
    ownGom().save(ThingNode("t1", "Lyre", setOf("Antique", "Restored"), thing.properties), nullPolicy = NullPolicy.CLEAR)
    assertEquals(setOf("Thing", "Antique", "Restored", "Audited"), labelsOf("t1"))

    // ----- ...and again, to nothing: every label the field wrote goes -----
    ownGom().save(ThingNode("t1", "Lyre", emptySet(), thing.properties), nullPolicy = NullPolicy.CLEAR)
    assertEquals(setOf("Thing", "Audited"), labelsOf("t1"))

    // ----- A label added after a CLEAR is the field's again, and removable -----
    ownGom().save(ThingNode("t1", "Lyre", setOf("Loaned"), thing.properties))
    ownGom().save(ThingNode("t1", "Lyre", emptySet(), thing.properties), nullPolicy = NullPolicy.CLEAR)
    assertEquals(setOf("Thing", "Audited"), labelsOf("t1"))

    gom.save(ThingNode("t1", "Lyre", setOf("Instrument"), thing.properties))

    // ----- The record of owned labels is kept under Drivine's own prefix... -----
    assertEquals(
        1L,
        pm.getOne(
            QuerySpecification.withStatement(
                "MATCH (n:Thing {id: 't1'}) WHERE n.`__drivine.labels.labels` IS NOT NULL RETURN count(n)"
            ).transform(Long::class.java)
        ),
    )

    // ----- ...and is not one of the node's open properties -----
    assertEquals(setOf("strings", "source-ref"), gom.load("t1", ThingNode::class.java)!!.properties.keys)

    // ----- Flat bag: entries are bare properties; declared fields are not in it -----
    assertEquals("Lyre", thing.name)
    assertEquals(7L, (thing.properties["strings"] as Number).toLong())
    assertEquals("kb", thing.properties["source-ref"])
    assertEquals(setOf("strings", "source-ref"), thing.properties.keys)
    assertEquals(
        1L,
        pm.getOne(QuerySpecification.withStatement("MATCH (n:Thing {id: 't1'}) WHERE n.strings = 7 RETURN count(n)").transform(Long::class.java)),
        "a flat bag entry is a plain node property",
    )

    // ----- Flat bag: merge-patch keeps a dropped key, CLEAR removes it — and never a declared field -----
    gom.save(ThingNode("t2", "Harp", emptySet(), mapOf("strings" to 47, "maker" to "Erard")))
    gom.save(ThingNode("t2", "Harp", emptySet(), mapOf("strings" to 46)))
    assertEquals(setOf("strings", "maker"), gom.load("t2", ThingNode::class.java)!!.properties.keys)
    gom.save(ThingNode("t2", "Harp", emptySet(), mapOf("strings" to 46)), nullPolicy = NullPolicy.CLEAR)
    val harp = gom.load("t2", ThingNode::class.java)!!
    assertEquals(setOf("strings"), harp.properties.keys)
    assertEquals("Harp", harp.name)

    // ----- Flat bag: an entry that is a declared field's property is rejected -----
    assertThrows(IllegalArgumentException::class.java) {
        gom.save(ThingNode("t3", "Oud", emptySet(), mapOf("name" to "shadow")))
    }

    // ----- Flat bag beside a prefixed bag: each keeps its own -----
    gom.save(MixedBagNode("x1", mapOf("source" to "wiki"), mapOf("colour" to "red")))
    val mixed = gom.load("x1", MixedBagNode::class.java)!!
    assertEquals(mapOf("source" to "wiki"), mixed.meta)
    assertEquals(mapOf("colour" to "red"), mixed.rest)
    gom.save(MixedBagNode("x1", mapOf("source" to "wiki"), emptyMap()), nullPolicy = NullPolicy.CLEAR)
    val cleared = gom.load("x1", MixedBagNode::class.java)!!
    assertEquals(mapOf("source" to "wiki"), cleared.meta, "clearing the flat bag must not remove the prefixed bag's property")
    assertTrue(cleared.rest.isEmpty())

    // ----- Through a view: root and relationship target both carry their labels -----
    gom.save(
        ThingView(
            thing = ThingNode("t4", "Viol", setOf("Instrument"), mapOf("strings" to 6)),
            owners = listOf(MemberNode("m2", "Bea", setOf(Role.Author))),
        ),
        cascade = CascadeType.NONE,
    )
    assertEquals(setOf("Thing", "Instrument"), labelsOf("t4"))
    assertEquals(setOf("Member", "Author"), labelsOf("m2"))
    val view = gom.load("t4", ThingView::class.java)!!
    assertEquals(setOf("Thing", "Instrument"), view.thing.labels)
    assertEquals(6L, (view.thing.properties["strings"] as Number).toLong())
    assertEquals(setOf(Role.Author), view.owners.single().roles)

    // ----- saveAll: rows with different labels each get their own -----
    gom.saveAll(listOf(MemberNode("m3", "Cy", setOf(Role.Admin)), MemberNode("m4", "Di", setOf(Role.Reviewer))))
    assertEquals(setOf("Member", "Admin"), labelsOf("m3"))
    assertEquals(setOf("Member", "Reviewer"), labelsOf("m4"))

    // ----- loadAll returns each node's labels -----
    assertEquals(
        mapOf("m3" to setOf(Role.Admin), "m4" to setOf(Role.Reviewer)),
        gom.loadAll(MemberNode::class.java).filter { it.id in setOf("m3", "m4") }.associate { it.id to it.roles },
    )

    // ----- A node deleted behind the session comes back whole when the same object is saved again -----
    val gone = ThingNode("gone", "Ada", setOf("Person"), mapOf("address" to "ada@example.com"))
    gom.save(gone)
    pm.execute(QuerySpecification.withStatement("MATCH (n {id: 'gone'}) DETACH DELETE n"))
    gom.save(gone)
    assertEquals(setOf("Thing", "Person"), labelsOf("gone"))
    val back = gom.load("gone", ThingNode::class.java)!!
    assertEquals("Ada", back.name)
    assertEquals("ada@example.com", back.properties["address"])
}

class NodeLabelsModelTest {
    @Test
    fun `a property under Drivine's own prefix is rejected when the model is built`() {
        val e = assertThrows(IllegalArgumentException::class.java) { FragmentModel.from(TrespassingNode::class.java) }
        assertTrue(e.message!!.contains("__drivine."), e.message)
    }

    @Test
    fun `a closed set that names the fragment's own label is rejected when the model is built`() {
        val e = assertThrows(IllegalArgumentException::class.java) { FragmentModel.from(ClashingNode::class.java) }
        assertTrue(e.message!!.contains("'Clashing'"), e.message)
    }
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class NodeLabelsNeo4jTest {
    companion object {
        private const val PASSWORD = "nodelabelstest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-labels", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `node labels and a flat bag round-trip on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()), pm) { buildGom(pm, SubtypeRegistry()) }
}

@Testcontainers
class NodeLabelsFalkorDbTest {
    companion object {
        private const val GRAPH = "nodelabelstest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-labels", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `node labels and a flat bag round-trip on FalkorDb`() = verify(buildGom(pm, SubtypeRegistry()), pm) { buildGom(pm, SubtypeRegistry()) }
}

@Testcontainers
class NodeLabelsMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-labels", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `node labels and a flat bag round-trip on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()), pm) { buildGom(pm, SubtypeRegistry()) }
}

