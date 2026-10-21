# Plan: the object manager on plain Postgres

Status: **in progress** · Replaces the July proposal (a table per label, join tables, one label per
node), which was written before the Oracle backend and its layout existed.

## Goal

`GraphObjectManager` and the stateless manager work on plain Postgres, with pgvector for vector
search, as they work on Oracle: the same annotations, the same query DSL, the same results. No
Apache AGE, no extension beyond pgvector, and nothing new for a caller to declare.

## The layout: Oracle's, so a store is one store

A graph is kept as Oracle keeps it (`OracleGraph`):

| Table | Columns |
|---|---|
| `<graph>_node` | `nid` identity key, `labels` (array), `props` (JSON object keyed by stored name), `stamp` |
| `<graph>_edge` | `eid` identity key, `src` and `dst` (cascading to the nodes), `type`, `props` |
| `<graph>_schema` | what Drivine made of indexes and constraints |

On Postgres, `labels` and `props` are `jsonb`, the labels a JSON array under a GIN index, as they are
JSON on Oracle: a list is bound as JSON, and a node's map is made of the two with no conversion. So:

- **A node has as many labels as it likes**, and a label is found through the index
  (`labels @> '["Person"]'`). This is the mistake of AGE, a label being a table, not made again.
- **Nothing new to declare.** No table, column or join annotations: a fragment is a set of labels,
  as everywhere else.
- **One store format across Oracle and Postgres, and across drivine4j, Drivine and drivine-python**,
  as Oracle already is. A store written from the JVM reads the same from Node or Python.
- **Indexes are partial**, which suits the layout better than Oracle's `CASE` expressions: a range
  index on `Person.email` is `CREATE INDEX … ((props->>'email')) WHERE labels @> '["Person"]'`, a
  uniqueness constraint the same index made `UNIQUE`.

Mapping onto hand-made relational tables is a different feature, a mode of its own if it is ever
wanted, and out of scope here.

## Shape of the work: two SQL backends, one family

Oracle's backend is about 4,000 lines in `backend/oracle`. Much of it is not about Oracle: the
model walk that turns a view into nested JSON, the predicates of the query DSL, the plan a save is
written as, the scope of a statement's names and parameters, the catalog of what was made. What is
Oracle's own is the dialect: `GRAPH_TABLE`, `JSON_VALUE`, `JSON_OBJECT`, PL/SQL.

The work pulls the shared part into `backend/sql`, behind seams, with Oracle and Postgres each
implementing them:

| Seam | Shared (in `backend/sql`) | Oracle | Postgres |
|---|---|---|---|
| `SqlDialect` (from `OracleSql`) | the vocabulary: property, label test, JSON object/array, literal, page | `JSON_VALUE`, `JSON_EXISTS`, `JSON_OBJECT`, `JSON_ARRAYAGG` | `->>`, `?`, `@>`, `jsonb_build_object`, `jsonb_agg` |
| `SqlGraph` (from `OracleGraph`) | table names, `objectName`, the catalog, `ensure` | 128-character names, PL/SQL ensuring block, property graph | 63-character names, `CREATE … IF NOT EXISTS` |
| `RowSources` (from `OracleRowSources`) | `Step`, `Query`, `RowSource`, `StatementScope` | `GRAPH_TABLE` patterns | joins over the edge table; recursive CTEs for depth |
| `SqlSelect`, `SqlPredicates` (from `OracleSelect`, `OraclePredicates`) | the whole model walk, written against the dialect and row sources | | |
| `WriteBlock` (from `OracleWriteBlock`) | `WritePlan`, `NodeShape`, `SqlSavePlan`: a save as a JSON plan of steps | the PL/SQL block | a PL/pgSQL function the store is given (see below) |
| `SqlSchemaGrammar` (from `OracleSchemaGrammar`) | the catalog, `recorded`/`forgotten`, listing and parsing | JSON expression indexes, Oracle Text, vector side tables | partial btree indexes, `tsvector` GIN indexes, pgvector HNSW |
| `SqlBackend` (from `OracleBackend`) | almost all of `GraphBackend` | contention codes, cascading delete in PL/SQL | contention codes (`40001`, `40P01`), cascading delete in a CTE |

**Each seam is pulled out when Postgres first needs it, not ahead of it.** A step is a refactor
commit, which leaves Oracle's behaviour and statements as they were (its tests are the proof), then
a commit that adds the Postgres side. A seam drawn with both sides in view is a better seam than
one guessed at with only Oracle's.

### Writes on Postgres

