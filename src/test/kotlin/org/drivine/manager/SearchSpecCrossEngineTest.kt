package org.drivine.manager

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.cypher.spec.SearchSpec
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.FullTextIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * The spec's `cypher/search.yaml` on each store, through [StatelessGraphObjectManager.loadMatching]: each case's searches
 * find the nodes it says, and each store ranks the corpus as closely to Neo4j's recorded ranking as its bar says. The
 * steps search.yaml's `today` says a store fails are held in [SearchSpec.HELD], and must still fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SearchSpecContract {

    /** The store, as search.yaml names it. */
    abstract val store: String

    abstract val pm: NonTransactionalPersistenceManager

    /** Whether the full-text indexes are made: on every store but `oracle`, and on Postgres both made and not. */
    open val indexed = true

    /** Empties the graph. */
    open fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    /** Writes [cypher], bound with [parameters], to the store: through compiled Cypher on a SQL store. */
    open fun write(cypher: String, parameters: Map<String, Any?> = emptyMap()) {
        pm.execute(QuerySpecification.withStatement(cypher.trimIndent()).bind(parameters))
    }

    /** Waits for the indexes to hold what was written, where they fill after the write. */
    open fun settle() {}

    private val manager by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    private val spec = SearchSpec.spec

    /**
     * Empties the graph, makes [indexes] where the store makes them, writes, and waits for the indexes to hold it. A
     * full-text index on one of their labels that another test left on a shared container is dropped first: on CI's shared
     * Memgraph, MultiLabelRootSearchCrossEngineTest's `Paper(body)` changed what a search of `Paper(title, body)` found.
     */
    private fun ready(indexes: List<SearchSpec.Index>, write: () -> Unit) {
        clear()
        if (indexed) {
            val labels = indexes.map { it.label }.toSet()
            pm.indexes.list()
                .filter { it.kind == SchemaItemKind.FULLTEXT_INDEX && it.label in labels }
                .filter { info -> indexes.none { it.label == info.label && it.properties == info.properties } }
                .forEach { pm.indexes.drop(FullTextIndexSpec(it.label, it.properties)) }
            indexes.forEach { pm.indexes.ensure(FullTextIndexSpec(it.label, it.properties)) }
        }
        write()
        if (indexed) settle()
    }

    private fun model(label: String, properties: List<String>): Class<*> =
        requireNotNull(MODELS[label to properties]) { "No model class for a search of $label on $properties" }

    /** A search's hits, by id, best first, with no threshold. */
    private fun search(label: String, properties: List<String>, query: String, topK: Int): List<String> =
        manager.loadMatching(model(label, properties), query, topK, 0.0).map { (it.value as SpecNode).id }

    /**
     * A test for each case. Each search's model class is found first, so that one the file names and [MODELS] lacks fails
     * the run, and isn't taken, in a held step, for the store's failure.
     */
    @TestFactory
    fun cases(): List<DynamicTest> {
        spec.cases.flatMap { it.steps }.forEach { model(it.search.label, it.search.properties) }
        return spec.tests(store, before = { ready(spec.indexes) { write(spec.graph) } }) {
            search(it.label, it.properties, it.query, it.topK)
        }
    }

    /**
     * A full-text index of another shape on a label search.yaml searches, left by another test, is gone once a case is
     * ready. The file's own indexes are dropped first: on FalkorDB, whose full-text index covers a whole label, one a
     * case made earlier would take in the one left, and nothing would be left to drop.
     */
    @Test
    fun `a case searches only the indexes it makes, not one another test left on its label`() {
        assumeTrue(indexed, "$store makes no full-text index")
        val several = spec.indexes.filter { it.properties.size > 1 }
        assertTrue(several.isNotEmpty(), "search.yaml has no index over several properties")
        val labels = spec.indexes.map { it.label }.toSet()
        fun held() = pm.indexes.list().filter { it.kind == SchemaItemKind.FULLTEXT_INDEX && it.label in labels }
            .map { it.label to it.properties }.toSet()
        spec.indexes.forEach { pm.indexes.drop(FullTextIndexSpec(it.label, it.properties)) }
        val left = several.map { it.label to it.properties.drop(1) }
        left.forEach { (label, properties) -> pm.indexes.ensure(FullTextIndexSpec(label, properties)) }
        assertTrue(held().containsAll(left), "$store holds ${held()}, not the indexes left")
        ready(spec.indexes) {}
        assertTrue(held() == spec.indexes.map { it.label to it.properties }.toSet(), "$store holds ${held()}")
    }

    @Test
    fun `the store ranks the corpus as closely to Neo4j as its bar says`() {
        val ranking = spec.ranking
        ready(listOf(ranking.index)) { write(ranking.write, mapOf("rows" to SearchSpec.corpus)) }
        val ranked = ranking.queries.associate { it.query to search(ranking.search.label, ranking.search.properties, it.query, ranking.top) }
        val agreement = ranking.agreement(ranked)
        println("${this::class.simpleName}, on $store: $agreement")
        val bar = ranking.bar(store)
        assumeTrue(bar != null, "search.yaml holds $store's ranking to no bar")
        assertTrue(agreement.sameTop >= bar!!.sameTop && agreement.overlap >= bar.overlap, "On $store, $agreement; its bar is ${bar.sameTop} and ${bar.overlap}")
    }

    companion object {
        /** The model class each search of search.yaml loads: its label and the properties of its full-text index. */
        internal val MODELS: Map<Pair<String, List<String>>, Class<*>> = mapOf(
            ("Doc" to listOf("body")) to SpecDoc::class.java,
            ("Paper" to listOf("title", "body")) to SpecPaper::class.java,
            ("Article" to listOf("body")) to SpecArticle::class.java,
        )
    }
}

