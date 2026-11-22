package org.drivine.schema

import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test

@NodeFragment(labels = ["Article"])
data class DeclArticle(
    @NodeId val id: String,
    @Unique val slug: String,
    @RangeIndex val title: String,
    @VectorIndex val embedding: List<Float>?,
    @FullTextIndex val body: String,
)

@NodeFragment(labels = ["Account"])
data class DeclAccount(
    @NodeId @Unique val id: String,
    val name: String,
)

@NodeFragment(labels = ["Ticket"], nodeIdIndex = false)
data class DeclTicket(
    @NodeId val id: String,
)

@NodeFragment(labels = ["Message"])
@Unique(properties = ["sessionId", "sequence"])
@FullTextIndex(properties = ["subject", "body"], analyzer = "english")
data class DeclMessage(
    @NodeId val id: String,
    val sessionId: String,
    val sequence: Long,
    val subject: String,
    val body: String,
)

@NodeFragment(labels = ["Customer"])
data class DeclCustomer(
    @NodeId val id: String,
    @RangeIndex val email: String,
)

@NodeFragment(labels = ["Customer"])
data class DeclCustomerView(
    @NodeId val id: String,
    @RangeIndex val email: String,
)

@NodeFragment(labels = ["Member"])
data class DeclMember(
    @NodeId val id: String,
    @CaseInsensitive @Unique(ignoreCase = true) val email: String,
)

/**
 * Each case of the spec's `declarations.yaml` (liberation-data/drivine-cypher-spec#135), declared in drivine4j's own API,
 * gives the planner the catalogs and the items ensured at runtime the case pins: what the planner decides from, so a store
 * one library indexed is up to date for the others. drivine4j's are the reference. A case with no declaration here fails,
 * so a case the spec adds is never passed over. No database is spoken to: the request is read as the planner is given it.
 */
class DeclarationsGoldenTest {

    private val pinned = com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        .readTree(javaClass.getResourceAsStream("/cypher/declarations.yaml"))

    private fun provider(name: String) = object : ConnectionProvider {
        override val name = name
        override val type = DatabaseType.NEO4J
        override val subtypeRegistry: SubtypeRegistry? = null
        override val cypherDialect = CypherDialect.NEO4J_5
        override fun connect(): Connection = error("not connected")
        override fun end() {}
    }