A save on Oracle is a plan, a JSON array of steps (`node`, `ref`, `link`, `unlink`, `cut`, `patch`),
carried out by one PL/SQL block whose text depends only on the node shapes it names. The plan is not
Oracle's and is kept (`WritePlan`, `SqlSavePlan`, with their tests). On Postgres the interpreter
is a PL/pgSQL function, `<graph>_write(plan jsonb, mark text, patched_if text)` returning the rows a
block returns, installed by `ensure` beside the tables. A `DO` block cannot return rows, so a function
it is. The `patch` step's predicate, which is SQL and cannot be data, is passed as text the function
runs with `EXECUTE … USING`, with its values in the plan. Drivine writes that text itself, from the
query DSL, so no caller's text reaches it.

### Kept in view: an openCypher layer

Embabel's virtual Cypher will want Cypher read on the SQL stores. Nothing here builds it, but:

- **`Step` and `RowSources` are the lowering target.** A Cypher pattern is a list of steps, and a
  translator that emits steps gets both stores for free.
- **`MiniCypher`** (the parser and evaluator of the test kit, which already writes Cypher `CREATE`
  fixtures to Oracle) is where a parser starts. Its fixtures are generalised to Postgres in step 2.
- **`CypherShorthand`**, which reads `n.prop` in a raw `where`, moves to `backend/sql` unchanged.

## Steps

Each step is a commit or two on `postgres-backend`, with the gate green:
`./gradlew :test` and `ORACLE_TESTS=true ./gradlew :test --tests '*Oracle*'`, plus
`POSTGRES_TESTS=true ./gradlew :test --tests '*Postgres*'` once there are Postgres tests.

1. **Substrate.** `DatabaseType.POSTGRES` through `JdbcConnectionProvider`: URL, pool, the driver
   as an optional dependency (as `ojdbc11` is), `$name` parameters bound positionally, `jsonb` and
   `text[]` values bound and read. `PostgresTestContainer` (`pgvector/pgvector`), `PostgresTestDatabase`,
   `@PostgresTests`. A connection test: queries, parameters, transactions commit and roll back.
2. **The graph.** Pull out `SqlDialect` and `SqlGraph`, move the dialect-free pieces (`Query`,
   `RowSource`, `StatementScope`, `Step`, `CypherShorthand`, `TextQuery`) to `backend/sql`.
   `PostgresGraph` and `PostgresDialect`; `ensure` makes the tables. `OracleCypherFixtures` becomes
   `SqlCypherFixtures`, so the cross-engine contracts seed Postgres as they seed Oracle.
3. **Reads.** Pull out `RowSources`, then `SqlSelect` and `SqlPredicates`. `PostgresRowSources`.
   `PostgresBackend`, reading only: `load`, `loadAll` (raw `where` and the DSL, orders, pages, keyset
   seeks), `count`, `related`, views with nested views, paths, depths and recursion, collection sorts.
   The read contracts gain a Postgres subclass each.
4. **Writes.** Pull out `WriteBlock`, `SqlSavePlan`. The PL/pgSQL write function. `save`,
   `saveTogether`, `patch`, `patchAll`, `relate`, `unrelate`, `unrelateAll`, `foundStamp`, stamps and
   stale checks, `contended`. The save, patch, null-policy, property-bag and edge contracts.
5. **Deletes.** `delete`, `deleteAll`, cascades (`DELETE_ALL`, `DELETE_ORPHAN`) and the depth checks,
   as one statement of CTEs. Pull out `SqlBackend` here, when both backends are whole.
6. **Groups.** `groups`, against the groups contract.
7. **Schema.** `SqlSchemaGrammar`; `PostgresSchemaGrammar` for range indexes and uniqueness
   constraints (partial indexes), the labelled-index catalog that filters are read through, the
   version marker and store stamp. The schema-management contract.
8. **Search.** Vector search through pgvector (an HNSW index over a partial expression, or a side
   table as on Oracle, whichever the planner serves; `searchK` as `hnsw.ef_search`), full-text search
   through `tsvector` and `ts_rank_cd`, read with `TextQuery`. The search contracts.
