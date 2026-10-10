# Plan: `StatelessGraphObjectManager` on Oracle (SQL/PGQ)

Status: **in progress** on `feat/oracle-sql-pgq`. Target: Oracle Database 23ai / 26ai.

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

### Phase 2 — The backend seam (no behaviour change)

- `GraphBackend` and `CypherBackend`; both managers, `EdgeOperations`, `BatchSaveOperations` and the
  scored-search planners go through it.
- Retry is asked of the backend ("was this turned away by another writer?"), not of
  `org.neo4j.driver.exceptions.TransientException`.
- The inline Cypher of `SaveExecutor.staleObject`, `storedKeys`, `StoreIdentityResolver` and
  `SchemaVersionStore` moves behind the backend.
- `PersistenceManager` and `ConnectionProvider` expose the backend; `grammar` remains for Cypher.
- Proof: the full existing suite, unchanged, on Neo4j, FalkorDB and Memgraph.

### Phase 3 — Storage and reads

- `OracleGraphStore`: creates the two tables, their base indexes and the property graph when absent.
- Load of a fragment and of a view: root, to-one and to-many relationships, nested views,
  `@RelationshipFragment`, `@Count`/`@Aggregate`, `@GraphPath`, recursive and variable-length
  fields, `depth()` overrides. One JSON document per root.
- `SqlConditionGenerator` over the condition AST: property, relationship (`any`/`none`), label,
  list membership, ignore-case, bag keys; ordering, collection sorts, keyset paging, `count`.
- Proof: the read-side cross-engine contracts, their seed data supplied per engine.

### Phase 4 — Writes

- The save block: root (checked or unchecked), dropped relationships, related nodes and their
  relationships, nested views, relationship properties, `NullPolicy`, `only`/`except`, `update`
  digests, `Replace` and `DELETE_UNREFERENCED`, `@NodeLabels`, bags. Same `stamps` row.
- `saveAll`, `edges.relate`/`unrelate`/`unrelateAll`/`loadRelated`, `delete` and cascade delete.
- Deadlocks: nodes are locked in a fixed order; `ORA-00060` and `ORA-08177` are reported as
  contention and retried.
- Proof: `StatelessSaveContract`, `StatelessSaveSoundnessContract`,
  `StatelessSingleStatementContract`, `UncheckedWriteLockContract` with an Oracle subclass each.

### Phase 5 — Bookkeeping

- Store identity, schema version store, `TestCleanup`, and the migration tools that apply.

### Phase 6 — Indexes and constraints

- `OracleSchemaGrammar`: `@RangeIndex` as a function-based index, `@Unique` as a conditional unique
  index on (label, property), listing and drift detection from the data dictionary.
- Generated predicates are written in the indexed form, so an index that exists is used.

### Phase 7 — Vector and full-text search

- `loadNearest`: exact `VECTOR_DISTANCE` first, then a side table and vector index per
  `VectorIndexSpec`; `searchK`, threshold, partition label, the `{value, score}` row.
- `loadMatching`: Oracle Text, score normalised to the existing contract.

### Phase 8 — Typed labels for outside readers (optional)

- From the annotated model, generate a view per label and a second property graph over the views,
  so `MATCH (p IS Person)` works for SQL written by hand. Read-only; Drivine does not use it.

### Phase 9 — Fit and finish

- Spring Boot starter properties and test-container support, README, a benchmark against Neo4j on
  the existing manager benchmark, and `embabel-agent-rag-graph` on Oracle.

## Known limits, by design

- A statement written by hand in Cypher does not run on Oracle. The 14 `.cypher` files and
  `CypherFilterConverter` in `embabel-agent-rag-graph` need SQL counterparts or a move to the DSL.
- Temporals are stored in JSON as ISO-8601 text, as on FalkorDB.
- The deprecated manager's own `save` is not ported.

## Risks

1. **Read performance on JSON predicates.** Mitigated by Phase 6, measured in Phase 9. If it is not
   good enough, hot properties can be promoted to virtual columns without changing the layout.
2. **The save block is the largest single piece.** Its contract is already written down as four
   cross-engine suites, and the spike covers its core.
3. **Size limits.** A save's description and a node's properties must be bound as `CLOB` past 32K.
4. **Full-text scoring.** Oracle Text scores are not BM25; normalising them to the existing
   threshold semantics needs its own check.