    private fun manager(catalogs: List<SchemaCatalog>): SchemaManager {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap())).also { it.register(provider("default")) }
        return SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs)
    }

    private val dims = VectorDimensionProvider { _, _ -> 384 }

    @Test
    fun `each case declared in drivine4j's API gives the planner the catalogs it pins`() {
        val long = "AVeryLongLabelNameThatGoesOnAndOnForTheSakeOfAnIdentifierLimit"
        val cases: List<Triple<String, List<SchemaCatalog>, List<org.drivine.schema.SchemaItemSpec>>> = listOf(
            Triple("range", listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name"))), emptyList()),
            Triple("range named", listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name", name = "person_name_idx"))), emptyList()),
            Triple("range composite", listOf(SchemaCatalog.of(RangeIndexSpec("Person", listOf("last", "first")))), emptyList()),
            Triple("range long name", listOf(SchemaCatalog.of(RangeIndexSpec(long, listOf("aPropertyWithALongName", "anotherPropertyWithALongName")))), emptyList()),
            Triple("unique", listOf(SchemaCatalog.of(UniquenessConstraintSpec("Person", "email"))), emptyList()),
            Triple("unique composite", listOf(SchemaCatalog.of(UniquenessConstraintSpec("Person", listOf("email", "tenant")))), emptyList()),
            Triple("unique named", listOf(SchemaCatalog.of(UniquenessConstraintSpec("Person", "email", name = "person_email_unique"))), emptyList()),
            Triple("ignore case", listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name", ignoreCase = true), UniquenessConstraintSpec("Person", "email", ignoreCase = true))), emptyList()),
            Triple("vector", listOf(SchemaCatalog.of(VectorIndexSpec("Chunk", "embedding", 384, SimilarityFunction.EUCLIDEAN))), emptyList()),
            Triple("vector dimensions only", listOf(SchemaCatalog.of(VectorIndexSpec("Chunk", "embedding", 384))), emptyList()),
            Triple(
                "vector tuned",
                listOf(
                    SchemaCatalog.of(
                        VectorIndexSpec(
                            "Chunk", "embedding", 384, SimilarityFunction.COSINE, hnswM = 16, hnswEfConstruction = 200,
                            engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false), FalkorDbVectorOptions(efRuntime = 10)),
                        ),
                    ),
                ),
                emptyList(),
            ),
            Triple("full-text", listOf(SchemaCatalog.of(FullTextIndexSpec("Article", listOf("title", "body"), analyzer = "english"))), emptyList()),
            Triple("full-text default analyzer", listOf(SchemaCatalog.of(FullTextIndexSpec("Article", "body"))), emptyList()),
            Triple("text", listOf(SchemaCatalog.of(TextIndexSpec("Person", "name"))), emptyList()),
            Triple("fragment", listOf(SchemaCatalog.fromFragments(dims, DeclArticle::class)), emptyList()),
            Triple("fragment id served by a constraint", listOf(SchemaCatalog.fromFragments(DeclAccount::class)), emptyList()),
            Triple("fragment without its id's index", listOf(SchemaCatalog.fromFragments(DeclTicket::class)), emptyList()),
            Triple("fragment class items", listOf(SchemaCatalog.fromFragments(DeclMessage::class)), emptyList()),
            Triple("fragments merged", listOf(SchemaCatalog.fromFragments(DeclCustomer::class, DeclCustomerView::class)), emptyList()),
            Triple("fragment ignoring case", listOf(SchemaCatalog.fromFragments(DeclMember::class)), emptyList()),
            Triple("owner and version", listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name")).named("rag").withVersion("v2")), emptyList()),
            Triple(
                "versions combined",
                listOf(
                    SchemaCatalog.of(RangeIndexSpec("Person", "name")).named("rag").withVersion("v2"),
                    SchemaCatalog.of(UniquenessConstraintSpec("Person", "email")).named("rag").withVersion("a1"),
                ),
                emptyList(),
            ),
            Triple(
                "co-owned",
                listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name")).named("a"), SchemaCatalog.of(RangeIndexSpec("Person", "name")).named("b")),
                emptyList(),
            ),
            Triple("ensured at runtime", emptyList(), listOf(VectorIndexSpec("Chunk", "embedding", 768), RangeIndexSpec("Tenant", "code"))),
        )
        val declared = cases.associateBy { it.first }
        val names = pinned["cases"].map { it["case"].textValue() }
        kotlin.test.assertEquals(emptyList(), names.filter { it !in declared }, "declarations.yaml cases with no declaration here")
        kotlin.test.assertEquals(emptyList(), declared.keys.filter { it !in names }, "declarations here no case of declarations.yaml pins")
        for (case in pinned["cases"]) {
            val name = case["case"].textValue()
            val (_, catalogs, ensured) = declared.getValue(name)
            val runtime = if (ensured.isEmpty()) emptyList() else SchemaPlanner.runtimeAfter(Operation.Ensure("dice", ensured), emptyList(), setOf("default"))
            val request = CypherJson.readTree(manager(catalogs).request("default", Operation.Enforce(), runtime).toJson())
            val json = com.fasterxml.jackson.databind.ObjectMapper()
            kotlin.test.assertEquals(json.writeValueAsString(case["catalogs"]), json.writeValueAsString(json.readTree(CypherJson.writeValueAsString(request["catalogs"]!!))), "$name: catalogs")
            kotlin.test.assertEquals(json.writeValueAsString(case["runtime"]), json.writeValueAsString(json.readTree(CypherJson.writeValueAsString(request["runtime"]!!))), "$name: runtime")
        }
    }
}
