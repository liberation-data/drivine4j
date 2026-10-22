package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
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
import sample.mapped.view.DeletableSessionShelf
import sample.mapped.view.DeletableSessionTree
import sample.mapped.view.DeletableSessionTreeDeep
import sample.mapped.view.MessageWithAttachments
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A cascade delete through a recursive `@GraphView` deletes exactly what a load of it reads: the
 * nodes at every level and what each level holds, and nothing the view does not declare. A tree
 * deeper than the relationship's maxDepth is refused before anything is written, where the cascade
 * would otherwise leave the levels below it behind. Verified on Neo4j, FalkorDB, Memgraph and Oracle;
 * `DELETE_ORPHAN` on all but Memgraph, which has none.
 *
 * The tree (PART_OF points child → parent; the view's INCOMING edge makes them parts):
 *   rc-root ← rc-child ← rc-grand, each with a message, and a :User the nested messages point at.
 */
private val TREE = """
    CREATE (u:User {id: 'rc-user'})
    CREATE (root:Session {id: 'rc-root'}), (m0:Message {id: 'rc-m0'})
    CREATE (child:Session {id: 'rc-child'}), (m1:Message {id: 'rc-m1'})
    CREATE (grand:Session {id: 'rc-grand'}), (m2:Message {id: 'rc-m2'})
    CREATE (root)-[:HAS_MESSAGE]->(m0)
    CREATE (child)-[:PART_OF]->(root)
    CREATE (child)-[:HAS_MESSAGE]->(m1)
    CREATE (grand)-[:PART_OF]->(child)
    CREATE (grand)-[:HAS_MESSAGE]->(m2)
    CREATE (m1)-[:AUTHORED_BY]->(u)
    CREATE (m2)-[:SENT_TO]->(u)
""".trimIndent()

/** Sessions rc-s0 ← rc-s1 ← … ← rc-s[levels], each with a message rc-sm[i]: [levels] below the root. */
private fun chain(levels: Int): String = (0..levels).joinToString("\n") { i ->
    "CREATE (s$i:Session {id: 'rc-s$i'}), (sm$i:Message {id: 'rc-sm$i'}) CREATE (s$i)-[:HAS_MESSAGE]->(sm$i)" +
        if (i > 0) " CREATE (s$i)-[:PART_OF]->(s${i - 1})" else ""
}

private fun ids(pm: PersistenceManager): Set<String> =
    pm.query(QuerySpecification.withStatement("MATCH (n) RETURN n.id").transform(String::class.java)).toSet()

private fun treeIds(tree: DeletableSessionTree): Set<String> =
    setOf(tree.session.id) + tree.messages.map { it.id } + tree.parts.flatMap(::treeIds)

private fun treeIds(tree: DeletableSessionTreeDeep): Set<String> =
    setOf(tree.session.id) + tree.messages.flatMap(::messageIds) + tree.parts.flatMap(::treeIds)

private fun messageIds(message: MessageWithAttachments): Set<String> = setOf(message.message.id) + message.attachments.map { it.id }

/** Deletes with [delete] and gives the ids it removed, with the count it returned. */
private fun removedBy(pm: PersistenceManager, delete: () -> Int): Pair<Set<String>, Int> {
    val before = ids(pm)
    val deleted = delete()
    return (before - ids(pm)) to deleted
}

private fun verifyDeleteAllMatchesLoad(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement(TREE))
    val loaded = treeIds(assertNotNull(gom.load("rc-root", DeletableSessionTree::class.java)))
    assertEquals(setOf("rc-root", "rc-m0", "rc-child", "rc-m1", "rc-grand", "rc-m2"), loaded)

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTree::class.java, CascadeType.DELETE_ALL) }

    assertEquals(loaded, removed, "delete removes exactly what load reads")
    assertEquals(loaded.size, deleted)
    // The nested messages point at the user, which the view does not declare.
    assertEquals(setOf("rc-user"), ids(pm))
}