/** A node search.yaml names its hits by: its `id`. */
interface SpecNode { val id: String }

@NodeFragment(labels = ["Doc"])
data class SpecDoc(@NodeId override val id: String, @FullTextIndex val body: String) : SpecNode

/** Searched over two properties, either of which a node may lack. */
@FullTextIndex(properties = ["title", "body"])
@NodeFragment(labels = ["Paper"])
data class SpecPaper(@NodeId override val id: String, val title: String?, val body: String?) : SpecNode

@NodeFragment(labels = ["Article"])
data class SpecArticle(@NodeId override val id: String, val title: String?, @FullTextIndex val body: String) : SpecNode

/** A graph engine's manager over Bolt, ended after the class. */
abstract class BoltSearchSpecContract : SearchSpecContract() {
    abstract val provider: Neo4jConnectionProvider
    abstract val type: DatabaseType
    override val pm by lazy { NonTransactionalPersistenceManager(provider, type.value.lowercase(), type, SubtypeRegistry()) }

    @AfterAll
    fun end() = provider.end()
}

class Neo4jSearchSpecTest : BoltSearchSpecContract() {
    override val store = "neo4j"
    override val type = DatabaseType.NEO4J
    override val provider by lazy {
        val container = SharedNeo4j.forClass()
        Neo4jConnectionProvider(
            name = "neo-search-spec", type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
            user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j", config = emptyMap(),
            subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.NEO4J_5,
        )
    }

    /** Neo4j's full-text index fills after the write. */
    override fun settle() {
        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
    }
}

class MemgraphSearchSpecTest : BoltSearchSpecContract() {
    override val store = "memgraph"
    override val type = DatabaseType.MEMGRAPH
    override val provider by lazy {
        val container = SharedMemgraph.forClass()
        Neo4jConnectionProvider(
            name = "memgraph-search-spec", type = DatabaseType.MEMGRAPH, host = container.host, port = container.getMappedPort(7687),
            user = "", password = "", database = null, config = emptyMap(),
            subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.MEMGRAPH,
        )
    }
}

class FalkorDbSearchSpecTest : SearchSpecContract() {
    override val store = "falkordb"
    private val provider by lazy {
        val container = SharedFalkorDb.forClass()
        FalkorDbConnectionProvider(
            name = "falkor-search-spec", host = container.host, port = container.getMappedPort(6379),
            password = null, graphName = "searchspec", subtypeRegistry = SubtypeRegistry(),
        )
    }
    override val pm by lazy { NonTransactionalPersistenceManager(provider, "searchspec", DatabaseType.FALKORDB, SubtypeRegistry()) }

    @AfterAll
    fun end() = provider.end()
}

/** A SQL store: the graph written through compiled Cypher, as the cross-engine tests write it. */
abstract class SqlSearchSpecContract : SearchSpecContract() {
    abstract fun fixtures(): PersistenceManager

    override fun clear() = org.drivine.test.SqlCypherFixtures.clear(pm)

    override fun write(cypher: String, parameters: Map<String, Any?>) {
        fixtures().query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))
    }
}

@PostgresTests
class PostgresSearchSpecTest : SqlSearchSpecContract() {
    override val store = "postgres"
    override val pm by lazy { PostgresTestDatabase.graphManager("searchspeci") }
    override fun fixtures() = PostgresTestDatabase.fixtures(pm)
}

/** Postgres with no full-text index made: the index only narrows the nodes read, and the hits are the same. */
@PostgresTests
class PostgresUnindexedSearchSpecTest : SqlSearchSpecContract() {
    override val store = "postgres"
    override val indexed = false
    override val pm by lazy { PostgresTestDatabase.graphManager("searchspec") }
    override fun fixtures() = PostgresTestDatabase.fixtures(pm)
}

class SqliteSearchSpecTest : SqlSearchSpecContract() {
    override val store = "sqlite"
    override val pm by lazy { SqliteTestDatabase.graphManager("searchspec") }
    override fun fixtures() = SqliteTestDatabase.fixtures(pm)
}

/** Oracle with no full-text index made, which Drivine scores by BM25. */
@OracleTests
class OracleSearchSpecTest : SqlSearchSpecContract() {
    override val store = "oracle"
    override val indexed = false
    override val pm by lazy { OracleTestDatabase.graphManager("searchspec") }
    override fun fixtures() = OracleTestDatabase.fixtures(pm)
}

/** Oracle with the full-text index made, which is Oracle Text: it holds what is committed, and each write commits. */
@OracleTests
class OracleTextSearchSpecTest : SqlSearchSpecContract() {
    override val store = "oracle-text"
    override val pm by lazy { OracleTestDatabase.graphManager("searchspect") }
    override fun fixtures() = OracleTestDatabase.fixtures(pm)
}
