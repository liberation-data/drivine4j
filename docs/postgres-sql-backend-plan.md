# Plan: `GraphObjectManager` on plain Postgres (SQL, no AGE)

Status: **proposal** · Target API: `GraphObjectManager` (`@GraphView` / `@NodeFragment`) over a
plain relational Postgres schema + pgvector. **No Apache AGE, no AgensGraph, no Cypher engine** —
graph composition is reproduced in pure SQL via `LATERAL` + `json_agg`.

## Goal

Make `@GraphView`-based reads, vector search, and writes work against ordinary Postgres tables, so
`embabel-chat-store` / `dice` can run on Postgres + pgvector with real `vector` columns and HNSW
indexes. The type-safe DSL (`where {}`, `loadNearest`) should work unchanged from the caller's side.

## Why this is feasible (the load-bearing facts)

Established earlier in design discussion, verified against the code:

1. **`GraphObjectManager` has no execution path of its own.** It builds a `QuerySpecification` and
   runs everything through `persistenceManager.query(...)` / `.execute(...)` / `.executeBatch(...)`
   (`manager/GraphObjectManager.kt:69,108,832,876`). So it is pure *generation + hydration* on top
   of the same substrate `PersistenceManager` uses. Give that substrate a SQL backend and the
   high-level API rides on it.
2. **A GraphView already `RETURN`s a single nested map column.** The result mapper collapses a
   one-column row straight to the object. A Postgres `SELECT json_build_object(...) AS row` returns
   the same single-`jsonb` shape → the hydration / `TransformPostProcessor` layer is reused
   essentially unchanged. (This is the biggest risk, and it's retired in Phase 0 with a hand-written
   query before any generator code is written.)
3. **`json_agg` is the relational equivalent of a Cypher pattern comprehension.**
   `[(p)-[:R]->(c) | c {.*}]` ⇄ `LEFT JOIN LATERAL (SELECT jsonb_agg(to_jsonb(c)) ...) ON true`.
4. **The `where {}` DSL has a backend-neutral AST.** `sealed class WhereCondition`
   (`PropertyCondition`, `RelationshipCondition`, `LabelCondition`, `OrCondition`,
   `ListMembershipCondition`) is walked by `CypherGenerator` to emit Cypher. A `SqlGenerator` can
   walk the *same* AST. Only `ComparisonOperator.cypherOperator` and the final emitter are dialect-
   specific.
5. **The connection seam is driver-neutral.** `Connection { query(spec): List<T>; tx control }` has
   no Bolt/Cypher types in its signature. A `PostgresConnection` over JDBC is structurally allowed.
6. **pgvector fits the existing vector contract.** `vectorSearchHead` already promises "normalized
   similarity, higher = better, normalized per-backend." pgvector cosine is `1 - (embedding <=> $q)`,
   `ORDER BY embedding <=> $q LIMIT k` — an exact match.
7. **Precedent.** The TypeScript Drivine shipped plain-Postgres support: `POSTGRES` and
   `AGENS_GRAPH` shared one `pg`-driver provider; the only difference was the default language
   (`SQL` vs `CYPHER`). Plain Postgres worked at the PersistenceManager level — hand-written SQL,
   mapped rows. We are re-expressing that as JDBC and then *generating* the SQL.

## Where the work is NOT

The `CypherGrammar` interface is **not** the extension point. Every method emits Cypher/openCypher
text; FalkorDB/Memgraph/Neptune were cheap *because they all speak Cypher*. Postgres means a
**sibling generation pipeline**, not a `CypherGrammar` implementation. The reused assets are the
*model* (`GraphViewModel`, fragment/relationship metadata), the `WhereCondition` AST, the connection
seam, and the hydration layer.

---

## Strategy: substrate first, then generation

Two layers, built and validated in order:

- **Phase 0 — Execution substrate (JDBC).** What "PersistenceManager on Postgres" *is*. Lets us run
  *hand-written* `json_agg` SQL through `persistenceManager.query(View::class)` and prove hydration
  reuse. This is the foundation everything else sits on AND the test harness that de-risks it.
- **Phase 1+ — SQL generation.** Teach a SQL builder family to emit the `json_agg` SQL from
  `@GraphView` annotations: reads, then vector search, then writes + DDL.

---

## Phase 0 — Execution substrate (the prerequisite)

Deliver raw-SQL execution through the existing `Connection` / `PersistenceManager` seam. No grammar,
no builders involved — `PersistenceManager` runs statement text.

### Tasks (file-by-file, mirroring existing structure)

- `connection/PostgresConnectionProvider.kt` — JDBC pool (HikariCP), `type = POSTGRES`, hands out
  `PostgresConnection`. Default language `SQL`.