/**
 * [loads] is false where the deep tree loads short: on FalkorDB 6 a pattern comprehension after one
 * that nests another comes back empty (FalkorDB/FalkorDB#3004), and a level's messages and parts each nest.
 * It still loads there, its root whole, and reads nothing the tree does not hold.
 */
private fun verifyDeleteAllThroughNestedViewsMatchesLoad(gom: StatelessGraphObjectManager, pm: PersistenceManager, loads: Boolean = true) {
    pm.execute(QuerySpecification.withStatement(TREE))
    pm.execute(
        QuerySpecification.withStatement(
            """
            MATCH (m1:Message {id: 'rc-m1'}), (m2:Message {id: 'rc-m2'}), (u:User {id: 'rc-user'})
            CREATE (a1:Attachment {id: 'rc-a1'}), (a2:Attachment {id: 'rc-a2'})
            CREATE (m1)-[:HAS_ATTACHMENT]->(a1)
            CREATE (m2)-[:HAS_ATTACHMENT]->(a2)
            CREATE (a2)-[:AUTHORED_BY]->(u)
            """.trimIndent()
        )
    )
    val tree = setOf("rc-root", "rc-m0", "rc-child", "rc-m1", "rc-a1", "rc-grand", "rc-m2", "rc-a2")
    val loaded = treeIds(assertNotNull(gom.load("rc-root", DeletableSessionTreeDeep::class.java)))
    if (loads) {
        assertEquals(tree, loaded)
    } else {
        assertTrue(loaded.containsAll(setOf("rc-root", "rc-m0")) && tree.containsAll(loaded), "$loaded")
    }

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTreeDeep::class.java, CascadeType.DELETE_ALL) }

    assertEquals(tree, removed, "every level reaches its messages' attachments")
    assertEquals(tree.size, deleted)
    assertEquals(setOf("rc-user"), ids(pm))
}

private fun verifyDeleteAllFromAShelfMatchesLoad(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement(TREE + "\nCREATE (shelf:Session {id: 'rc-shelf'}) CREATE (shelf)-[:SHELVES]->(root)"))
    // Loaded through the shelf, the tree holds every level it holds loaded on its own.
    val shelf = assertNotNull(gom.load("rc-shelf", DeletableSessionShelf::class.java))
    assertEquals(treeIds(assertNotNull(gom.load("rc-root", DeletableSessionTree::class.java))), treeIds(shelf.trees.single()))
    val loaded = setOf(shelf.session.id) + shelf.trees.flatMap(::treeIds)

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-shelf", DeletableSessionShelf::class.java, CascadeType.DELETE_ALL) }

    assertEquals(loaded, removed)
    assertEquals(loaded.size, deleted)
}

private fun verifyFullDepthAndCycleAreDeleted(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    // Exactly maxDepth levels below the root: nothing is left below, so nothing is refused.
    pm.execute(QuerySpecification.withStatement(chain(3)))
    val loaded = treeIds(assertNotNull(gom.load("rc-s0", DeletableSessionTree::class.java)))
    assertEquals(8, loaded.size)
    assertEquals(loaded to 8, removedBy(pm) { gom.delete("rc-s0", DeletableSessionTree::class.java, CascadeType.DELETE_ALL) })

    // A ring of four: one level past maxDepth comes back to the root, which the cascade starts from.
    pm.execute(QuerySpecification.withStatement(chain(3) + "\nCREATE (s0)-[:PART_OF]->(s3)"))
    val ring = treeIds(assertNotNull(gom.load("rc-s0", DeletableSessionTree::class.java)))
    assertEquals(loaded to 8, removedBy(pm) { gom.delete("rc-s0", DeletableSessionTree::class.java, CascadeType.DELETE_ALL) })
    assertEquals(loaded, ring)
}

