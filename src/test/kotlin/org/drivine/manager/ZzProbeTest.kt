package org.drivine.manager

import java.time.LocalDate
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.FieldWrite
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PatchSpec
import org.drivine.query.dsl.PropertyConditionBuilder
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
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
import sample.stateless.JavaNote
import sample.stateless.JavaStampedRecord

@NodeFragment(labels = ["Probe"])
data class Probe(
    @NodeId val id: String,
    val name: String? = null,
    val count: Long? = null,
    val open: Boolean? = null,
    val isLive: Boolean? = null,
    val score: Double? = null,
    val names: List<String>? = null,
    val day: LocalDate? = null,
    @NodeStamp val stamp: String? = null,
)

open class ProbeBase(@NodeId open val id: String, open val inherited: String? = null)

@NodeFragment(labels = ["ProbeChild"])
class ProbeChild(id: String, inherited: String? = null, val own: String? = null) : ProbeBase(id, inherited)

class ProbeDsl(override val nodeAlias: String = "n") : NodeReference {
    val name = StringPropertyReference(nodeAlias, "name")
    val count = PropertyReference<Long>(nodeAlias, "count")
    val open = PropertyReference<Boolean>(nodeAlias, "open")
    val isLive = PropertyReference<Boolean>(nodeAlias, "isLive")
    val score = PropertyReference<Double>(nodeAlias, "score")
    val names = PropertyReference<List<String>>(nodeAlias, "names")
    val day = PropertyReference<LocalDate>(nodeAlias, "day")
}

class ZzPatchedValuesProbe {
    private fun attempt(name: String, block: () -> Any?) {
        val result = try { block().toString() } catch (e: Throwable) { "THREW ${e::class.simpleName}: ${e.message}" }
        println("PROBE values $name => $result")
    }

    @Test
    fun probe() {
        val mapper = Neo4jObjectMapper.instance
        val dsl = ProbeDsl()
        attempt("kotlin isLive") { PatchedValues(Probe::class.java, mapper).of(listOf(dsl.isLive.assign(true))) }
        attempt("kotlin open") { PatchedValues(Probe::class.java, mapper).of(listOf(dsl.open.assign(true))) }
        attempt("java class text") { PatchedValues(JavaNote::class.java, mapper).of(listOf(StringPropertyReference("n", "text").assign("x"))) }
        attempt("java record text") { PatchedValues(JavaStampedRecord::class.java, mapper).of(listOf(StringPropertyReference("n", "text").assign("x"))) }
        attempt("inherited") { PatchedValues(ProbeChild::class.java, mapper).of(listOf(StringPropertyReference("n", "inherited").assign("x"), StringPropertyReference("n", "own").assign("y"))) }
        attempt("save map of Probe") { mapper.convertValue(Probe("p", isLive = true, open = true), Map::class.java) }
    }
}

abstract class ZzProbeContract {
    abstract val pm: PersistenceManager
    abstract val engine: String
    open val cypher: Boolean = true

    private val stateless get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val dsl = ProbeDsl()

    private fun out(name: String, block: () -> Any?) {
        val result = try { block().toString() } catch (e: Throwable) { "THREW ${e::class.simpleName}: ${(e.message ?: "").take(300)} / cause ${e.cause?.message?.take(300)}" }
        println("PROBE $engine $name => $result")
    }

    private fun PatchResult.short() = if (!found) "absent" else if (!applied) "unmet" else if (changed) "changed" else "unchanged"

    private fun patch(id: String, write: FieldWrite, vararg conditions: PropertyConditionBuilder): String =
        stateless.patch(id, Probe::class.java, dsl, java.util.function.Consumer<PatchSpec<ProbeDsl>> { it.set(write).onlyIf(*conditions) }).short()

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun predicates() {
        stateless.save(Probe("p1", name = "Ada", count = 5, open = true, score = 1.5, names = listOf("a", "b")))
        var round = 100L
        fun met(name: String, expected: Boolean, condition: () -> PropertyConditionBuilder) =
            out("predicate $name (expect ${if (expected) "changed" else "unmet"})") { patch("p1", dsl.count.assign(round++), condition()) }
        met("hasPrefix", true) { dsl.name.hasPrefix("Ad") }
        met("hasSuffix", true) { dsl.name.hasSuffix("da") }
        met("hasSubstring", true) { dsl.name.hasSubstring("d") }
        met("isIn hit", true) { dsl.name.isIn(listOf("Ada", "Bob")) }
        met("isIn miss", false) { dsl.name.isIn(listOf("Bob")) }
        met("isIn empty", false) { dsl.name.isIn(emptyList()) }
        met("count gt 4", true) { dsl.count.isGreaterThan(4) }
        met("count lt 5", false) { dsl.count.isLessThan(5) }
        met("open eq true", true) { dsl.open.isEqualTo(true) }
        met("open eq false", false) { dsl.open.isEqualTo(false) }
        met("score atLeast 1.5", true) { dsl.score.isAtLeast(1.5) }
        met("name eq null", false) { dsl.name.isEqualTo(null) }
        met("name neq Bob", true) { dsl.name.isNotEqualTo("Bob") }
        met("name eq 5000 chars", false) { dsl.name.isEqualTo("x".repeat(5000)) }
        met("name eq 40000 chars", false) { dsl.name.isEqualTo("x".repeat(40000)) }
        met("isIn long list", true) { dsl.name.isIn((1..6000).map { "name-$it" } + "Ada") }
        met("count isIn numbers", false) { dsl.count.isIn(listOf(1L, 2L)) }
        out("final count") { stateless.load("p1", Probe::class.java)?.count }
    }

