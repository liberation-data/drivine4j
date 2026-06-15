package org.drivine.sample.view

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * A `@GraphView` whose root fragment carries a `@VectorIndex` — so the codegen emits the filtered
 * `loadNearest(vector, topK, threshold, spec)` wrapper for it (see `SampleVectorViewQueryDsl`).
 * Contrast with the non-vector views (e.g. `RaisedAndAssignedIssue`), which get no `loadNearest`.
 */
@NodeFragment(labels = ["SampleDoc"])
data class SampleDocNode(
    @NodeId val id: String,
    val title: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
)

@GraphView
data class SampleVectorView(
    @Root val doc: SampleDocNode,
)
