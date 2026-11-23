package org.drivine.schema

import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex

/** The fragment [DeclarationCases] declares for the case "fragment" of `declarations.yaml`. */
@NodeFragment(labels = ["Article"])
data class DeclArticle(
    @NodeId val id: String,
    @Unique val slug: String,
    @RangeIndex val title: String,
    @VectorIndex val embedding: List<Float>?,
    @FullTextIndex val body: String,
)

/** The fragment [DeclarationCases] declares for the case "fragment id served by a constraint" of `declarations.yaml`. */
@NodeFragment(labels = ["Account"])
data class DeclAccount(
    @NodeId @Unique val id: String,
    val name: String,
)

/** The fragment [DeclarationCases] declares for the case "fragment without its id's index" of `declarations.yaml`. */
@NodeFragment(labels = ["Ticket"], nodeIdIndex = false)
data class DeclTicket(
    @NodeId val id: String,
)

/** The fragment [DeclarationCases] declares for the case "fragment class items" of `declarations.yaml`. */
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

/** The fragment [DeclarationCases] declares for the case "fragments merged" of `declarations.yaml`. */
@NodeFragment(labels = ["Customer"])
data class DeclCustomer(
    @NodeId val id: String,
    @RangeIndex val email: String,
)

/** The fragment [DeclarationCases] declares for the case "fragments merged" of `declarations.yaml`. */
@NodeFragment(labels = ["Customer"])
data class DeclCustomerView(
    @NodeId val id: String,
    @RangeIndex val email: String,
)

/** The fragment [DeclarationCases] declares for the case "fragment ignoring case" of `declarations.yaml`. */
@NodeFragment(labels = ["Member"])
data class DeclMember(
    @NodeId val id: String,
    @CaseInsensitive @Unique(ignoreCase = true) val email: String,
)

/**
 * Each case of the spec's `declarations.yaml` (liberation-data/drivine-cypher-spec#135) declared in drivine4j's own API:
 * its catalogs, and the items ensured at runtime (by owner [RUNTIME_OWNER]). [DeclarationsGoldenTest] checks the request
 * they give the planner; [DeclarationsInteropTest] applies them to a real store, for the run that checks a store one
 * library indexed is up to date for the others.
 */
internal object DeclarationCases {

    /** The owner the runtime items of a case are ensured under. */
    const val RUNTIME_OWNER = "dice"

    /** A fragment's vector index's dimensions, which a catalog read from fragments needs. */
    val dims = VectorDimensionProvider { _, _ -> 384 }

    private val long = "AVeryLongLabelNameThatGoesOnAndOnForTheSakeOfAnIdentifierLimit"

    /**
     * Each case, by the name `declarations.yaml` gives it: its startup catalogs, in the order given, and the items its
     * owner [RUNTIME_OWNER] ensures at runtime, in that order (none for a case that ensures nothing).
     */
    val all: List<Triple<String, List<SchemaCatalog>, List<SchemaItemSpec>>> = listOf(
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
}
