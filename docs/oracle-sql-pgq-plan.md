# Plan: `StatelessGraphObjectManager` on Oracle (SQL/PGQ)

Status: **phases 1 to 7 done** on `feat/oracle-sql-pgq`; 8 and part of 9 remain. Target: Oracle Database 23ai / 26ai.
What was built, and what it showed, is under [Outcome](#outcome) at the end. How to use it is in the
README, under [Oracle](../README.md#oracle).

The phases below are the plan as it was written. Where a class was built under another name, or a
step turned out otherwise, the phase says so in italics.

Target API: everything on `GraphObjectOperations`, plus `save`, `saveAll`, `update` and `edges` on
`StatelessGraphObjectManager`. The deprecated `GraphObjectManager.save` (session diffing,
`CascadeType`) stays Cypher-only.

This supersedes the generation half of [postgres-sql-backend-plan.md](postgres-sql-backend-plan.md)
for Oracle. Its Phase 0 (a JDBC substrate) is Phase 1 here and is shared: `JdbcConnection` is not
Oracle-specific.

## What was established before any of this was written

Two spikes ran against Oracle AI Database 26ai Free (23.26.3). Both are kept as tests.

- **Writes work.** `OracleWriteSpikeTest` holds a hand-written PL/SQL save to 17 scenarios from the
  stateless save contracts: checked and unchecked saves, both stamp tokens, relationship replace,
  whole-or-nothing without a transaction, read-your-writes through `GRAPH_TABLE`, eight concurrent
  writers. Removing `FOR UPDATE` fails two of them.
- **Hydration is reused.** `OracleConnectionIntegrationTest` hydrates a real `@GraphView` from one
  JSON column through `persistenceManager.getOne(spec.transform(MemoView::class.java))`.

Facts about Oracle that shape the design, each observed and not assumed:

| Fact | Consequence |
|---|---|
| `INSERT`/`UPDATE`/`DELETE` on `GRAPH_TABLE` is refused (`ORA-40968`) | Writes are DML on the tables under the graph |
| A PL/SQL block that fails is rolled back to where it began | A save can be one statement, as it is in Cypher |
| A block hands rows back with `DBMS_SQL.RETURN_RESULT` | A save returns its `stamps` row with no out-parameter plumbing |
| A nested `GRAPH_TABLE` cannot name the outer row in its `MATCH … WHERE` (`ORA-40996`) | A nested collection exposes the root's key in `COLUMNS` and is joined outside |
| A `MATCH … WHERE` holds no subquery (`ORA-40988`) | A test against a list, and a query of another pattern, are asked of the rows a pattern gives |
| A correlated `EXISTS` over a `GRAPH_TABLE` that tests, on its rows, a property with an index for equality ends the server process (`ORA-07445` in `kkosjt`, `ORA-03113` to the client; Free 23.26). `<>`, the tests of a text, a regular expression, a list, and an `OR` of an equality with a list did not | A condition on a related node is tested in the pattern that reaches it, and one written with a subquery on the pattern's rows |
| A node joined by a foreign key, of which nothing is read but a test of a property with a range index over it, is dropped from the plan with its test (Free 23.26). The statement answers for every node, or ends the server process (`ORA-07445` in `kkocfbMarkBindFroCB` with the value bound, `kkosjt` with it written in; `ORA-03113` to the client). It was not seen with the index of a uniqueness constraint, which is over a `CASE`, with a test of the node's labels beside it, or at the far end of a correlated `EXISTS` | The node a load of related nodes starts from is found first, from the node table, and the relationship is tested against it |
| A `MERGE` may not update a column its `ON` reads (`ORA-38104`), unless an index over the expression stands in for the column | The schema marker is found by a query and matched by its id |
| Labels belong to a table, not a row; `!Label` is refused as a "complex label expression" | Labels that are data cannot be SQL/PGQ labels |
| JSON columns are graph properties, usable in `MATCH … WHERE` with `JSON_EXISTS`/`JSON_VALUE` | Labels and properties that are data can be JSON |
| A quantified path takes a predicate on its edge: `-[e IS edge WHERE e.type = 'KNOWS']->{1,2}` | Variable-length and `@GraphPath` reads map directly |
| Multivalue index on a JSON array; function-based and conditional unique indexes on JSON | `@RangeIndex` and `@Unique` have an implementation |
| A property graph can be defined over views, and a property can be an expression | Typed labels can be layered over generic storage later |

## Decision 1: how a graph is stored

**Chosen: one node table and one edge table per graph, with labels and properties as JSON.**

```sql
CREATE TABLE <graph>_node (
    nid    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    labels JSON NOT NULL,          -- ["Person", "Musician"]
    props  JSON NOT NULL,          -- {"id": "ada", "name": "Ada", "metadata.source": "x"}
    stamp  VARCHAR2(33)
);
CREATE TABLE <graph>_edge (
    eid   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    src   NUMBER NOT NULL REFERENCES <graph>_node (nid),
    dst   NUMBER NOT NULL REFERENCES <graph>_node (nid),
    type  VARCHAR2(128) NOT NULL,
    props JSON
);
CREATE PROPERTY GRAPH <graph>
    VERTEX TABLES (<graph>_node KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp))
    EDGE TABLES (<graph>_edge KEY (eid)
        SOURCE KEY (src) REFERENCES <graph>_node (nid)
        DESTINATION KEY (dst) REFERENCES <graph>_node (nid)
        LABEL edge PROPERTIES (eid, type, props));
```

Reads are SQL/PGQ over that graph. A Cypher label becomes a predicate on `labels`, a relationship
type a predicate on `type`.

*As built (`OracleGraph`): the edge table's `src` and `dst` cascade on delete, so a node's
relationships go with its row, and its `props` is never null; `src` and `dst` are properties of the
edge label too; the vertex label has one more property, `doc`, the node as the map a load returns; and
a third table, `<graph>_schema`, records the indexes and constraints Drivine made.*

Why this and not a table per fragment class:

- It is the only layout that carries the whole model. `@NodeLabels`, an open `@PropertyBag`,
  `edges.relate` with a type known at run time, a node read as two classes with overlapping labels,
  and several relationships of one type between two nodes all need labels, keys and types to be
  data. `EntityNode` in `embabel-agent-rag-graph` uses three of those.
- Polymorphism is unchanged: hydration picks the concrete class from the `labels` list of each node
  map, exactly as it does today.
- A save is data, not generated code. With two tables, one fixed PL/SQL block can interpret a save
  described as JSON, where typed tables would need a block generated per model.
- The existing cross-engine contracts can be held against it without carving out exceptions.

What it costs:

- An outside reader of the graph sees one label, `node`, and filters on JSON. Phase 8 adds typed
  labels as generated views, which the probe showed Oracle accepts as vertex tables.
- Every lookup is a JSON predicate, so it is only fast with the indexes of Phase 6. Phase 9 measures.
- An embedding cannot be indexed inside JSON; Phase 7 gives each vector index a side table.

This is the decision to revisit first if the direction is wrong. Phases 1 and 2 do not depend on it.

## Decision 2: where the second backend plugs in

`CypherGrammar` abstracts differences within Cypher and is not the seam. The managers pass Cypher
text between layers (`WHERE` fragments, prologs, bridge variables), so replacing the builders alone
does not work. The cut is one level up: what `GraphObjectManager` and `StatelessGraphObjectManager`
ask of a store, expressed as operations that return a ready `QuerySpecification`.

```
GraphObjectManager / StatelessGraphObjectManager      model, hydration, stamps handed back, retries
        │
   GraphBackend                                        load, count, delete, search, save, edges, bookkeeping
    ├── CypherBackend   the present builders, moved behind the interface, behaviour unchanged
    └── OracleBackend   SQL/PGQ reads, PL/SQL writes
```

The condition AST (`WhereCondition`, `OrderSpec`) is already nearly free of Cypher and is what the
managers hand to the backend. The raw `whereClause: String` overloads stay, and mean "a predicate in
the store's own language".

## Phases

Each phase ends green on the existing suites, and on Oracle with `ORACLE_TESTS=true`.

### Phase 1 — SQL through `PersistenceManager` ✅

- `JdbcConnection`, `JdbcConnectionProvider` (application `DataSource` or its own pool),
  `JdbcResultMapper`, `DatabaseType.ORACLE`, `ConnectionProviderBuilder` wiring.
- `$name` parameters translated to positional markers; `skip`/`limit` as `OFFSET … FETCH`.
- Collections bound as JSON; a JSON column read as a document; `VECTOR` read as a list.
- Transactions, batches, and a block that returns rows.

*As built: the translation is `SqlParameters`, and what is bound is reshaped by
`JdbcParameterCoercer`. A string whose bytes run past 32,767 is bound as a `CLOB`. A pool Drivine
makes is HikariCP, with the driver's statement cache on.*

### Phase 2 — The backend seam (no behaviour change) ✅

- `GraphBackend` and `CypherBackend`; both managers, `EdgeOperations`, `BatchSaveOperations` and the
  scored-search planners go through it.
- Retry is asked of the backend ("was this turned away by another writer?"), not of
  `org.neo4j.driver.exceptions.TransientException`.
- The inline Cypher of `SaveExecutor.staleObject`, `storedKeys`, `StoreIdentityResolver` and
  `SchemaVersionStore` moves behind the backend.
- `PersistenceManager` and `ConnectionProvider` expose the backend; `grammar` remains for Cypher.
- Proof: the full existing suite, unchanged, on Neo4j, FalkorDB and Memgraph.

### Phase 3 — Storage and reads ✅

- `OracleGraphStore`: creates the two tables, their base indexes and the property graph when absent.
  *Built as `OracleGraph`.*
- Load of a fragment and of a view: root, to-one and to-many relationships, nested views,
  `@RelationshipFragment`, `@Count`/`@Aggregate`, `@GraphPath`, recursive and variable-length
  fields, `depth()` overrides. One JSON document per root.
- `SqlConditionGenerator` over the condition AST: property, relationship (`any`/`none`), label,
  list membership, ignore-case, bag keys; ordering, collection sorts, keyset paging, `count`.
  *Built as `OraclePredicates`, with the statement itself written by `OracleSelect` and a raw
  `whereClause` read by `CypherShorthand`.*
- Proof: the read-side cross-engine contracts, their seed data supplied per engine.

### Phase 4 — Writes ✅

- The save block: root (checked or unchecked), dropped relationships, related nodes and their
  relationships, nested views, relationship properties, `NullPolicy`, `only`/`except`, `update`
  digests, `Replace` and `DELETE_UNREFERENCED`, `@NodeLabels`, bags. Same `stamps` row.
- `saveAll`, `edges.relate`/`unrelate`/`unrelateAll`/`loadRelated`, `delete` and cascade delete.
- Deadlocks: nodes are locked in a fixed order; `ORA-00060` and `ORA-08177` are reported as
  contention and retried.
- Proof: `StatelessSaveContract`, `StatelessSaveSoundnessContract`,
  `StatelessSingleStatementContract`, `UncheckedWriteLockContract` with an Oracle subclass each.

### Phase 5 — Bookkeeping ✅

- Store identity, schema version store, `TestCleanup`, and the migration tools that apply.
  *None of the migration tools applies: `RelationshipDirectionRepair` and `PathRelationshipReport`
  repair what saves before 0.1.0 wrote, which no graph on Oracle holds, and they remain Cypher.*

### Phase 6 — Indexes and constraints ✅

- `OracleSchemaGrammar`: `@RangeIndex` as a function-based index, `@Unique` as a conditional unique
  index on (label, property), listing and drift detection from the data dictionary.
- Generated predicates are written in the indexed form, so an index that exists is used.

*As built: listing and drift detection read `<graph>_schema`, a table of the graph's own that records
each item with the name of the object made for it. The dictionary holds an index's expression and
not what it was made for; it is asked only whether the recorded object is still there, and a drop
drops the object the record names. A range index is two indexes: one over the property whatever
the label, which two labels that index a property of one name share and which goes when the last
of them is dropped, and one over the property of the label's nodes alone, which a filter on the
label's nodes is read through (#65). A uniqueness constraint over one property stands for the
second, and none is made beside it. The schema marker and the store's identity are written by the grammar too
(`SchemaGrammar.versionMarker` and `storeStamp`).*

### Phase 7 — Vector and full-text search ✅

- `loadNearest`: exact `VECTOR_DISTANCE` first, then a side table and vector index per
  `VectorIndexSpec`; `searchK`, threshold, partition label, the `{value, score}` row.
- `loadMatching`: Oracle Text, score normalised to the existing contract.

*As built: the table beside the nodes, its trigger and its backfill are `OracleSideTable`, which a
vector index and a full-text index share. A full-text query is read by `TextQuery`, into the text an
Oracle Text index is asked and, for a search with no index, the regular expressions each node's text
is counted against.*

### Phase 8 — Typed labels for outside readers (optional)

- From the annotated model, generate a view per label and a second property graph over the views,
  so `MATCH (p IS Person)` works for SQL written by hand. Read-only; Drivine does not use it.

### Phase 9 — Fit and finish (all but `embabel-agent-rag-graph` done)

- Spring Boot starter properties and test-container support, README, a benchmark against Neo4j on
  the existing manager benchmark, and `embabel-agent-rag-graph` on Oracle.
  *Done: a datasource of `type: ORACLE` is built from the starter's properties, `OracleTestContainer`
  and `@EnableDrivineTestConfig` start Oracle Free for tests, the README has an Oracle section, and
  `OracleManagerBenchmarkTest` measures the workloads under [Outcome](#outcome), though not beside
  Neo4j. Not done: `embabel-agent-rag-graph` on Oracle.*

## Known limits, by design

- A statement written by hand in Cypher does not run on Oracle. The 14 `.cypher` files and
  `CypherFilterConverter` in `embabel-agent-rag-graph` need SQL counterparts or a move to the DSL.
- Temporals are stored in JSON as ISO-8601 text, as on FalkorDB.
- The deprecated manager's own `save` is not ported.
- Oracle stores an empty string as null.

## Risks

1. **Read performance on JSON predicates.** Mitigated by Phase 6, measured in Phase 9. If it is not
   good enough, hot properties can be promoted to virtual columns without changing the layout.
2. **The save block is the largest single piece.** Its contract is already written down as four
   cross-engine suites, and the spike covers its core.
3. **Size limits.** A save's description and a node's properties must be bound as `CLOB` past 32K.
4. **Full-text scoring.** Oracle Text scores are not BM25; normalising them to the existing
   threshold semantics needs its own check.

## Outcome

Every stateless contract that has engine subclasses has an Oracle one: the four save contracts, and
the read, search, label, bag, null-policy, relate, temporal, property-name and store-identity suites.
That includes the contract the review of the stateless manager added, and its read-path tests.
With `ORACLE_TESTS=true`: 563 tests in 57 classes, none failing. The nine skipped are the deprecated
manager's own save, one Memgraph-only case and the benchmark.

What differed from the plan:

- **A save is a plan, not generated code.** One PL/SQL block interprets a JSON array of steps. Its
  text depends only on the kinds of node a save names, so it is compiled once for a view class.
- **Fixtures.** The contracts seed and read back in Cypher. A small interpreter (`MiniCypher`)
  works such a statement out over the graph, so the contracts run unchanged. It ships with the test
  support, in `org.drivine.test`, and is reached through `OracleCypherFixtures`. It reads a statement
  once, into a tree, and refuses what it does not know before it reads the graph.
- **A read is written to be read beside its Cypher.** A row is called what the model calls it, the
  node of a `MATCH` has its row's name, `COLUMNS` names only what the statement reads, and every query
  in a statement is laid out the same way (`Query`, `GraphTable`). The README shows one.
- **An index is a table beside the nodes.** Oracle indexes a `VECTOR` column, not a list inside JSON,
  so a vector index is a table of embeddings that a trigger keeps in step, with the index (neighbour
  partitions) over it. A full-text index is the same with an Oracle Text index. Without an index, or
  without Oracle Text (the slim image), a search reads every node of the label.

What the work found that the plan did not foresee:

- **A lock taken through a function-based index can hang.** `SELECT … FOR UPDATE` through the index
  over a node's id waited for a row lock while holding a page of that index; the lock's holder, whose
  update keeps the index, waited for the page. Oracle reports no deadlock. A node is now found without
  a lock and locked by its key.
- **The multivalue index over labels is chosen too eagerly.** With a label and an id in one
  predicate, Oracle read every node of the label. Labels are tested in a form that index cannot
  answer wherever a node is found another way.
- **An Oracle Text index must not be on the node table.** It holds what has been committed, and once
  it is there Oracle answers ordinary tests of a JSON property from it. A save could not find a node it
  had made a moment before in the same statement, and made it again.
- **A bound JSON array named twice in one statement gave wrong counts** from `JSON_TABLE` on 23.26.
  Full-text terms are bound one by one.
- **A node reached through a relationship fragment was loaded without its stamp.** Oracle returns a
  node as it is stored, and the stored names were mapped back to field names for every shape but that
  one. The contracts did not assert the loaded stamp there; one does now.

What holds on Oracle and not on the graph engines:

- **An empty string is null.** A stamp stored as `''` is no stamp. A property saved as `""` is kept
  in the node's JSON, and `JSON_VALUE` reads it as null: the query DSL writes a comparison with `""`
  as a JSON path test, so that it still finds one.
- **A load reads the node as stored**, so a view can be filtered and ordered by a property no field
  declares, where a graph engine refuses to read past its projection.
- **A save that drops the stale keys of a property bag is one statement**, not a read and a write.

Measured on 20,000 claims, 20,000 humans and 60,000 relationships, in a container on a laptop, with
the id of each label constrained unique (median):

| Workload | Time |
|---|---|
| load a fragment by id | 0.8 ms |
| load a view (3 related nodes) | 1.3 ms |
| create a fragment | 3.6 ms |
| load, change, save a view (`update`) | 5.2 ms |
| save a new view with 10 new related nodes | 5.9 ms |
| save a new view with 1,000 new related nodes | 258 ms |
| `saveAll` 1,000 new fragments | 181 ms |

Not compared with Neo4j: `ManagerBenchmarkNeo4jTest` was not run alongside.

Still to do: typed labels as views (phase 8), a neighbour-graph (HNSW) vector index for a database
with vector memory set aside, a number-typed range index, and `embabel-agent-rag-graph` on Oracle.
