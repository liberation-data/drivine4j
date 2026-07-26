package org.drivine.sample.view

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * A `@NodeFragment` carrying a `@FullTextIndex` — so the codegen emits the filtered
 * `loadMatching(query, topK, threshold, spec)` wrapper both for the fragment itself and for the
 * [SampleFullTextView] rooted on it (see `SampleArticleNodeQueryDsl` / `SampleFullTextViewQueryDsl`).
 * Contrast with the non-full-text types (e.g. `RaisedAndAssignedIssue`), which get no `loadMatching`.
 */
@NodeFragment(labels = ["SampleArticle"])
data class SampleArticleNode(
    @NodeId val id: String,
    @FullTextIndex val body: String,
)

@GraphView
data class SampleFullTextView(
    @Root val article: SampleArticleNode,
)