9. **Finish.** README (a Postgres section beside Oracle's, the engine table), `TEST_CONTAINERS.md`,
   Spring Boot starter properties, the benchmark run against Postgres.

Then the same for Drivine (TypeScript) and drivine-python, guided by statements that match.

## Open questions

- **Postgres tests by default, or behind `POSTGRES_TESTS=true`?** The image is small and starts in
  seconds, unlike Oracle's; by default seems right once the suite is stable. Gated until then.
- **Lowest Postgres version.** 14 has everything the layout needs (`jsonb`, GIN, partial indexes,
  identity columns, `CREATE … IF NOT EXISTS`); 15 if `MERGE` turns out to be wanted. Tests run on 17.
- **Comparisons across types.** Oracle compares the string `'7'` with the number `7` as equal through
  `JSON_VALUE`; the graph engines do not. Postgres with `jsonb` can match the graph engines. Which
  is right is a question for both SQL backends, to settle in step 3.

## Progress

- [x] 1. Substrate: `DatabaseType.POSTGRES` connects through `JdbcConnectionProvider` (and from
  connection properties, and the starter's test configuration); `jsonb`, `text[]`, `timestamptz`,
  pgvector and integer columns read as the graph drivers give them; dollar-quoted bodies pass
  through `$name` parsing; `PostgresTestContainer`, `PostgresConnectionIntegrationTest`.
- [x] 2. The graph: `SqlGraph` (tables, names, catalog) under `OracleGraph` and `PostgresGraph`;
  Postgres makes its tables in one locked, transactional block marked with its version.
  `SqlCypherFixtures` (was `OracleCypherFixtures`, kept as a deprecated alias) seeds either store, and
  `TestCleanup` cleans a Postgres graph. `SqlDialect` and the moves of `Query`, `RowSource` and the
  statement scope wait for step 3, where the reads first need them.
- [x] 3. Reads: the dialect-free pieces moved to `backend/sql` (`Query`, `RowSource`, `Step`,
  `SqlKind`, `StatementScope`, `CypherShorthand`) with Oracle's statements unchanged. `PostgresBackend`
  loads, counts, pages, seeks and loads related nodes: views with nested views, relationships with
  properties, paths (each node once, ordered by a hop), depths and recursion, aggregates and
  collection sorts. A path is joins over the edge table, and a step to a depth a recursive query
  anchored at the node it starts from. A number is read only where one is stored, so `'7'` is not
  `7`, as on the graph engines. The fragment and view read contracts that seed with fixtures pass
  (null placement, dynamic property filters, flat variable length, limit and skip, polymorphic
  recursive views), and `PostgresReadSmokeTest` covers the rest. Contracts that also save, delete or
  make indexes join their steps. The shared select (`SqlSelect`) is not pulled out yet: Oracle's is
  shaped by `GRAPH_TABLE` and its workarounds, and the two are compared again once writes exist.
- [x] 4. Writes: `WritePlan`, its shapes and `SqlSavePlan` (was `OracleSavePlan`) moved to
  `backend/sql` behind `WriteBlocks`, Oracle's block unchanged. On Postgres the plan is carried out by
  `<graph>_write`, a PL/pgSQL function the store keeps, with `<graph>_find` (found, then locked by
  key and looked at again) and `<graph>_owns`; one call is one statement, rolled back whole when its
  root is stale. A node insert that a uniqueness constraint refuses is looked for again, as on
  Oracle, but with `ON CONFLICT DO NOTHING` rather than a caught error, which would cost a
  subtransaction per node. The query a conditional `patch` asks is passed to the function with its
  values written in as literals. Save, batch save, patch, patch-all, relate, unrelate and
  `contended` (`40001`, `40P01`). Every contract that saves now has a Postgres subclass; a test that
  reaches a later step is skipped by `PostgresNotYet` (removed in step 9): 283 pass, 25 skip.
- [x] 5. Deletes: `delete`, `deleteAll` and cascades, each one statement of CTEs ending in a
  `DELETE … RETURNING` that is counted. `DELETE_ORPHAN` keeps what a node outside the cascade holds,
  walked by a recursive CTE, as Oracle's PL/SQL does. A path is anchored at a set of nodes as well as
  at a row, and a cascade below a field that holds views of its own class chains a walk after hops.
  The recursive cascade contract runs on Postgres. `SqlBackend` is not pulled out yet: Oracle's
  deletes are PL/SQL and Postgres's CTEs, so what the two share is the operations' shape, not their
  code; it is weighed again at the end.
- [x] 6. Groups: a grouped read is `GROUP BY` over the roots, and over the distinct pairs of a root
  and its key nodes where a key is a path, each path walked from the root's row by a lateral join.
  A text key no field declares carries its number beside it, as on Oracle; Postgres keeps `""` apart
  from null, so it needs no column for that. The 43 tests of the groups contract pass.
- [ ] 7. Schema
- [ ] 8. Search
- [ ] 9. Finish