private fun verifyTooDeepIsRefused(gom: StatelessGraphObjectManager, pm: PersistenceManager, orphans: Boolean) {
    // Four levels below the root, one past maxDepth = 3.
    pm.execute(QuerySpecification.withStatement(chain(4) + "\nCREATE (shelf:Session {id: 'rc-shelf'}) CREATE (shelf)-[:SHELVES]->(s0)"))
    val seeded = ids(pm)

    val cascades = if (orphans) listOf(CascadeType.DELETE_ALL, CascadeType.DELETE_ORPHAN) else listOf(CascadeType.DELETE_ALL)
    for (cascade in cascades) {
        val refused = assertFailsWith<IllegalStateException>("$cascade") {
            gom.delete("rc-s0", DeletableSessionTree::class.java, cascade)
        }
        assertContains(refused.message!!, "DeletableSessionTree.parts reaches 3 levels deep (maxDepth = 3), and 1 node below that would be left behind")
        assertContains(refused.message!!, "Raise maxDepth")

        // The recursive relationship one view in is checked from where the shelf reaches it.
        val throughShelf = assertFailsWith<IllegalStateException>("$cascade") {
            gom.delete("rc-shelf", DeletableSessionShelf::class.java, cascade)
        }
        assertContains(throughShelf.message!!, "DeletableSessionShelf 'rc-shelf': DeletableSessionTree.parts reaches 3 levels deep")
        assertEquals(seeded, ids(pm), "nothing is deleted when a cascade is refused")
    }

    // The check reads the roots the delete would: a where clause that matches nothing refuses nothing.
    assertEquals(0, gom.delete("rc-s0", DeletableSessionTree::class.java, "session.title = 'archived'", CascadeType.DELETE_ALL))
    assertEquals(seeded, ids(pm))
    // Without a cascade the delete takes the root alone, as it always has.
    assertEquals(1, gom.delete("rc-s0", DeletableSessionTree::class.java, CascadeType.NONE))
}

private fun verifyDeleteOrphanPerNode(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement(TREE))
    val loaded = treeIds(assertNotNull(gom.load("rc-root", DeletableSessionTree::class.java)))

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTree::class.java, CascadeType.DELETE_ORPHAN) }

    // The nested messages are kept: the user outside the view still refers to each. The sessions are
    // deleted, though each relates to its own messages and parts: those are in the cascade.
    assertEquals(loaded - setOf("rc-m1", "rc-m2"), removed)
    assertEquals(4, deleted)
    assertEquals(setOf("rc-user", "rc-m1", "rc-m2"), ids(pm))
}

/**
 * The sessions of a chat store: a user bookmarks a root message, and a message of another session
 * quotes one of a sub-session's. Each attachment hangs off its message alone.
 */
private val BOOKMARKED = """
    CREATE (u:User {id: 'rc-user'}), (t:Session {id: 'rc-other'}), (tm:Message {id: 'rc-quote'})
    CREATE (t)-[:HAS_MESSAGE]->(tm)
    CREATE (s:Session {id: 'rc-root'}), (m0:Message {id: 'rc-m0'}), (m1:Message {id: 'rc-m1'}), (a1:Attachment {id: 'rc-a1'})
    CREATE (s)-[:HAS_MESSAGE]->(m0)
    CREATE (s)-[:HAS_MESSAGE]->(m1)
    CREATE (m1)-[:HAS_ATTACHMENT]->(a1)
    CREATE (m1)-[:BOOKMARKED_BY]->(u)
    CREATE (c:Session {id: 'rc-child'}), (m2:Message {id: 'rc-m2'}), (a2:Attachment {id: 'rc-a2'}), (m3:Message {id: 'rc-m3'}), (a3:Attachment {id: 'rc-a3'})
    CREATE (c)-[:PART_OF]->(s)
    CREATE (c)-[:HAS_MESSAGE]->(m2)
    CREATE (m2)-[:HAS_ATTACHMENT]->(a2)
    CREATE (c)-[:HAS_MESSAGE]->(m3)
    CREATE (m3)-[:HAS_ATTACHMENT]->(a3)
    CREATE (tm)-[:QUOTES]->(m3)
""".trimIndent()

