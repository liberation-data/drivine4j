package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.containsIgnoreCase
import org.drivine.query.dsl.eqIgnoreCase
import org.drivine.query.dsl.field
import org.drivine.query.dsl.hasAnyLabel
import org.drivine.query.dsl.hasElement
import org.drivine.query.dsl.hasItem
import org.drivine.query.dsl.matches
import org.drivine.query.dsl.not
import org.drivine.query.dsl.notIn
import org.drivine.query.dsl.predicate
import org.drivine.query.dsl.predicateOn
import org.drivine.query.dsl.property
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
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
import sample.dynamicfilter.RecordNode
import sample.dynamicfilter.RecordNodeQueryDsl
import kotlin.test.assertEquals

/**
 * The dynamic-predicate escape hatch ([property] / [predicate]) verified cross-engine (Neo4j /
 * FalkorDB / Memgraph): filtering on arbitrary `@PropertyBag` metadata keys — stored as dotted node
 * properties (`metadata.<key>`) — resolves to the backtick-quoted property on every engine, covers the
 * comparison + string operators, composes with a typed accessor, and matches the typed `key()` form.
 */
private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager, supportsRegex: Boolean = true) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:Record:Premium {id: 'a', title: 'Alpha', section_id: 's1', tags: ['kotlin', 'graph'], `metadata.source`: 'web',  `metadata.rank`: 5})
            CREATE (:Record          {id: 'b', title: 'Beta',  section_id: 's2', tags: ['python'],          `metadata.source`: 'book', `metadata.rank`: 2})
            CREATE (:Record:Premium {id: 'c', title: 'Gamma', section_id: 's1', tags: ['kotlin', 'rust'],  `metadata.source`: 'web',  `metadata.rank`: 8})
            """.trimIndent()
        )
    )
    val dsl = RecordNodeQueryDsl.INSTANCE

    // Dynamic eq on a bagged key → n.`metadata.source` = $param.
    val web = gom.loadAll(RecordNode::class.java, dsl) { where { query.property("metadata.source") eq "web" } }
    assertEquals(setOf("a", "c"), web.map { it.id }.toSet())

    // predicate() with a comparison operator on a numeric bagged key.
    val topRank = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.predicate("metadata.rank", ComparisonOperator.GREATER_THAN, 6) }
    }
    assertEquals(setOf("c"), topRank.map { it.id }.toSet())

    // predicate() with a string operator the untyped reference doesn't expose.
    val contains = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.predicate("metadata.source", ComparisonOperator.CONTAINS, "oo") }
    }
    assertEquals(setOf("b"), contains.map { it.id }.toSet())

    // Equivalence: the typed @PropertyBag key() and the dynamic property() resolve the same property.
    val viaKey = gom.loadAll(RecordNode::class.java, dsl) { where { query.metadata.key("source") eq "book" } }
    val viaDynamic = gom.loadAll(RecordNode::class.java, dsl) { where { query.property("metadata.source") eq "book" } }
    assertEquals(setOf("b"), viaKey.map { it.id }.toSet())
    assertEquals(viaKey.map { it.id }.toSet(), viaDynamic.map { it.id }.toSet())

    // Dynamic predicate composes (AND) with a typed accessor.
    val combined = gom.loadAll(RecordNode::class.java, dsl) {
        where {
            query.property("metadata.source") eq "web"
            query.id neq "a"
        }
    }
    assertEquals(setOf("c"), combined.map { it.id }.toSet())

    // ----- Phase B operators, executed cross-engine -----

    // NOT_IN.
    val notBook = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.property("metadata.source") notIn listOf("book") }
    }
    assertEquals(setOf("a", "c"), notBook.map { it.id }.toSet())

    // MATCHES (regex, full-match). Not supported on FalkorDB (no `=~`).
    if (supportsRegex) {
        val startsA = gom.loadAll(RecordNode::class.java, dsl) { where { query.title matches "A.*" } }
        assertEquals(setOf("a"), startsA.map { it.id }.toSet())
    }

    // Case-insensitive contains + equals.
    val ci = gom.loadAll(RecordNode::class.java, dsl) { where { query.title containsIgnoreCase "ALPH" } }
    assertEquals(setOf("a"), ci.map { it.id }.toSet())
    val eqCi = gom.loadAll(RecordNode::class.java, dsl) { where { query.title eqIgnoreCase "beta" } }
    assertEquals(setOf("b"), eqCi.map { it.id }.toSet())

    // not { } — negation of a leaf.
    val notWeb = gom.loadAll(RecordNode::class.java, dsl) {
        where { not { query.property("metadata.source") eq "web" } }
    }
    assertEquals(setOf("b"), notWeb.map { it.id }.toSet())

    // hasAnyLabel — matches any of the given labels (a, c carry :Premium).
    val premium = gom.loadAll(RecordNode::class.java, dsl) { where { query.hasAnyLabel("Premium") } }
    assertEquals(setOf("a", "c"), premium.map { it.id }.toSet())

    // Composition: hasAnyLabel AND a negated metadata predicate.
    val premiumNotTop = gom.loadAll(RecordNode::class.java, dsl) {
        where {
            query.hasAnyLabel("Premium")
            not { query.predicate("metadata.rank", ComparisonOperator.GREATER_THAN, 6) }
        }
    }
    assertEquals(setOf("a"), premiumNotTop.map { it.id }.toSet())

    // ----- Model-aware key resolution: field() / predicateOn() -----

    // A promoted @GraphProperty field resolves by BOTH its Kotlin name and its on-disk name → section_id.
    val byKotlinName = gom.loadAll(RecordNode::class.java, dsl) { where { query.field("sectionId") eq "s1" } }
    assertEquals(setOf("a", "c"), byKotlinName.map { it.id }.toSet())
    val byOnDiskName = gom.loadAll(RecordNode::class.java, dsl) { where { query.field("section_id") eq "s1" } }
    assertEquals(setOf("a", "c"), byOnDiskName.map { it.id }.toSet())

    // A free-form key (no matching field) resolves through the single @PropertyBag prefix → metadata.source,
    // and matches the explicit stored-path form exactly.
    val bySource = gom.loadAll(RecordNode::class.java, dsl) { where { query.field("source") eq "book" } }
    val byStoredPath = gom.loadAll(RecordNode::class.java, dsl) { where { query.property("metadata.source") eq "book" } }
    assertEquals(setOf("b"), bySource.map { it.id }.toSet())
    assertEquals(byStoredPath.map { it.id }.toSet(), bySource.map { it.id }.toSet())

    // predicateOn resolves the key the same way, with the full operator set.
    val sourceContains = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.predicateOn("source", ComparisonOperator.CONTAINS, "oo") }
    }
    assertEquals(setOf("b"), sourceContains.map { it.id }.toSet())

    // ----- Dynamic list-membership: HAS_ELEMENT == typed hasItem -----

    // Typed hasItem on a list-valued property.
    val typedTag = gom.loadAll(RecordNode::class.java, dsl) { where { query.tags hasItem "kotlin" } }
    assertEquals(setOf("a", "c"), typedTag.map { it.id }.toSet())

    // Dynamic HAS_ELEMENT via predicateOn (resolves the key) — same rows.
    val dynTagOn = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.predicateOn("tags", ComparisonOperator.HAS_ELEMENT, "kotlin") }
    }
    assertEquals(typedTag.map { it.id }.toSet(), dynTagOn.map { it.id }.toSet())

    // Dynamic HAS_ELEMENT via the hasElement infix on an untyped reference — same rows.
    val dynTagInfix = gom.loadAll(RecordNode::class.java, dsl) {
        where { query.field("tags") hasElement "kotlin" }
    }
    assertEquals(typedTag.map { it.id }.toSet(), dynTagInfix.map { it.id }.toSet())
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class DynamicPropertyFilterNeo4jTest {
    companion object {
        private const val PW = "dynfiltertest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PW) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-dynfilter", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `dynamic property filters resolve on Neo4j`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class DynamicPropertyFilterFalkorDbTest {
    companion object {
        private const val GRAPH = "dynfiltertest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-dynfilter", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    // FalkorDB has no `=~` operator, so regex (MATCHES / embabel Like) is unsupported there.
    @Test fun `dynamic property filters resolve on FalkorDB`() = verify(buildGom(pm, registry), pm, supportsRegex = false)
}

@Testcontainers
class DynamicPropertyFilterMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-dynfilter", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `dynamic property filters resolve on Memgraph`() = verify(buildGom(pm, registry), pm)
}
