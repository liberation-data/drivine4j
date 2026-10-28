## Schema Management (Indexes & Constraints)

Drivine manages vector indexes, full-text indexes, range indexes, Neo4j's TEXT indexes, and uniqueness constraints across Neo4j, Memgraph, FalkorDB, Oracle and Postgres — with idempotent, drift-aware `ensure` semantics and engine differences (DDL syntax, introspection, FalkorDB's Redis-command constraints) handled for you. Opt-in: declare nothing, pay nothing.

### Imperative API

Every `PersistenceManager` exposes `indexes` and `constraints` managers. Operations always run in auto-commit mode (schema DDL cannot run inside a data transaction):

```kotlin
// Idempotent — call on every startup
val result = persistenceManager.indexes.ensure(
    VectorIndexSpec(label = "Proposition", property = "embedding", dimensions = 1536)
)
persistenceManager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
persistenceManager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))  // composite
persistenceManager.indexes.ensure(FullTextIndexSpec("Chunk", "text"))                            // full-text (single property)
persistenceManager.indexes.ensure(FullTextIndexSpec("Entity", listOf("name", "description")))    // full-text (multi-property)
persistenceManager.indexes.ensure(TextIndexSpec("Chunk", "title"))                               // Neo4j TEXT index

persistenceManager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
persistenceManager.constraints.ensure(UniquenessConstraintSpec("Membership", listOf("tenantId", "userId")))
```

`ensure` returns an `EnsureResult`:

