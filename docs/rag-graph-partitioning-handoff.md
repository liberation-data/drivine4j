# Handoff: corpus partitioning for filtered vector search (embabel-agent-rag-graph)

Status: design agreed, not started. Drivine4j side tracked separately — see "What Drivine must supply" below.

## Why this exists

A filtered ANN read (`loadNearest` + `where {}`) has two compounding failure modes, observed live on a
~9K-vector index (1536-dim, cosine) after bulk-ingesting ~2K tightly-clustered vectors:

1. **Under-recall at practical k.** A vector verified as true global rank 3 (by exhaustive
   `vector.similarity.cosine`) was not returned at any k ≤ 100. At k = 200 it came back at rank 3.
   Reproduced across two full index rebuilds, with quantization on and off, and across a database
   restart.
2. **Post-filter dilution.** Drivine emits `CALL db.index.vector.queryNodes(name, $k, $vec)` and applies
   the `where {}` predicates *after* the index yields. A corpus-scoped caller therefore receives roughly
   `k × selectivity` rows, and those survivors are the intersection of "globally nearest" with "in my
   corpus" — which is not "my corpus's nearest".

### The mechanism (established, not hypothesised)

The rank-3 result proves `k` is the **search beam width**, not merely a row count. If `k` only limited
rows, a vector at true rank 3 would appear at k = 100 — it is comfortably inside 100. The only way k =
200 finds it and k = 100 does not is if k drives the candidate queue. That is Lucene-backed HNSW:
`ef_search == k`, and Neo4j exposes no separate knob.

Two consequences:

- "Over-fetch multiplier" and "expose the effective-k knob" are the same lever. The existing workaround
  (fetch 200, trim to 40) *is* running the search at ef = 200, and is correct rather than a hack.
- Under-recall is a tunable tradeoff, not a defect. Dense clusters saturate every neighbour list: with
  `M = 16`, near-duplicate vectors consume all 16 links with intra-cluster neighbours, pruning the
  long-range links greedy descent needs to enter or leave the cluster. Low ef then cannot recover.

**Run this experiment before building anything.** As of drivine4j 0.0.78+, `M` and `ef_construction` are
pinnable:

```kotlin
VectorIndexSpec("Proposition", "embedding", 1536, hnswM = 64, hnswEfConstruction = 400)
```

Rebuild and retest recall at k = 40. If recall returns, the original symptom has an index-level fix and
the cost/benefit of everything below changes materially.

## The design

Separate **logical** from **physical** partitions.

- **Logical partition = corpus.** Domain meaning, set by the application, unbounded. Not reorganisable
  for index-efficiency reasons — merging two corpora changes what a query means.
- **Physical partition = the Neo4j label carrying the vector index.** Bounded, chosen for layout.

The mapping between them is the tunable part:

| Corpus size | Physical layout | Read strategy |
|---|---|---|
| below exact-scan threshold | shared bucket label | exhaustive `vector.similarity.cosine`, exact |
| medium | shared bucket label | ANN on bucket index, post-filter by `corpusId` (dilution bounded by bucket size) |
| large | dedicated label | ANN on the corpus's own index — pre-filtered by construction, `k` recovers its intuitive meaning |

This gives a bounded number of HNSW graphs with an unbounded number of corpora, which is what breaks
under naive one-label-per-corpus-forever.

Nodes take multiple labels, so overlapping membership is free: a proposition in two corpora carries both
labels. Stored once, indexed twice.

### Why label-per-partition and not property-per-partition

Only a **label** makes the routing decision free. `MATCH (n:Corpus_abc) RETURN count(n)` is count-store
metadata, O(1). The same check against a property — `MATCH (n:Proposition {corpusId: $c})` — is an index
scan, proportional to the partition. Labels also give per-partition vector indexes, which is what
converts post-filtering into pre-filtering. Both benefits come from the same change.

### Why ingest time is the right moment

A new physical partition means a new label and a new vector index — a schema operation, and Neo4j
rejects schema DDL inside an open data transaction (Drivine already routes schema ops through the
auto-commit path). Per-request partition creation would make that fatal. Corpus ingestion already does
chunking, embedding and bulk insert, so adding an index creation there is a rounding error and a natural
place for a schema event.

Note the symmetry: bulk-ingesting one semantically coherent corpus is exactly what *creates* the dense
cluster that breaks navigation, and is also the natural partition boundary that dissolves it.

## Work items (this repo)

1. **Corpus → physical label mapping.** Bucketing policy: which corpora share a label, which get their
   own. Persist the mapping; it must survive restarts and be authoritative for both read and write paths.
2. **Ingest-time index creation.** Call `persistenceManager.indexes.ensure(VectorIndexSpec(label, ...))`
   when a physical partition first appears. No new Drivine feature needed — the imperative API already
   accepts an arbitrary label at runtime.
3. **Read routing.** Count the partition (count-store, O(1)) → below threshold, exhaustive scan; above,
   ANN against that partition's index.
4. **Promotion.** Moving a corpus out of a shared bucket into its own label: add label, create index,
   backfill, drop from bucket. Physical migration only — query semantics must not change.
5. **Recall harness.** Given an index and a sample of query vectors: exhaustive ground truth via
   `vector.similarity.cosine`, ANN at a range of k, report recall@k. Optionally sweep `M` /
   `ef_construction` across rebuilds. This is what turns tuning into measurement, and it is what would
   have answered "is quantization the suspect?" in minutes rather than two rebuilds.

## What Drivine must supply

- **Runtime label targeting for vector reads.** Today `VectorIndexResolver.kt:63` derives the label from
  `FragmentModel.labelsFor(fragmentClass)`, and codegen emits `loadNearest(vector, topK, threshold, spec)`
  keyed on the view type — the index is a compile-time function of the fragment. Per-partition indexes
  need it to be a runtime function of the query. This cannot be worked around from here; it is below the
  API surface. Design in progress.
- **Yield observability** — requested `k`, index yield, post-filter count, so dilution stops being silent.
- **Documentation** that `k` is the beam width.
- Index creation — already available, no work.

## Deliberately not in Drivine

Every policy decision stays here: bucket sizes, the exact-scan threshold, when to promote a corpus.
Drivine emits faithfully what it is told and does not invent strategies from selectivity estimates it
does not have — the same rule that stopped it substituting its own HNSW defaults.

## Open questions

- **Corpus cardinality per deployment**, and whether it is bounded. Dozens to low hundreds makes
  dedicated indexes attractive; thousands and growing makes bucketing mandatory.
- **The exact-scan crossover**, which must be measured rather than assumed. Expect it lower than a
  raw-FLOPS estimate suggests: `vector.similarity.cosine` reads a 1536-float property off each node, so
  the scan pays property-store access per vector rather than walking a packed array.
- **Cross-partition queries.** Searching several corpora at once needs one ANN call per index, merged and
  re-ranked. Affects the Drivine API signature — see the design note.
