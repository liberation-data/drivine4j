# Vector search on `@GraphView` items (`loadNearest`)

Maintainer handoff for the vector-search feature added to `GraphObjectManager`.

## What it does

Brings vector (approximate nearest-neighbour) search to `GraphObjectManager`, returning the same
typed objects `loadAll` does — paired with a normalized similarity score. Like the rest of the
manager, it works on both a `@GraphView` (searches the root fragment's embedding, returns the
projected view) and a plain `@NodeFragment` (searches and returns the bare nodes).

```kotlin
// infers the @VectorIndex on the view's root fragment — no magic string
val hits = graphObjectManager.loadNearest(PropositionView::class.java, queryEmbedding, topK = 20)
hits.forEach { println("${it.score} → ${it.value.proposition.text} (${it.value.mentions.size} mentions)") }

// disambiguate when a node has several embeddings; floor by similarity
graphObjectManager.loadNearest(PropositionView::class.java, "titleEmbedding", vec, topK = 20, threshold = 0.8)
```

Returns `List<Scored<T>>`, most similar first.

## Design decisions (and why)

- **`topK` is the ANN index's `k`, not a guaranteed result count.** It's a real, knowable number
  (the value passed to the index), matching every vector API people know. A "give me exactly K
  survivors" knob can't be honoured under selective filters anyway, so we don't pretend to.
- **Post-filter, documented.** The view's required-relationship `EXISTS` checks (and the optional
  `threshold`) prune the K candidates *after* the search — so **the result may contain fewer than
  `topK` rows**. Callers who need a fuller set raise `topK`. This keeps the vector path's `WHERE`
  byte-identical to the normal load path.
- **Score normalized to similarity, higher = better, on every engine** — so ordering and `threshold`
  mean the same thing regardless of backend. The normalization lives in each grammar.
- **The property is inferred from `@VectorIndex`.** The embedding property is already annotated for
  the schema feature, so the common single-embedding case needs no property argument; a name is only
  passed to disambiguate multiple embeddings on one node. No `@Embedding` / auto-embed — Drivine
  stays out of embedding generation; the query vector is computed caller-side.

## Backend support

All three native-vector engines are verified end-to-end against testcontainers (ranking, scoring,
post-filter, threshold) — see `VectorSearchNeo4jTest` and `VectorSearchCrossEngineTest`.

| Engine   | Vector head | Status |
|----------|-------------|--------|
| Neo4j 5  | `CALL db.index.vector.queryNodes(name, k, vec) YIELD node, score` — score is already normalized similarity | ✅ verified |
| FalkorDB | `CALL db.idx.vector.queryNodes(label, prop, k, vecf32(vec)) YIELD node, score` — distance → similarity (cosine `1-d`, euclidean `1/(1+d)`) | ✅ verified |
| Memgraph | `CALL vector_search.search(name, k, vec) YIELD node, similarity` — native similarity | ✅ verified |
| Neptune  | **throws** `UnsupportedOperationException` — no native vector index (inherits the grammar default) | ✅ throws |

### FalkorDB quirk: required relationships are filtered *after* projection

FalkorDB cannot evaluate an inline relationship pattern predicate in a `WHERE` over a node sourced
from a vector-index `CALL` — `WHERE (doc)-[:WRITTEN_BY]->(:Author)` fails with `Type mismatch:
expected Null but was Pointer`, because the node still carries its `vecf32` property and the
pattern-predicate evaluator trips on the Pointer. (The relationship *projection* comprehension
`[(doc)-[:WRITTEN_BY]->(a)|…]` is fine; only the bare predicate fails.)

So the vector path does **not** reuse the load path's pre-projection `EXISTS` checks. Instead it
projects every relationship and then filters the *projected* value of each required (non-null,
non-collection) relationship for `IS NOT NULL` **after** the projection `WITH` — equivalent for
required single relationships, and portable across all engines. See
`GraphViewProjectionAssembler.requiredRelationshipAliases` and `GraphViewVectorSearchBuilder`.

## Code map

- `manager/Scored.kt` — `Scored<T>(value, score)`; score lives here, not on the domain object.
- `query/grammar/VectorQuerySpec.kt` — resolved index + bound param names handed to a grammar.
- `query/grammar/CypherGrammar.kt` — `vectorSearchHead(...)` + `supportsVectorSearch` (default
  throws / false); implemented in `Neo4j5Grammar` and `FalkorDbCypherGrammar`.
- `query/grammar/MemgraphGrammar.kt` — Memgraph head.
- `query/VectorIndexResolver.kt` — resolves label/property/indexName/similarity from `@VectorIndex`
  on the root fragment (infer / disambiguate by name / error). Mirrors `VectorIndexSpec` naming.