- `connection/PostgresConnection.kt` — `implements Connection`: compile spec → SQL + ordered params,
  `PreparedStatement`, execute, map rows; `startTransaction`/`commit`/`rollback` via JDBC
  `setAutoCommit(false)` + `commit`/`rollback`.
- `query/` SQL compile path — render `$name` params to positional `?`/`$n` (exactly what the TS
  `AgensGraphSpecCompiler` did); `OFFSET`/`LIMIT` (the `SpecCompiler.skipClause()` SQL branch already
  exists). Decide: extend `SpecCompiler` vs a `PostgresSpecCompiler`.
- `mapper/PostgresResultMapper.kt` — `ResultSet` → read the single `json`/`jsonb` column → Jackson →
  object; reuse existing `ResultPostProcessor`s (incl. `TransformPostProcessor`). Multi-column rows →
  tuple, mirroring `GraphResultMapper`.
- **Decouple param binding** — `QuerySpecification.bind()` hard-codes
  `Neo4jObjectMapper.convertValueForNeo4j` (`query/QuerySpecification.kt:54,84`). Introduce a value-
  converter seam (default = current Neo4j behavior; Postgres variant for JDBC types). Must not
  regress the Neo4j path.
- `connection/ConnectionProviderBuilder` + `DatabaseType.POSTGRES` wiring + `CypherDialect`/registry
  (note: raw SQL needs no grammar; the dialect entry is for Phase 1).
- `build.gradle` — `org.postgresql:postgresql`, HikariCP, and a pgvector type strategy
  (`com.pgvector:pgvector` or `::vector` cast on string params).

### Acceptance criteria

Against a `pgvector/pgvector` testcontainer:
1. `PersistenceManager` executes a hand-written `SELECT`, params bound, rows mapped. Transactions
   commit/rollback.
2. **The hydration proof:** hand-write a `json_agg` query for a real `@GraphView` (one to-one + one
   to-many), run `persistenceManager.query(View::class)`, assert the typed object (incl. the nested
   collection) hydrates correctly. *This validates the central reuse claim before any generator is
   written.*

---

## Phase 1 — SQL generation for reads (`loadAll` + `where {}`)

The core build: `GraphViewModel` → `LATERAL`/`json_agg` SQL.

### Mapping rules (annotation → relational)

| Annotation | Relational mapping |
|---|---|
| `@NodeFragment(labels=["Person"])` | table (label → table name) |
| property | column (name strategy: explicit or camel→snake) |
| `@NodeId` | primary key |
| `@Root` | `FROM <root table>` |
| `@GraphRelationship` to-one | `LEFT JOIN LATERAL (… LIMIT 1)` → nested object (or scalar `to_jsonb` subquery) |
| `@GraphRelationship` to-many | `LEFT JOIN LATERAL (SELECT jsonb_agg(to_jsonb(c)) …) ON true`, `COALESCE(…, '[]')` |
| `@GraphRelationship(direction=…)` | chooses FK side / join direction |
| nested `@GraphView` target | nested `jsonb_build_object` inside the agg |
| `@Aggregate` / `@Count` | aggregate subquery (`count`, `sum`, …) |
| `@PropertyBag` | `jsonb` column |
| required (non-null) relationship | `INNER JOIN` or post-projection `IS NOT NULL` (mirrors the existing required-rel filter) |

### Tasks

- `query/sql/SqlGraphViewBuilder.kt` — sibling to `GraphViewProjectionAssembler`; emits the
  `json_build_object` + `LATERAL`/`json_agg` projection from the resolved view model.
- `query/dsl/SqlGenerator.kt` — walks the `WhereCondition` AST → SQL `WHERE`. Add
  `ComparisonOperator.sqlOperator`. Relationship-existence conditions → `EXISTS (SELECT 1 …)` (the
  SQL analog of `filteredExistenceCheck`).
- **Relationship-mapping annotations** — graph edges are schemaless; SQL needs to know *how* a
  relationship is stored. Add params (or a companion annotation), e.g.
  `@GraphRelationship(joinTable=…, sourceColumn=…, targetColumn=…)` for many-to-many, FK column for
  to-one — or infer from FK metadata. **This is the main new public surface.** (See Open Decisions.)
- **Generalize builder selection** — today `PersistenceManagerFactory` grabs
  `connectionProvider.grammar` (a `CypherGrammar`) and threads it into `GraphObjectManager`. Widen
  this into a query-backend abstraction so a `POSTGRES` dialect dispatches to the SQL builder family.

### Acceptance criteria

