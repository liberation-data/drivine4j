package sample.nullpolicy

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * A **bagless** scalar + `@VectorIndex` fragment — the surface for exercising null-write policy: a null
 * embedding must never clear the stored vector (#1), and non-vector nulls follow
 * [org.drivine.manager.NullPolicy] (#2). Bagless on purpose so `saveAll` takes the UNWIND fast path on
 * Neo4j / Memgraph (and the per-item fallback on FalkorDB, which wraps vectors) — both null-vector
 * paths get covered. Bag null-policy is covered separately via the bagged `RecordNode` fixture.
 */
@NodeFragment(labels = ["VecSave"])
data class VecSaveNode(
    @NodeId val id: String,
    val title: String? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
)