- `query/GraphViewProjectionAssembler.kt` — shared projection core (root + relationships +
  aggregates + required-rel `EXISTS` + `WHERE` + `BuildContext` prolog plumbing).
- `query/GraphViewLoadBuilder.kt` — normal load (`MATCH` head) over the assembler.
- `query/GraphViewVectorSearchBuilder.kt` — view vector search (`CALL` head + scored RETURN +
  `ORDER BY score DESC`) over the assembler.
- `query/FragmentVectorSearchBuilder.kt` — fragment vector search (`CALL` head + fragment field
  projection + scored RETURN); no relationships, so threshold is the only filter.
- `query/GraphViewQueryBuilder.kt` — facade; `buildVectorQuery(...)` delegates to the vector builder.
- `manager/GraphObjectManager.kt` — `loadNearest(...)` overloads; branches view vs. fragment, reuses
  `TransformPostProcessor` on the inner `value` map, and snapshots for dirty tracking.

## Generated Cypher (Neo4j, the `DocView` test fixture)

```cypher
CALL db.index.vector.queryNodes('Doc_embedding_vector', $_vectorTopK, $_vectorQuery)
YIELD node, score
WITH node AS doc, score AS _score
WITH
    doc { id: doc.id, title: doc.title, embedding: doc.embedding, labels: labels(doc) } AS doc,
    [(doc)-[:WRITTEN_BY]->(author:Author) | author { ... }][0] AS author,
    _score
WHERE author IS NOT NULL          -- required relationship, filtered on the projected value
RETURN { value: { doc: doc, author: author }, score: _score } AS row
ORDER BY _score DESC
```

The required-relationship filter is a post-projection `IS NOT NULL` on the projected value (not a
pre-projection `EXISTS`) — see the FalkorDB quirk above. A `threshold`, when given, is `AND`-ed into
the same `WHERE` as `_score >= $_vectorThreshold`.

The row is wrapped as a single map column so the result mapper collapses it to one value; the
manager unpacks `value` + `score` into `Scored<T>`.

## Tests

- `query/grammar/VectorSearchGrammarTest` — each backend's head; Neptune + base openCypher throw.
- `query/VectorSearchBuilderTest` — index inference, disambiguation, error paths, Cypher shape.
- `manager/VectorSearchNeo4jTest` (testcontainer) — ranking, hydrated `Scored` views, the `<topK`
  post-filter proof (the nearest Doc, lacking an author, is correctly absent), and threshold.
- `manager/VectorSearchCrossEngineTest` (testcontainers) — the same ranking/prune/threshold
  assertions run against **FalkorDB and Memgraph**, verifying each engine's procedure, `vecf32`
  wrapping, and distance→similarity normalization actually execute and rank correctly.

## Recall tuning (0.0.79)

**`searchK`** — what the *index* is asked for, i.e. the HNSW beam width. `topK` becomes a post-filter
`LIMIT`, applied after the `WHERE` so over-fetching recovers rows the filter would otherwise thin away.

`k` is not just a row count: on a 9K-vector index a vector at true global rank 3 was missed at every
`k ≤ 100` and returned at rank 3 at `k = 200`. In Lucene-backed HNSW the result queue is the candidate
queue, so `ef_search == k`. Omitting `searchK` leaves the emitted query byte-identical.

Full rationale: [0.0.79-vector-search-k.md](0.0.79-vector-search-k.md).

**`partitionLabel`** — targets a per-partition index at runtime, so the search runs *inside* the
partition rather than being post-filtered. The label is bound, not interpolated (one query plan for all
partitions); the index name re-derives as `${label}_${property}_vector`, matching the create side.
Targeting is not identity — the projection is unchanged.
Full rationale: [0.0.79-vector-partitioning.md](0.0.79-vector-partitioning.md).

## Open follow-ups

1. **Yield observability.** A filtered vector read that returns fewer rows than requested is silent
   about it. Reporting requested `k`, index yield, and post-filter count would make dilution visible
   rather than something to infer.
2. **Cross-partition search.** `partitionLabel` targets one partition; searching several needs one call
   per index, merged and re-ranked, which changes what `k` means. Deliberately deferred.
3. **Phase 2 — `nearest{}` DSL sugar.** `loadNearest` is the primitive; a `nearest{}` block
   composable with `where{}` (returning plain `List<T>`, dropping the score by design) is deferred.
4. **Search anchored on a non-root node.** A view always searches its *root* fragment's embedding; a
   `@VectorIndex` on a relationship target is not reachable (you'd search the target and traverse
   back). Not currently supported.