private val OUTSIDE = setOf("rc-user", "rc-other", "rc-quote")

private fun verifyDeleteOrphanKeepsWhatAKeptNodeHolds(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement(BOOKMARKED))

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTreeDeep::class.java, CascadeType.DELETE_ORPHAN) }

    // The bookmarked and the quoted message are kept whole, with their attachments; their sessions go.
    assertEquals(setOf("rc-root", "rc-m0", "rc-child", "rc-m2", "rc-a2"), removed)
    assertEquals(5, deleted)
    assertEquals(OUTSIDE + setOf("rc-m1", "rc-a1", "rc-m3", "rc-a3"), ids(pm))
}

private fun verifyDeleteOrphanKeepsAKeptSubSessionWhole(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement(BOOKMARKED))
    // A sub-session of the sub-session, and a pin on the sub-session from outside.
    pm.execute(
        QuerySpecification.withStatement(
            """
            MATCH (c:Session {id: 'rc-child'}), (u:User {id: 'rc-user'})
            CREATE (g:Session {id: 'rc-grand'}), (m4:Message {id: 'rc-m4'})
            CREATE (g)-[:PART_OF]->(c)
            CREATE (g)-[:HAS_MESSAGE]->(m4)
            CREATE (c)-[:PINNED_BY]->(u)
            """.trimIndent()
        )
    )

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTreeDeep::class.java, CascadeType.DELETE_ORPHAN) }

    // The pinned sub-session keeps everything below it, at every level; only the root's own go.
    assertEquals(setOf("rc-root", "rc-m0"), removed)
    assertEquals(2, deleted)
}

/**
 * A sub-session that is also part of the root directly: the parts below a pinned one form a chain
 * six steps long, longer than the view's fields taken to their depths one after another, and the
 * rule keeps the whole of it.
 */