    @Test
    fun values() {
        stateless.save(Probe("p1"))
        val day = LocalDate.of(2026, 3, 1)
        val long = "y".repeat(40000)
        fun twice(name: String, write: () -> FieldWrite) {
            out("value $name first (expect changed)") { patch("p1", write()) }
            out("value $name again (expect unchanged)") { patch("p1", write()) }
        }
        twice("names list") { dsl.names.assign(listOf("x", "y")) }
        twice("score double") { dsl.score.assign(2.5) }
        twice("open boolean") { dsl.open.assign(false) }
        twice("day") { dsl.day.assign(day) }
        twice("isLive") { dsl.isLive.assign(true) }
        twice("empty string") { dsl.name.assign("") }
        twice("long string") { dsl.name.assign(long) }
        twice("empty list") { dsl.names.assign(emptyList()) }
        out("loaded") { stateless.load("p1", Probe::class.java)?.let { it.copy(name = "len ${it.name?.length}", stamp = null) } }
        stateless.save(Probe("p2", name = "Ada", count = 5, open = true, isLive = false, score = 1.5, names = listOf("a", "b"), day = day))
        out("saved then patched names (expect unchanged)") { patch("p2", dsl.names.assign(listOf("a", "b"))) }
        out("saved then patched score (expect unchanged)") { patch("p2", dsl.score.assign(1.5)) }
        out("saved then patched day (expect unchanged)") { patch("p2", dsl.day.assign(day)) }
        out("saved then patched open (expect unchanged)") { patch("p2", dsl.open.assign(true)) }
        out("saved then patched isLive (expect unchanged)") { patch("p2", dsl.isLive.assign(false)) }
        out("saved then patched count (expect unchanged)") { patch("p2", dsl.count.assign(5)) }
        out("predicate on saved day eq") { patch("p2", dsl.count.assign(6), dsl.day.isEqualTo(day)) }
        out("unmet with clear") { patch("p2", dsl.name.cleared(), dsl.count.isEqualTo(99)) }
        out("name after unmet clear") { stateless.load("p2", Probe::class.java)?.name }
    }

    @Test
    fun sharedId() {
        if (!cypher) return
        pm.execute(QuerySpecification.withStatement("CREATE (:Probe {id: 'dup', name: 'Ada', count: 1}), (:Probe {id: 'dup', name: 'Bob', count: 1})"))
        out("shared id, one meets") { stateless.patch("dup", Probe::class.java, dsl, java.util.function.Consumer<PatchSpec<ProbeDsl>> { it.set(dsl.count.assign(2)).onlyIf(dsl.name.isEqualTo("Ada")) }) .short() }
        out("shared id counts") { pm.query(QuerySpecification.withStatement("MATCH (n:Probe {id: 'dup'}) RETURN n.name + '=' + toString(n.count) + ' stamp ' + coalesce(n.`__drivine.stamp`, 'none') ORDER BY n.name").transform(String::class.java)) }
    }
}

@Testcontainers
class ZzProbeNeo4jTest : ZzProbeContract() {
    companion object {
        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest")).apply { withAdminPassword("probeone1") }
        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-probe", type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = "probeone1", database = "neo4j", config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }
    override val pm get() = manager
    override val engine = "neo4j5"
}

@Testcontainers
class ZzProbeNeo4j4Test : ZzProbeContract() {
    companion object {
        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:4.4")).apply { withAdminPassword("probefour1") }
        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo4-probe", type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = "probefour1", database = "neo4j", config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_4,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }
    override val pm get() = manager
    override val engine = "neo4j4"
}

@Testcontainers
class ZzProbeFalkorDbTest : ZzProbeContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest")).withExposedPorts(6379)
        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(name = "falkor-probe", host = container.host, port = container.getMappedPort(6379), password = null, graphName = "probe", subtypeRegistry = registry)
            manager = NonTransactionalPersistenceManager(provider, "probe", DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }
    override val pm get() = manager
    override val engine = "falkordb"
}

@Testcontainers
class ZzProbeMemgraphTest : ZzProbeContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest")).withExposedPorts(7687).waitingFor(Wait.forListeningPort())
        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-probe", type = DatabaseType.MEMGRAPH, host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(), cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }
    override val pm get() = manager
    override val engine = "memgraph"
}

@OracleTests
class ZzProbeOracleTest : ZzProbeContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))
    override val engine = "oracle"
    override val cypher = false
}