| Result | Meaning |
|---|---|
| `Created` | Nothing existed and the item was created, or it existed without part of what the engine makes for it (on Oracle, a range index without its index over the label's nodes) and that part was made |
| `AlreadyMatching` | A matching item exists; nothing changed |
| `Drift` | An item exists with a **different shape** (e.g. vector dimensions changed). Nothing changed — call `recreate(spec)` to replace it (destructive) |
| `Violation` | Constraint only: existing data violates it — a key the constraint covers repeats. Includes a bounded sample of the conflicting values, each with the nodes that hold it: their engine id (`elementId`), and their `@NodeId` when the constraint is declared on a fragment, as many nodes per value as `violationSampleSize`. A refusal the data does not bear out (no key repeats) is thrown, not reported as a `Violation` |
| `BlockedByIndex` | Constraint only: a plain range index over the same label and properties is in the way of it, and is named. Neo4j alone refuses a constraint for one, since its constraint brings its own index. Nothing changed — drop the index, or ensure with `replaceBlockingIndex = true` (`drivine.schema.replace-blocking-index=true`), which drops it and makes the constraint |
| `Recreated` | From an explicit `recreate(spec)` — old item dropped, new one created |

**A TEXT index is Neo4j's.** `TextIndexSpec` (or `@TextIndex` on a property) makes `CREATE TEXT INDEX … FOR (n:Label) ON (n.prop)`. That index is over one string property and serves `CONTAINS`, `STARTS WITH` and `ENDS WITH` on it. It is not a full-text index, and Neo4j lists, drift-checks and drops it as a kind of its own (`SchemaItemKind.TEXT_INDEX`), so the two can stand on the same property. No other engine has one. There, `ensure`, `recreate`, `find` and `drop` throw a `DrivineException` before anything runs, rather than make another index in its place, and applying a catalog that declares one fails for its owner. Mind the names: what Memgraph calls a `TEXT INDEX` is a full-text index, declared with `FullTextIndexSpec` / `@FullTextIndex`.

### Declarative: SchemaCatalog bean

Register a `SchemaCatalog` bean and Drivine ensures everything on startup (indexes before constraints):

```kotlin
@Bean
fun propositionSchema(embeddingService: EmbeddingService) = SchemaCatalog.of(
    VectorIndexSpec("Proposition", "embedding", embeddingService.dimensions),
    RangeIndexSpec("Proposition", "contextId"),
    UniquenessConstraintSpec("Proposition", "id"),
)
```

Multiple catalog beans merge; identical declarations deduplicate; conflicting declarations for the same (kind, label, properties) fail startup.

**Which databases a catalog applies to.** By default a catalog broadcasts to **every schema-capable registered database** — engines without DDL support (Neptune, openCypher) are skipped with a warning. Narrow it when you need to:

```kotlin
SchemaCatalog.of(...)                        // all schema-capable databases (default)
SchemaCatalog.of(...).forDefaultDatabase()   // only the primary (first-registered) datasource
SchemaCatalog.of(...).forDatabase("users")   // one named datasource
SchemaCatalog.of(...).forDatabases("a", "b") // a specific set
```

Broadcast is lenient (skips engines that can't do schema); an explicitly **named** target is strict — pointing it at an unknown or schema-incapable datasource fails startup. `"default"` resolves to the first-registered datasource, consistent with the rest of Drivine.

Targeting is replace/last-wins, not additive — use one `forDatabases("a", "b")` call to target several; chaining `forDatabase(...)` calls does not accumulate (last wins).

### Declarative: annotations on fragments

```kotlin
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId
    @RangeIndex
    @Unique
    val id: String,

    @RangeIndex
    val contextId: String,

    val text: String,

    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>?,

    @FullTextIndex                       // full-text index on this property (also drives loadMatching)
    val body: String,

    @TextIndex                           // Neo4j TEXT index, for CONTAINS / STARTS WITH / ENDS WITH
    val title: String,
)

// Composite declarations go on the class
@NodeFragment(labels = ["Message"])
@RangeIndex(properties = ["sessionId", "createdAt"])
@Unique(properties = ["sessionId", "sequence"])
@FullTextIndex(properties = ["subject", "body"])   // multi-property full-text index
data class MessageNode(/* ... */)
```

Scan them into a catalog — vector dimensions come from your embedding model at runtime, via a `VectorDimensionProvider`:

```kotlin
@Bean
fun schema(embeddingService: EmbeddingService) = SchemaCatalog.fromFragments(
    VectorDimensionProvider { _, _ -> embeddingService.dimensions },
    PropositionNode::class,
    MessageNode::class,
)
```

Works for Java fragments too (`SchemaCatalog.fromFragments(JavaNode.class)`).

### Runtime enforcement (`SchemaManager`)

Catalogs are applied by a `SchemaManager` bean, enforced once on startup. It's also injectable, so you can drive schema changes at runtime — e.g. build indexes *after* a bulk load, or rebuild after re-embedding:

```kotlin
@Component
class Reindexer(private val schema: SchemaManager) {
    fun afterBulkLoad() {
        schema.enforce()      // idempotent: create what's missing, recreate on version change
    }
    fun afterReembed() {
        schema.recreateAll()  // brute-force: drop + recreate every declared item
    }
}
```

`enforce()` is safe to call repeatedly; `recreateAll()` is the destructive hammer. (`drivine.schema.enabled=false` disables only the startup run — the bean is still there for runtime calls.)

The manager reads the catalog beans **once**, when it is built: a `SchemaCatalog` bean made later is not seen, and a later `enforce()` applies the same catalogs. With nothing to apply it says so at INFO — `Schema management found no SchemaCatalog` when none was registered, or `found 2 SchemaCatalog(s), all empty: 'dice', unnamed` when every one registered nothing — so that a missing index can be traced to a catalog that never arrived. Schema known only later is given to `ensure`, below.

### Schema known only at runtime (`SchemaManager.ensure`)

Some schema cannot be in a catalog because it is not known when the catalogs are read: a vector index whose dimensions arrive with an embedding model configured after startup, an index for each label a tenant declares. Give it to `ensure`, under the owner it belongs to:

```kotlin
@Component
class PropositionIndex(private val schema: SchemaManager) {
    fun modelArrived(dimensions: Int) {
        schema.ensure("dice", VectorIndexSpec("Proposition", "embedding", dimensions))
    }
}
```

The items are added to what that owner's catalogs declared, for the life of the manager, and applied as `enforce` applies the owner's:

- **Under the same policy.** `drivine.schema.mode`, `recreate-on-drift` and `violation-sample-size` apply: a drift or a violation fails under `FAIL_FAST` and is logged under `WARN`, and the same summary line is logged. Only the items given are applied; the owner's others are left as they are. `ensure` neither adopts nor acts on a change of the owner's version, which is `enforce`'s, and `recreate-on-startup` does not apply to it.
- **In the owner's inventory.** They are recorded in the owner's `_DrivineSchema` marker, marked as ensured at runtime. A later `enforce()` or `enforce("dice")` applies them with the rest, and `recreateAll` recreates them. An `enforce` by a manager built later — the next process, before the item is ensured again — keeps them in the inventory and does not report them as orphans.
- **Recreated with the owner.** When that later `enforce` recreates the owner's items (its version changed, or `recreateAll`), it cannot recreate a runtime item whose spec it has not been given, so it marks it, and the next `ensure` of the item recreates it.
- **Checked against other owners.** A declaration that conflicts with another owner's, or with the owner's own, fails naming both, and nothing is added. One identical to another owner's is co-owned, as between catalogs.

Ensuring an item again with the same kind, label and properties replaces it: of another shape it is drift, handled per the policy. One given under a new name leaves the old name an orphan of the owner, reported by the next apply. To target databases, give a catalog: `schema.ensure(SchemaCatalog.of(spec).named("dice").forDatabase("users"))`. A runtime catalog cannot carry a version, since the owner's version is its startup catalogs'.

### Version-triggered rebuild

Drift detection only catches changes introspection can *see* (dimensions, similarity, properties). A change it can't see — swapping the embedding model for one with the **same dimensions** — leaves stale vectors behind. Tag a catalog with a version token to force a one-time rebuild when that token changes:

```kotlin
@Bean
fun chunkSchema(embeddingService: EmbeddingService) = SchemaCatalog.of(
    VectorIndexSpec("Chunk", "embedding", embeddingService.dimensions),
).withVersion(embeddingService.modelId)   // bump this → recreate once
```

How it works: the last-applied token is stored in a reserved `_DrivineSchema` marker node per database (portable across the engines; on Oracle it is a row of the graph's node table). On `enforce()`, a **changed** token drops and recreates that catalog's items, then records the new token; a first-ever token is *adopted* without recreating, so turning versioning on never nukes a healthy schema.

> Recreating a vector index rebuilds it from the stored embedding *properties* — it does **not** re-embed. After a model swap, re-embed the nodes first (same dimensions → the index even auto-updates), then `enforce()`/`recreateAll()` for the structural half. Drivine manages schema, not your embeddings.

### Configuration

```yaml
drivine:
  schema:
    enabled: true              # master switch for startup initialization
    mode: FAIL_FAST            # FAIL_FAST (default) or WARN on drift/violations
    recreate-on-drift: false   # destructive: rebuild items whose shape changed
    recreate-on-startup: false # destructive: rebuild everything every startup
    violation-sample-size: 10  # conflicting rows sampled on constraint violations, and nodes named per row
    replace-blocking-index: false # drop a plain index in the way of a uniqueness constraint (Neo4j)
```

### Per-engine notes

| | Neo4j | Memgraph | FalkorDB |
|---|---|---|---|
| Vector indexes | `CREATE VECTOR INDEX … IF NOT EXISTS` | `WITH CONFIG {…}`, uSearch metrics | `OPTIONS {…}`, unnamed |
| Full-text indexes | `CREATE FULLTEXT INDEX … ON EACH […]` (+ analyzer) | `CREATE TEXT INDEX … ON :L(props)` | `CREATE FULLTEXT INDEX FOR (n:L) ON (n.prop)`, per-property, unnamed |
| Range indexes | Named, composite supported | Label-property style | Per-label coverage; extended incrementally |
| TEXT indexes | `CREATE TEXT INDEX … IF NOT EXISTS FOR (n:L) ON (n.prop)`, from Neo4j 4.4 | Refused: its `TEXT INDEX` is full-text, above | Refused |
| Uniqueness | `REQUIRE … IS UNIQUE` | `ASSERT … IS UNIQUE` | **Redis command** `GRAPH.CONSTRAINT` (not Cypher) — Drivine issues it at driver level, auto-creates the required backing index, and polls the asynchronous build. A build FalkorDB marks `FAILED` is dropped, since it enforces nothing yet stays listed, and is a `Violation` when a key repeats. One found already in the store is dropped and built again, not taken as `AlreadyMatching`, and `drop` removes one. One found still `UNDER CONSTRUCTION` (another process's build, or an earlier run's) is waited on: `AlreadyMatching` once built, dropped and built again if its build fails |
| Item names | Yes | Vector only | No |

**Oracle.** Every item is an index over an expression, since a property is a path into a JSON column, and what Drivine made is recorded in the table `<graph>_schema`, which `ensure` and drift detection read. An item whose index or table was dropped by hand is made again by the next `ensure`.

| | Oracle |
|---|---|
| Vector indexes | A table of embeddings (`VECTOR(n, FLOAT32)`) beside the nodes, kept in step by a trigger and filled from the nodes there are, with `CREATE VECTOR INDEX … ORGANIZATION NEIGHBOR PARTITIONS` over it. An embedding of another dimension leaves its node out of the index |
| Full-text indexes | A table of the indexed text beside the nodes, kept in step by a trigger, with an Oracle Text search index over it. Without Oracle Text the index is recorded and nothing is made |
| Range indexes | Two indexes. `CREATE INDEX … (JSON_VALUE(props, '$.p'))`, the property read as text, over the property whatever the label, so two labels that index a property of one name share it: a save and a load by id find a node by it. And `CREATE INDEX … (CASE WHEN JSON_EXISTS(n.labels, '$[*]?(@ == "Label")') THEN JSON_VALUE(n.props, '$.p') END)`, over the property of the label's nodes alone, which a filter on the label's nodes reads. Composite supported |
| TEXT indexes | Refused: Neo4j's alone |
| Uniqueness | A unique index over the property for the nodes that carry the label, with the range index a node is found by. A node that lacks one of the properties is not constrained. Over one property, the unique index stands for the range index's index over the label's nodes, and none is made beside it |
| Item names | Recorded; the Oracle object's name is derived from the graph, label and properties, and recorded with it. A drop drops the object the record names |

The store's identity (`DrivineStore`) and the schema marker are nodes of the graph on Oracle too, and the graph's tables are made for them when they are the first thing asked of a new graph.

**Postgres.** Every item is a partial index over the nodes of its label, recorded in `<graph>_schema` as on Oracle. An index dropped by hand is not listed, and is made again by the next `ensure`. The store's identity and the schema marker are nodes of the graph, as on Oracle.

| | Postgres |
|---|---|
| Vector indexes | `CREATE INDEX … USING hnsw ((CAST(CAST(props -> 'p' AS text) AS vector(n))) vector_cosine_ops) WHERE labels @> '["Label"]' AND …`, over the embeddings of the label's nodes that are of its size (`vector_l2_ops` for Euclidean). Needs pgvector, which the index makes where it may |
| Full-text indexes | `CREATE INDEX … USING gin ((lower(…)) gin_trgm_ops) WHERE labels @> '["Label"]'`, over the text a search reads, with `pg_trgm`, which the index makes where it may |
| Range indexes | `CREATE INDEX … ((props ->> 'p')) WHERE labels @> '["Label"]'`, one per label. Composite supported |
| TEXT indexes | Refused: Neo4j's alone |
| Uniqueness | The same index made `UNIQUE`, which also finds a node, so no range index is made beside it. A node that lacks one of the properties is not constrained |
| Item names | Recorded; the index's name is derived from the graph, label and properties, and Postgres keeps it in lower case. A drop drops the index the record names |

Neptune and generic openCypher have no schema management support — operations fail loudly rather than silently no-op.