private fun verifyDeleteOrphanKeepsAChainLongerThanTheView(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (u:User {id: 'rc-user'}), (root:Session {id: 'rc-root'}), (m0:Message {id: 'rc-m0'})
            CREATE (root)-[:HAS_MESSAGE]->(m0)
            CREATE (n0:Session {id: 'rc-n0'}), (n1:Session {id: 'rc-n1'}), (n2:Session {id: 'rc-n2'})
            CREATE (n3:Session {id: 'rc-n3'}), (n4:Session {id: 'rc-n4'}), (n5:Session {id: 'rc-n5'}), (m5:Message {id: 'rc-m5'})
            CREATE (n0)-[:PART_OF]->(root), (n1)-[:PART_OF]->(n0), (n2)-[:PART_OF]->(n1), (n3)-[:PART_OF]->(n2)
            CREATE (n3)-[:PART_OF]->(root), (n4)-[:PART_OF]->(n3), (n5)-[:PART_OF]->(n4)
            CREATE (n5)-[:HAS_MESSAGE]->(m5)
            CREATE (n0)-[:PINNED_BY]->(u)
            """.trimIndent()
        )
    )

    val (removed, deleted) = removedBy(pm) { gom.delete("rc-root", DeletableSessionTree::class.java, CascadeType.DELETE_ORPHAN) }

    // n0 is pinned, and each of n1 … n5 and m5 is one step below the one before it.
    assertEquals(setOf("rc-root", "rc-m0"), removed)
    assertEquals(2, deleted)
}

private fun verifyDeleteOrphanThroughARing(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    // A ring of three: the parts come back to the root at the last level, and with it the root's message.
    pm.execute(QuerySpecification.withStatement(chain(2) + "\nCREATE (s0)-[:PART_OF]->(s2)"))
    val loaded = treeIds(assertNotNull(gom.load("rc-s0", DeletableSessionTree::class.java)))
    assertEquals(6, loaded.size)

    // Each node is deleted, and counted, once: the root and its message are among the targets too.
    assertEquals(loaded to 6, removedBy(pm) { gom.delete("rc-s0", DeletableSessionTree::class.java, CascadeType.DELETE_ORPHAN) })
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry): StatelessGraphObjectManager =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class RecursiveCascadeDeleteNeo4jTest {
    companion object {
        private const val PW = SharedNeo4j.PASSWORD

        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-recursive-cascade", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private val gom get() = buildGom(pm, registry)

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `DELETE_ALL removes what load reads at every level on Neo4j`() = verifyDeleteAllMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches nested views at every level on Neo4j`() = verifyDeleteAllThroughNestedViewsMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches a recursive view one view in on Neo4j`() = verifyDeleteAllFromAShelfMatchesLoad(gom, pm)
    @Test fun `a tree of exactly maxDepth, and a ring, are deleted on Neo4j`() = verifyFullDepthAndCycleAreDeleted(gom, pm)
    @Test fun `a tree deeper than maxDepth is refused on Neo4j`() = verifyTooDeepIsRefused(gom, pm, orphans = true)
    @Test fun `DELETE_ORPHAN keeps a nested node referred to from outside on Neo4j`() = verifyDeleteOrphanPerNode(gom, pm)
    @Test fun `DELETE_ORPHAN deletes a ring back to its root once on Neo4j`() = verifyDeleteOrphanThroughARing(gom, pm)
    @Test fun `DELETE_ORPHAN keeps what a kept node holds on Neo4j`() = verifyDeleteOrphanKeepsWhatAKeptNodeHolds(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a kept sub-session whole on Neo4j`() = verifyDeleteOrphanKeepsAKeptSubSessionWhole(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a chain longer than the view below a kept node on Neo4j`() = verifyDeleteOrphanKeepsAChainLongerThanTheView(gom, pm)
}

class RecursiveCascadeDeleteFalkorDbTest {
    companion object {
        private const val GRAPH = "recursivecascadetest"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-recursive-cascade", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private val gom get() = buildGom(pm, registry)

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `DELETE_ALL removes what load reads at every level on FalkorDB`() = verifyDeleteAllMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches nested views at every level on FalkorDB`() = verifyDeleteAllThroughNestedViewsMatchesLoad(gom, pm, loads = false)
    @Test fun `DELETE_ALL reaches a recursive view one view in on FalkorDB`() = verifyDeleteAllFromAShelfMatchesLoad(gom, pm)
    @Test fun `a tree of exactly maxDepth, and a ring, are deleted on FalkorDB`() = verifyFullDepthAndCycleAreDeleted(gom, pm)
    @Test fun `a tree deeper than maxDepth is refused on FalkorDB`() = verifyTooDeepIsRefused(gom, pm, orphans = true)
    @Test fun `DELETE_ORPHAN keeps a nested node referred to from outside on FalkorDB`() = verifyDeleteOrphanPerNode(gom, pm)
    @Test fun `DELETE_ORPHAN deletes a ring back to its root once on FalkorDB`() = verifyDeleteOrphanThroughARing(gom, pm)
    @Test fun `DELETE_ORPHAN keeps what a kept node holds on FalkorDB`() = verifyDeleteOrphanKeepsWhatAKeptNodeHolds(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a kept sub-session whole on FalkorDB`() = verifyDeleteOrphanKeepsAKeptSubSessionWhole(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a chain longer than the view below a kept node on FalkorDB`() = verifyDeleteOrphanKeepsAChainLongerThanTheView(gom, pm)
}

class RecursiveCascadeDeleteMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-recursive-cascade", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private val gom get() = buildGom(pm, registry)

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `DELETE_ALL removes what load reads at every level on Memgraph`() = verifyDeleteAllMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches nested views at every level on Memgraph`() = verifyDeleteAllThroughNestedViewsMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches a recursive view one view in on Memgraph`() = verifyDeleteAllFromAShelfMatchesLoad(gom, pm)
    @Test fun `a tree of exactly maxDepth, and a ring, are deleted on Memgraph`() = verifyFullDepthAndCycleAreDeleted(gom, pm)
    @Test fun `a tree deeper than maxDepth is refused on Memgraph`() = verifyTooDeepIsRefused(gom, pm, orphans = false)
}

@OracleTests
class RecursiveCascadeDeleteOracleTest {
    private val registry = SubtypeRegistry()
    private val store = OracleTestDatabase.graphManager("contracts", registry)
    private val pm = OracleTestDatabase.fixtures(store)
    private val gom = buildGom(store, registry)

    @BeforeEach fun clean() = OracleTestDatabase.clear(store)

    @Test fun `DELETE_ALL removes what load reads at every level on Oracle`() = verifyDeleteAllMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches nested views at every level on Oracle`() = verifyDeleteAllThroughNestedViewsMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches a recursive view one view in on Oracle`() = verifyDeleteAllFromAShelfMatchesLoad(gom, pm)
    @Test fun `a tree of exactly maxDepth, and a ring, are deleted on Oracle`() = verifyFullDepthAndCycleAreDeleted(gom, pm)
    @Test fun `a tree deeper than maxDepth is refused on Oracle`() = verifyTooDeepIsRefused(gom, pm, orphans = true)
    @Test fun `DELETE_ORPHAN keeps a nested node referred to from outside on Oracle`() = verifyDeleteOrphanPerNode(gom, pm)
    @Test fun `DELETE_ORPHAN deletes a ring back to its root once on Oracle`() = verifyDeleteOrphanThroughARing(gom, pm)
    @Test fun `DELETE_ORPHAN keeps what a kept node holds on Oracle`() = verifyDeleteOrphanKeepsWhatAKeptNodeHolds(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a kept sub-session whole on Oracle`() = verifyDeleteOrphanKeepsAKeptSubSessionWhole(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a chain longer than the view below a kept node on Oracle`() = verifyDeleteOrphanKeepsAChainLongerThanTheView(gom, pm)
}

@PostgresTests
class RecursiveCascadeDeletePostgresTest {
    private val registry = SubtypeRegistry()
    private val store = PostgresTestDatabase.graphManager("contracts", registry)
    private val pm = PostgresTestDatabase.fixtures(store)
    private val gom = buildGom(store, registry)

    @BeforeEach fun clean() = PostgresTestDatabase.clear(store)

    @Test fun `DELETE_ALL removes what load reads at every level on Postgres`() = verifyDeleteAllMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches nested views at every level on Postgres`() = verifyDeleteAllThroughNestedViewsMatchesLoad(gom, pm)
    @Test fun `DELETE_ALL reaches a recursive view one view in on Postgres`() = verifyDeleteAllFromAShelfMatchesLoad(gom, pm)
    @Test fun `a tree of exactly maxDepth, and a ring, are deleted on Postgres`() = verifyFullDepthAndCycleAreDeleted(gom, pm)
    @Test fun `a tree deeper than maxDepth is refused on Postgres`() = verifyTooDeepIsRefused(gom, pm, orphans = true)
    @Test fun `DELETE_ORPHAN keeps a nested node referred to from outside on Postgres`() = verifyDeleteOrphanPerNode(gom, pm)
    @Test fun `DELETE_ORPHAN deletes a ring back to its root once on Postgres`() = verifyDeleteOrphanThroughARing(gom, pm)
    @Test fun `DELETE_ORPHAN keeps what a kept node holds on Postgres`() = verifyDeleteOrphanKeepsWhatAKeptNodeHolds(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a kept sub-session whole on Postgres`() = verifyDeleteOrphanKeepsAKeptSubSessionWhole(gom, pm)
    @Test fun `DELETE_ORPHAN keeps a chain longer than the view below a kept node on Postgres`() = verifyDeleteOrphanKeepsAChainLongerThanTheView(gom, pm)
}