A real chat-store `@GraphView` loads via `graphObjectManager.query(View::class).loadAll()` and via
`.filterWith(...).where { … }.loadAll()`, against table DDL, producing identical typed objects to the
Neo4j path. Cross-checked by a testcontainer test.

---

## Phase 2 — Vector search (`loadNearest`) on pgvector

- Embedding property (`@VectorIndex`) → real `vector(N)` column + HNSW index.
- SQL vector head: `ORDER BY embedding <=> $q LIMIT $k`, score `1 - (embedding <=> $q)` (cosine) /
  `1 / (1 + (embedding <=> $q))` (euclidean) — matches the normalized-similarity contract.
- Reproduce the **post-filter-after-ANN** pattern (documented for FalkorDB): wrap the ANN in a CTE,
  then apply the view's required-relationship `IS NOT NULL` and `threshold` over the projected value.
- `VectorIndexResolver` already resolves index/property/similarity from `@VectorIndex` — reuse.

### Acceptance

`loadNearest(View::class, queryVec, topK, threshold)` returns ranked `Scored<View>` from pgvector,
with the same ranking/prune/threshold assertions the cross-engine vector tests already make.

---

## Phase 3 — Writes + schema DDL

- **Save/merge** — `GraphViewMergeBuilder` analog: `INSERT … ON CONFLICT (id) DO UPDATE` per
  fragment; relationship writes = FK updates / join-table upserts. Cascade semantics mapped to
  multi-statement transactions.
- **Delete** — `GraphViewDeleteBuilder` analog (FK `ON DELETE` or explicit deletes; orphan handling).
- **`PostgresSchemaGrammar`** — the DDL/introspection family (sibling to `Neo4jSchemaGrammar` etc.):
  `CREATE TABLE`, FK constraints, `@RangeIndex` → btree, `@Unique` → unique constraint,
  `@VectorIndex` → `CREATE INDEX … USING hnsw (embedding vector_cosine_ops)`. Feeds the existing
  schema-management feature.

---

## Open decisions (resolve before Phase 1)

1. **Relationship storage convention + annotation surface.** Always join-table? FK for to-one +
   join-table for to-many? Inferred from DB FK metadata, or declared on `@GraphRelationship`?
   *Recommendation:* declared (explicit `joinTable`/columns), inference as a later convenience.
2. **Who owns the schema?** Drivine generates DDL from annotations (consistent with the existing
   schema feature) vs. maps onto a pre-existing hand-authored schema. *Recommendation:* generate, but
   allow explicit `@Table`/`@Column` name overrides so it can also bind to existing tables.
3. **Table/column naming.** Convention (camel→snake) vs. explicit annotations. *Recommendation:*
   convention + override.
4. **Identity.** Use `@NodeId` business key as PK, or a surrogate? Generated vs. caller-supplied?
5. **Relationship properties (`@RelationshipFragment`).** Join-table-with-columns. In scope for v1?

## Out of scope for v1

- Multi-label / polymorphic nodes (`instanceOf`, `@NodeFragment(labels=[a,b])`) — single-table per
  fragment first; discriminator/STI later.
- Variable-length / arbitrary-depth traversal (`maxDepth`, `@GraphPath` multi-hop) → recursive CTEs;
  defer. Single-hop relationships only in v1.
- The raw-Cypher `PersistenceManager` API on Postgres (un-portable by definition; SQL Persistence
  Manager is the analog and is delivered in Phase 0).

## Risks

- **Param-binding decoupling touches shared code** — guard the Neo4j path with existing tests.
- **Deep/wide views → large nested `jsonb` + many `LATERAL`s.** Single round-trip (no N+1), but watch
  payload size / planner behavior; measure on a realistic chat-store view.
- **Relationship-mapping ergonomics** — the new annotation surface is the part most likely to feel
  awkward; validate it against ≥2 real views early.

## Testing

- Testcontainers (`pgvector/pgvector`), following the existing `*ConnectionIntegrationTest` /
  `VectorSearchCrossEngineTest` patterns.
- Phase 0: `PostgresConnectionIntegrationTest` + the hand-written-`json_agg` hydration test.
- Phase 1+: per-view load / filter / vector / save round-trip tests, asserting parity with the Neo4j
  path where the same fixture can run on both.

## Milestones

1. **M0 — Substrate.** Phase 0 complete; hand-written `json_agg` hydrates a `@GraphView`. *Go/no-go
   on the hydration claim.*
2. **M1 — Read.** One real chat-store view loads + filters via generated SQL.
3. **M2 — Vector.** `loadNearest` on pgvector, ranked + post-filtered.
4. **M3 — Write + DDL.** Save/delete + schema generation; a full chat-store vertical on Postgres.