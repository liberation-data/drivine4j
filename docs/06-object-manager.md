## Object Manager - Type-Safe Graph Mapping

`StatelessGraphObjectManager` is a high-level API for working with graph-mapped objects using annotated models. It generates efficient Cypher queries automatically and provides a type-safe DSL for filtering and ordering.

It keeps no state between calls. What a load returns is what the store holds, and what a save writes depends only on the object and the arguments you pass.

Get one from the `GraphObjectManagerFactory`, which the Spring Boot starter provides:

```kotlin
@Bean
fun graphObjectManager(factory: GraphObjectManagerFactory): StatelessGraphObjectManager = factory.stateless()
```

> Earlier releases documented `GraphObjectManager`, which tracks what it loads in a session. It is deprecated: see [Migrating from GraphObjectManager](15-migrating-from-graphobjectmanager.md#migrating-from-graphobjectmanager).
>
> Upgrading from 0.0.x? [docs/0.1.0-stateless-object-manager.md](../docs/0.1.0-stateless-object-manager.md) lists what changed and what to do about data saved before it.

### Key Concepts

#### 1. NodeFragment - Mapping Nodes

A `@NodeFragment` represents a single node in the graph:

```kotlin
@NodeFragment
data class Person(
    @NodeId val uuid: String,
    val name: String,
    val bio: String?
)

@NodeFragment
data class Organization(
    @NodeId val uuid: String,
    val name: String
)
```

**Defaults for missing properties.** When a node is missing a property, the value loads as `null` — which fails for a non-nullable type. Two annotations handle that without any Jackson knowledge:

```kotlin
@NodeFragment(labels = ["User"])
data class UserNode(
    @NodeId val id: String,
    @Default val roles: List<String> = emptyList(),  // missing/null → the declared default []
    @Default val status: String = "active",          // missing/null → "active"
    @EmptyWhenAbsent val tags: List<String>,         // missing/null → [] (no default needed)
)
```

- **`@Default`** falls back to the property's declared default (a Kotlin constructor default, or a Java field initializer). Works for any type; a provided value always wins.
- **`@EmptyWhenAbsent`** maps an absent/null collection or map to empty, with no declared default required — the right choice for **Java records**, whose components have no field initializer:
  ```java
  public record UserNode(@NodeId String id, @EmptyWhenAbsent List<String> roles) {}
  ```

#### 2. RelationshipFragment - Capturing Relationship Properties

A `@RelationshipFragment` captures properties on relationship edges, not just the target node:

```kotlin
@RelationshipFragment
data class WorkHistory(
    val startDate: LocalDate,  // Property on the edge
    val role: String,           // Property on the edge
    val target: Organization    // Target node
)
```

This is useful for modeling:
- Employment history (start date, role, organization)
- Transaction records (timestamp, amount, target account)
- Audit trails (timestamp, action, target entity)
- Any relationship with metadata

#### 3. GraphView - Composing Views

A `@GraphView` composes multiple fragments and relationships into a single query result:

```kotlin
@GraphView
data class PersonCareer(
    @Root val person: Person,  // Root fragment

    @GraphRelationship(type = "WORKS_FOR")
    val employmentHistory: List<WorkHistory>  // Relationship with properties
)
```

The `@Root` annotation marks which fragment is the query's starting point.

#### 4. Recursive Relationships - Hierarchies & Ontologies

Graph databases excel at recursive structures — ontologies, org charts, location hierarchies. Drivine supports self-referential `@GraphView` classes where a relationship targets its own type, expanding to a configurable depth using nested pattern comprehensions.

**Define a recursive view:**

```kotlin
@NodeFragment(labels = ["Location"])
data class Location(
    @NodeId val uuid: UUID,
    val name: String,
    val type: String
)

@GraphView
data class LocationHierarchy(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.OUTGOING, maxDepth = 3)
    val subLocations: List<LocationHierarchy>  // Self-referential!
)
```

`maxDepth = 3` means Drivine expands 3 levels deep. Loading a continent produces:

```
Europe (continent)
├── Western Europe (region)
│   ├── France (country) → subLocations: []
│   ├── Germany (country) → subLocations: []
│   └── ...
├── Northern Europe (region)
│   ├── Sweden (country) → subLocations: []
│   └── ...
└── ...
```

At the terminal depth, collections become `[]` and nullable singles become `null`.

**Generated Cypher** (abbreviated):

```cypher
MATCH (location:Location)
WITH
    location { name: location.name, type: location.type, uuid: location.uuid } AS location,
    [(location)-[:HAS_LOCATION]->(sub_d1:Location) |
        sub_d1 {
            location: { name: sub_d1.name, type: sub_d1.type, uuid: sub_d1.uuid },
            subLocations: [(sub_d1)-[:HAS_LOCATION]->(sub_d2:Location) |
                sub_d2 {
                    location: { name: sub_d2.name, ... },
                    subLocations: [(sub_d2)-[:HAS_LOCATION]->(sub_d3:Location) |
                        sub_d3 { location: { ... }, subLocations: [] }
                    ]
                }
            ]
        }
    ] AS subLocations
RETURN { location: location, subLocations: subLocations } AS result
```

**Traversing upward** — create a different view with `Direction.INCOMING`:

```kotlin
@GraphView
data class LocationAncestry(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.INCOMING, maxDepth = 3)
    val parent: LocationAncestry?  // Nullable single — each location has at most one parent
)
```

Loading "France" returns France → Western Europe → Europe → (terminated).

**Query-time depth override:**

```kotlin
graphObjectManager.loadAll<LocationHierarchy> {
    depth("subLocations", 5)  // Override annotation's maxDepth=3 to 5
    where { query.location.type eq "continent" }
}
```

**Querying a level below.** The generated DSL reaches each level, built only when it is read, so a where can test the targets of the targets: `where { subLocations.subLocations.location.name eq "France" }`. A where of a load reaches as deep as the DSL does, `subLocations.subLocations.subLocations` and below, each level checked in turn, on every store. Conditions on two relationships of one nested view hold of one node of it: `where { desks.employer.name eq "Acme"; desks.mentor.name eq "Ann" }` finds the offices with a desk that does both. Neo4j and Memgraph check each relationship within the check of the view; FalkorDB and Neptune match one pattern, with each relationship led from the node they share. In a scored search (`loadNearest`, `loadMatching`) on a graph engine it reaches two levels, the nested view's own relationships, as the projection is filtered: one deeper is refused with an `IllegalArgumentException`.

**Chain cycles** (A → B → A) are also supported. When a relationship targets a `@GraphView` that forms a cycle through other types, Drivine tracks visit counts and terminates at `maxDepth`:

```kotlin
@GraphView
data class PersonOrgView(
    val person: Person,
    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val employer: OrgPersonView?
)

@GraphView
data class OrgPersonView(
    val org: Organization,
    @GraphRelationship(type = "EMPLOYS", direction = Direction.OUTGOING, maxDepth = 2)
    val employees: List<PersonOrgView>
)
```

**A list of fragments over several hops.** `maxDepth` above 1 on a `List<Fragment>` field reads every node of the
target's labels within that many hops (`-[:NEXT*1..2]->`), each once, and never over one relationship twice in a
path. It does so at every level, on a view nested in another too: there the graph engines read only one hop before
liberation-data/drivine4j#461, while the SQL stores read the whole depth, so a nested load on Neo4j, Memgraph or
FalkorDB may now give more nodes than it did.

**Saving.** A view nested in itself, as above, is saved: each level is one relationship from the level above. A list of *fragments* read over several hops (`maxDepth` above 1 on a `List<Fragment>` field) is loaded and never written, like a path field: see [`@ReadOnly`](#readonly-a-field-that-is-loaded-and-never-written). A recursive collection cannot be sorted in the query; use [`@SortedBy`](#client-side-sorting-with-sortedby), which orders every level.

#### 5. Path Traversal - Skipping Intermediary Nodes

`@GraphRelationship` is a single hop. `@GraphPath` traverses several and maps only the **final** node, skipping the ones in between:

```kotlin
@GraphView
data class ActorDirectors(
    @Root val actor: Actor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN",    Direction.OUTGOING, label = "Movie"),  // through Movie — not mapped
        Hop("DIRECTED_BY", Direction.OUTGOING),                   // to Director
    ])
    val directors: List<Director>,
)
```

The far node is **de-duplicated** (an actor who made two movies by the same director gets that director once). Field cardinality mirrors `@GraphRelationship`: `List<T>` is a collection, `T?` a single optional, `T` a required single (roots lacking the path are filtered out). Each `Hop`'s `label` optionally constrains the node it reaches; `maxDepth` does not apply (a path is a fixed hop list, not variable-length recursion).

One hop may name an **order**: a property of the node it reaches, read lowest first unless `ascending = false`. A list then holds its targets in the order of the first node of that hop that leads to each, and a single field holds the target the first such node leads to:

```kotlin
@GraphView
data class SessionStarter(
    @Root val session: Session,
    @ReadOnly
    @GraphPath([
        Hop("HAS_MESSAGE", Direction.OUTGOING, label = "Message", orderBy = "sentAt"),  // earliest message first
        Hop("AUTHORED_BY", Direction.OUTGOING),
    ])
    val startedBy: Person?,   // who wrote the first message
)
```

A node of the ordered hop that has no value for the property comes last, whichever way the path is read; an empty text is a value, and the lowest text. Targets whose first values are equal come in no set order. The property should hold values of one type: every backend reads the order the same way where it does, and a text of digits such as `'0100'` is ordered as text. Where it holds texts on some nodes and numbers on others, Neo4j, FalkorDB, Oracle and Memgraph 3.14 and later read every text before every number, lowest first; an earlier Memgraph refuses to compare them.

#### 6. Aggregates - Counting & Summarizing Without Loading

`@Count` and `@Aggregate` add per-root scalar fields computed in the query, so you don't load a collection just to size or summarize it:

```kotlin
@GraphView
data class ActorStats(
    @Root val actor: Actor,
    @ReadOnly @Count("ACTED_IN")
    val movieCount: Long,
    @ReadOnly @Aggregate(AggregateFunction.AVG, type = "RATED", property = "score")
    val avgRating: Double,
    @ReadOnly @Aggregate(AggregateFunction.SUM, type = "RATED", property = "score")
    val totalRating: Double,
)
```

`@Count` needs no property; `SUM`/`AVG`/`MIN`/`MAX` aggregate a numeric property of the related nodes. Aggregates are single-hop, and belong to one root. To group nodes by a key and count or rank the groups (a top-N), use a [grouped read](#grouped-reads-groups).

> All three — path traversal and aggregates — work identically across Neo4j, Memgraph, and FalkorDB.

### Loading Data

#### Load All Instances

```kotlin
@Component
class PersonService @Autowired constructor(
    private val graphObjectManager: StatelessGraphObjectManager
) {
    fun getAllPeople(): List<PersonCareer> {
        return graphObjectManager.loadAll<PersonCareer>()
    }
}
```

#### Load by ID

```kotlin
fun getPerson(uuid: String): PersonCareer? {
    return graphObjectManager.load<PersonCareer>(uuid)
}
```

#### Count

`count` returns a `Long` and is **consistent with `loadAll`** — it counts exactly the objects `loadAll` would return for the same type and filter. There are three overloads, mirroring `loadAll`/`deleteAll`:

```kotlin
// 1. Count everything of a type (reified, or pass Issue::class.java)
val total: Long = graphObjectManager.count<Issue>()

// 2. Count with a simple WHERE filter (aliases match loadAll: `n` for fragments,
//    the root fragment field name for views)
graphObjectManager.count<Issue>("n.state = 'open'")

// 3. Count with the type-safe DSL (generated per-view extension injects the query object)
graphObjectManager.count<RaisedAndAssignedIssue> {
    where { query.issue.state eq "open" }
}
```

The codegen emits INSTANCE-injecting extensions for every DSL-spec method — `loadAll<T> { }`,
`deleteAll<T> { }`, `count<T> { }`, and `loadNearest<T>(…) { }` (the last only for `@VectorIndex`-ed
views). The reified `count<T>()` / `loadNearest<T>(…)` and the explicit `::class.java` overloads also
remain (the latter for Java).

**Fragments** are a straight node count of the fragment's labels.

**Views count only roots that satisfy the view's _required_ relationships** — non-optional, non-collection `@GraphRelationship`s — so the result equals `loadAll(...).size`, *not* a naive node count. For example, `RaisedAndAssignedIssue` requires `raisedBy` (a single, non-null `RAISED_BY`):

```kotlin
graphObjectManager.count<Issue>()                    // 3 — every Issue node
graphObjectManager.count<RaisedAndAssignedIssue>()   // 2 — only Issues with a RAISED_BY edge
```

An `Issue` with no `RAISED_BY` is a valid `Issue` node but is **not** a `RaisedAndAssignedIssue`, so it is excluded — just as `loadAll` would exclude it. Optional (nullable) and collection relationships place no such constraint.

From Java, the same three overloads apply (use `.count(Issue.class)` etc.); the DSL overload takes the generated query object and a lambda.

#### Vector Search (`loadNearest`)

`loadNearest` runs an approximate nearest-neighbour search and returns the hits paired with a
normalized similarity score. Like `loadAll`, it works on both a **`@GraphView`** — searching the
root fragment's embedding and returning the fully-projected view — and a plain **`@NodeFragment`**,
searching and returning the bare nodes:

```kotlin
// Search a view: ranks PropositionViews by their root proposition's embedding
val views: List<Scored<PropositionView>> =
    graphObjectManager.loadNearest<PropositionView>(queryEmbedding, topK = 20)

// Search a fragment: ranks bare PropositionNodes
val nodes: List<Scored<PropositionNode>> =
    graphObjectManager.loadNearest<PropositionNode>(queryEmbedding, topK = 20)
```

The embedding to search is identified by a `@VectorIndex` annotation on the searched fragment (the
view's root fragment, or the fragment itself) —
**inferred** when the fragment declares one embedding, or **named explicitly** to pick among
several. `@VectorIndex` is the query-side declaration of "this embedding is searchable" and is
independent of how the index is *created*: it works whether you create the index from the annotation
(`SchemaCatalog.fromFragments(...)`) or from an explicit spec (`SchemaCatalog.of(VectorIndexSpec(...))`);
in the latter case, add the annotation to the fragment as well — it carries no creation side effects
on its own. Drivine refuses a catalog spec over a fragment field that has no `@VectorIndex`, naming the
class, the field and the spec: without the annotation FalkorDB writes the field as a plain list and
`loadNearest` finds no index. The check runs when `SchemaManager` applies the catalog and when the
fragment class is first used. A spec for a label no fragment maps is not checked.

```kotlin
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId val id: String,
    val text: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
)

@GraphView
data class PropositionView(
    @Root val proposition: PropositionNode,
    @GraphRelationship(type = "HAS_MENTION", direction = Direction.OUTGOING)
    val mentions: List<Mention>,
)
```

```kotlin
// Single embedding on the root fragment → inferred, no property argument
val hits: List<Scored<PropositionView>> =
    graphObjectManager.loadNearest<PropositionView>(queryEmbedding, topK = 20)

hits.forEach { println("${it.score} → ${it.value.proposition.text}") }

// Disambiguate when a node carries several embeddings, and/or floor by similarity
graphObjectManager.loadNearest<PropositionView>("titleEmbedding", queryEmbedding, topK = 20, threshold = 0.8)
```

Each result is a `Scored<T>(value, score)`; the score is normalized to **similarity, higher = more
similar** on every engine, so ordering and `threshold` mean the same thing regardless of backend.
It is in `[0, 1]`: `(1 + cos) / 2` for a cosine index, and `1 / (1 + d²)` for a Euclidean one, where
`d` is the distance. So a cosine of 0.6 scores 0.8, and `2 * score - 1` gives the cosine back.

> **Changed on FalkorDB and Memgraph.** Before this release FalkorDB gave the cosine itself (-1 to 1)
> and `1 / (1 + d)`, and Memgraph the absolute value of the cosine, so opposite vectors scored as
> high as equal ones; Memgraph's Euclidean score was already `1 / (1 + d²)`. Both now give the scores
> above, as Neo4j, Postgres, Oracle and SQLite do. A cosine score rises from `cos` to `(1 + cos) / 2`,
> and a FalkorDB Euclidean score falls from `1 / (1 + d)` to `1 / (1 + d²)` where `d` is over 1 (and
> rises where it is under), so a `threshold` chosen against the old scores keeps more or fewer results
> than before: convert a cosine threshold `t` to `(1 + t) / 2`.

**`topK` is the index's `k`, not a guaranteed result count.** When searching a **view**, its
*required* relationships (and the optional `threshold`) are applied **after** the K-nearest search,
so a candidate that ranks in the top K but fails the filter is dropped — meaning **`loadNearest` can
return fewer than `topK` results**. The same holds for a root with more than one label: the index is
on its first label, so a node with that label alone is a candidate, and is dropped. So is a node of a
`partitionLabel`'s index that lacks any of the root's labels. A **fragment** with one label, searched
without a partition, has no such filter, so it returns the full top K (minus any `threshold` cut).

**`k` is also the search beam width — this is the surprising part.** On Lucene-backed engines the
result queue *is* the HNSW candidate queue, so `k` does not merely truncate a ranked list; it decides
how much of the graph the search explores. A small `k` can miss a vector that is genuinely nearest.
This is measurable, not theoretical: on a 9K-vector index, a vector verified as true global rank 3 was
not returned at any `k ≤ 100`, and came back at rank 3 at `k = 200`. Neo4j exposes no separate
`ef_search`, so raising `k` is the only query-time lever on recall.

Use **`searchK`** to widen the search without widening the result:

```kotlin
// search with a beam of 200, return the best 40 that survive the filter
graphObjectManager.loadNearest<PropositionView>(dsl, queryEmbedding, topK = 40, searchK = 200) {
    where { proposition.contextId eq ctx }
}
```

`searchK` is what the index is asked for; `topK` becomes a `LIMIT` applied **after** the filter, so
over-fetching actually recovers rows the filter would otherwise have thinned away. Omit it and the
emitted query is unchanged. `searchK < topK` throws — it can only lose results.

On Neo4j, over-fetching also re-ranks the beam by **exact** similarity before trimming: where the index
quantizes, its own score is computed against quantized vectors and does not order identically to exact
similarity, so trimming by it discards true matches the beam already found. Whether an index quantizes
depends on the engine build unless you pin it.

**Filtered searches dilute.** Because predicates apply after the index yields, a scoped caller gets
roughly `k × selectivity` rows — at 22% selectivity, asking for 40 returns about 9 — and those
survivors are the globally-nearest that happen to be in scope, not the nearest within scope. `searchK`
mitigates this. See [0.0.79-vector-search-k.md](../docs/0.0.79-vector-search-k.md).

**Searching a partition (`partitionLabel`).** When the same property is indexed per partition — one
vector index per corpus, tenant, or other scope — name the partition and the search runs *inside* it,
pre-filtered by construction, so `k` is no longer diluted by the filter:

```kotlin
persistenceManager.indexes.ensure(VectorIndexSpec("Corpus_abc", "embedding", 1536))

graphObjectManager.loadNearest(
    PropositionNode::class.java, null, queryEmbedding,
    topK = 40, threshold = null, searchK = null, partitionLabel = "Corpus_abc",
)
```

The label changes only which index is located: `where { }` and the projection are unaffected. A node
the partition's index yields comes back only when it has every one of the fragment's labels, here
`:Proposition`, so a node of the partition that is no `Proposition` is dropped. The index name re-derives as
`${label}_${property}_vector`, matching what `VectorIndexSpec` would create — so a fragment whose
`@VectorIndex` pins an explicit name cannot be partitioned, and says so.
See [0.0.79-vector-partitioning.md](../docs/0.0.79-vector-partitioning.md).

Backends without a native vector index (Amazon Neptune) throw `UnsupportedOperationException`.

**Pinning the physical index.** Only `dimensions` and `similarity_function` are always emitted;
everything else about the physical index is the engine's choice, and engine defaults change between
versions — so the same declaration can produce a different index on different servers. Pin the HNSW
graph shape portably on the annotation, and anything engine-specific on a `VectorIndexSpec`:

```kotlin
@VectorIndex(similarity = SimilarityFunction.COSINE, hnswM = 32, hnswEfConstruction = 200)
val embedding: List<Float>? = null

// engine-specific options — Neo4j quantization, FalkorDB efRuntime — in a catalog spec
SchemaCatalog.of(
    VectorIndexSpec("Proposition", "embedding", 1536,
        engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false))),
)
```

An unpinned parameter stays the engine's to choose and is never reported as drift; a pinned one the
engine contradicts is. The effective configuration is logged after every index creation either way.
See [0.0.79-vector-index-tuning.md](../docs/0.0.79-vector-index-tuning.md).

**Filtering with `where { }`.** A `where { }` block AND-s caller predicates into the same
post-search filter, so you can combine vector similarity with property predicates **and**
relationship quantifiers in one statement:

```kotlin
// nearest propositions in this context that mention entity X (reified form generated per @VectorIndex view)
graphObjectManager.loadNearest<PropositionView>(queryVector, topK = 20) {
    where {
        proposition.contextId eq ctx
        proposition.status eq "active"
        mentions.any { resolvedId eq entityId }   // any{} / none{} over a projected relationship
    }
}
```

The codegen emits this `loadNearest<T>(vector, topK, threshold) { where { } }` extension for each
`@VectorIndex`-bearing **view** (its root fragment). The filtered form works on a bare **fragment** too,
given the fragment's generated query object — filter its own properties directly:

```kotlin
// filtered vector search over a bare @NodeFragment
graphObjectManager.loadNearest<ChunkNode, _>(ChunkNodeQueryDsl.INSTANCE, queryVector, topK = 20) {
    where { query.containerSectionId eq "sec-1" }
}
```

The predicate is applied **after** the K-nearest search, so it really does prune — a node that ranks
in the top K but fails the predicate is dropped, and `topK` remains the index's `k` (the result may
contain fewer rows). Property predicates filter the projected root; relationship quantifiers
(`any{}`/`none{}`) filter the projected relationship collection (`any(m IN mentions WHERE …)`).
Multiple `any{}` AND together — "mentions *all* of these entities" is one `any{}` per id. Referencing
a relationship the view does not project is an error.

#### Nearest Neighbours Within a Set (`loadNearestAmong`)

`loadNearestAmong` answers, for each of a set of ids, "which of the *other ids in this set* are
nearest to it?" — the batched form of calling `loadNearest` once per id with that node's own
embedding and keeping only the hits inside the set. It runs as one statement that each store answers
natively, so it needs no store-specific Cypher:

```kotlin
val neighbours: Map<String, List<Scored<String>>> =
    graphObjectManager.loadNearestAmong<PropositionNode>(batchIds, topK = 5, threshold = 0.8)

neighbours["p-1"]  // [Scored("p-7", 0.97), Scored("p-3", 0.91)]
```

- **Keys** are the ids whose root node has the embedding, in the order the ids were given. An id with
  no embedding, or no node, is not a key; an id whose neighbours were all dropped maps to an empty list.
  An embedding of another size than the index's is measured against none of a different size: on
  Postgres or Oracle with a vector index made it is not a key, and on Neo4j and FalkorDB the search
  fails, as their index search refuses a vector of another size.
- **Values** are up to `topK` *other* ids from the set — never the id itself — most similar first, each
  with the same normalized score `loadNearest` gives that pair on the same store, so the two agree.
  Those below `threshold` are dropped.
- It works on a `@NodeFragment` and on a `@GraphView` (searching the root fragment's embedding), and
  resolves the embedding from `@VectorIndex` as `loadNearest` does; pass `property` when there are
  several. A view's relationships play no part: the set is named by id.

**`searchK` matters here as it does for a filtered `loadNearest`.** Where the store searches through
an approximate index — Neo4j, FalkorDB, Memgraph, and Postgres or Oracle once a vector index has been
made — the index is asked for `searchK` candidates per id (default `topK + 1`: the id itself is found
first), and the "among the set", self, root label and `threshold` filters apply to those. An id whose nearest
mostly lie outside the set can get fewer than `topK` back, so a selective set — a few ids among many
indexed nodes — needs a larger `searchK`. Where the store measures every node instead (SQLite, and
Postgres or Oracle with no vector index made) only the set is measured, the answer is exact, and
`searchK` changes nothing; every pair of the set is measured, so the cost grows with the square of
the number of ids.

| Store | How it is answered |
|---|---|
| Neo4j | `UNWIND` the ids, `db.index.vector.queryNodes` from each seed's embedding, rescored exactly with `vector.similarity.*` |
| FalkorDB | `UNWIND` the ids, `db.idx.vector.queryNodes` from each seed's embedding |
| Memgraph | `UNWIND` the ids, `vector_search.search` from each seed's embedding |
| Postgres | one SQL statement: each seed's nearest through the pgvector index, or, with none made, measured against the other seeds |
| Oracle | one SQL statement: each seed's nearest through the vector index (`CROSS APPLY … FETCH APPROX`), or, with none made, every pair of seeds measured and ranked |
| SQLite | one SQL statement: every pair of seeds measured and ranked (exact) |
| Neptune | refused with `UnsupportedOperationException`, as `loadNearest` is |

#### Full-Text Search (`loadMatching`)

`loadMatching` is the full-text mirror of `loadNearest`: it finds the `topK` nodes most relevant to a
text query and returns them as scored, typed results — normalized to a consistent `[0, 1]` similarity
across engines, with no consumer Cypher and no per-engine score wrangling.

```kotlin
// bare @NodeFragment
val hits: List<Scored<ChunkNode>> = graphObjectManager.loadMatching<ChunkNode>("graph databases", topK = 20)

// with a floor on the normalized relevance
graphObjectManager.loadMatching<ChunkNode>("graph databases", topK = 20, threshold = 0.5)

// a @GraphView searches its root fragment's text index and returns the projected view
graphObjectManager.loadMatching<ChunkView>("graph databases", topK = 20)
```

The index is resolved from a **`@FullTextIndex`** on the searched fragment (property-level for a single
field, or class-level `@FullTextIndex(properties = ["title", "body"])` for a multi-property index) — the
same annotation the schema feature uses to *create* it. `threshold` defaults to `0.0` (keep everything).
`topK` bounds the index call as well as the rows returned: the scores are normalized over the hits
the index yields that have every one of the root's labels, so the index is asked for its best `topK`
hits, and an ordinary query that matches most of a corpus costs no more than those. A node with the
indexed label alone is no root, so it is dropped before the scores are scaled, and the best hit a
search can return scores `1.0` on every store. Each result is a `Scored<T>(value, score)`, most relevant
first, with polymorphic dispatch (a `loadMatching<SealedBase>` returns each hit as its concrete subtype).

The query string is passed through to the engine's full-text language (Lucene syntax on Neo4j:
`AND`/`OR`/`"phrase"`/`field:term`); raw user input is not escaped for you. Backends without a native
full-text index throw `UnsupportedOperationException`.

**Filtering with `where { }`** works exactly like `loadNearest` — full-text relevance plus property
predicates in one query, on a view or a bare fragment:

```kotlin
graphObjectManager.loadMatching<ChunkNode>("graph databases", topK = 20) {
    where { query.containerSectionId eq "sec-1" }
}
```

The predicate, like a view's required relationships and a root's labels beyond the first (the one
the index is on), is applied **after** the index yields; the labels are checked before the scores
are scaled, the predicate and relationships after. Where any of them filters, the index is asked for `topK × FullTextSearchLimits.FILTERED_OVER_FETCH` (10) hits
and the filter keeps the best `topK` of those, so a filter that passes fewer than one in ten of the
best hits can return fewer than `topK` rows while the index holds more that pass it. The DEBUG line
`GraphObjectManager` logs for a short result says how many hits the index was asked for.

> Engine note: full-text search runs on Neo4j (`db.index.fulltext`), FalkorDB (`db.idx.fulltext`, by
> label), and Memgraph (`text_search`, GA — no experimental flag needed).

### Type-Safe Query DSL

The code generator creates a type-safe DSL for each `@GraphView`, giving you IntelliJ autocomplete and compile-time type checking.

#### Basic Filtering

```kotlin
// Load people whose bio contains "Lead"
val leads = graphObjectManager.loadAll<PersonCareer> {
    where {
        person.bio contains "Lead"  // Direct property access!
    }
}
```

#### Multiple Conditions (AND)

```kotlin
val results = graphObjectManager.loadAll<PersonCareer> {
    where {
        person.name eq "Alice Engineer"
        person.bio.isNotNull()
    }
}
// Generates: WHERE person.name = $p0 AND person.bio IS NOT NULL
```

#### OR Conditions

```kotlin
val results = graphObjectManager.loadAll<PersonCareer> {
    where {
        anyOf {
            person.name eq "Alice"
            person.name eq "Bob"
        }
    }
}
// Generates: WHERE (person.name = $p0 OR person.name = $p1)
```

#### Querying Bare Fragments

The DSL isn't only for `@GraphView`s — the code generator also emits a `<Fragment>QueryDsl` for every
bare `@NodeFragment`, so you can `loadAll` / `count` / `deleteAll` a node type directly with a typed
`where` over its own properties. Import the `query` receiver:

```kotlin
import org.drivine.query.dsl.query

// reified form — filters ChunkNode's own properties (query.<kotlinFieldName>, not the on-disk name)
val chunks = graphObjectManager.loadAll<ChunkNode> {
    where { query.containerSectionId eq "sec-1" }
}
graphObjectManager.count<ChunkNode> { where { query.containerSectionId eq "sec-1" } }

// a sealed fragment dispatches each row to its concrete subtype; narrow with instanceOf()
graphObjectManager.loadAll<ContentElementNode> { where { query.instanceOf<ChunkNode>() } }

// to filter a subtype by its OWN property, pass that subtype's DSL
graphObjectManager.loadAll<ChunkNode, _>(ChunkNodeQueryDsl.INSTANCE) {
    where { query.containerSectionId eq "sec-1" }
}
```

Use `query.<field>` (the Kotlin field name); `@GraphProperty` on-disk names are mapped for you. The same
`<Fragment>QueryDsl.INSTANCE` powers the filtered `loadNearest` / `loadMatching` forms above.

A fragment's DSL has a reference for each property the node stores, as Drivine reads them: the ones it
inherits too, a Java superclass's private fields among them, but not a Kotlin superclass's private
properties. A static or `@GraphTransient` field has none. An inherited `@VectorIndex` or `@FullTextIndex`
makes the fragment searchable just as its own would.

#### Quantified Predicates over a To-Many Relationship (`any` / `none`)

Filter a root by a predicate over the elements of a `List<>`-typed `@GraphRelationship`. The block
is scoped to the **target fragment's** properties, and several conditions inside one block correlate
to a *single* related element.

```kotlin
// propositions that have at least one mention resolving to this entity
graphObjectManager.loadAll<PropositionView> {
    where { mentions.any { resolvedId eq entityId } }
}

// ... or to any of several
graphObjectManager.loadAll<PropositionView> {
    where { mentions.any { resolvedId inList entityIds } }
}

// propositions with NO mention resolving to this entity (also matches propositions with no mentions)
graphObjectManager.loadAll<PropositionView> {
    where { mentions.none { resolvedId eq entityId } }
}

// correlated: a single mention that is BOTH the subject AND resolves to this entity
graphObjectManager.loadAll<PropositionView> {
    where { mentions.any { role eq "SUBJECT"; resolvedId eq entityId } }
}
```

Rendered as an existence subquery, per engine — `EXISTS { (root)-[:HAS_MENTION]->(m:Mention) WHERE … }` on
Neo4j, `size([(root)-[:HAS_MENTION]->(m:Mention) WHERE … | 1]) > 0` on Memgraph, a `CALL`-subquery count on
FalkorDB — with `none{}` wrapping it in `NOT (…)`. The related node must carry the field's target labels,
as the load matches them, on every store: a condition tests only the nodes the field would load, so a
node of another label at the end of the same relationship neither matches `any { }` nor fails `none { }`.
The same holds of a relationship of a nested view, and of a single-valued relationship. A list of fragments
read over several hops (`maxDepth` above 1) is tested to the same depth the load reads it, a `depth(…)` given
for the query included: `following.any { text eq "c" }` on a field of `maxDepth = 2` walks
`-[:NEXT*1..2]->`, and finds a node two hops away, as the load would give it. `any{}` is the explicit form of the flat
`mentions.resolvedId eq id` shorthand; prefer it for `none`, for correlated multi-condition blocks,
or for clarity. (Backends without subquery support throw, per the grammar default.)

Inside a scored search (`loadNearest { where { } }`, `loadMatching { where { } }`) the filter is applied to
the projected view, and `any` / `none` reach what the projection holds: a collection of fragments, a
collection of nested views (the nested view's root, and its own relationships), and a single-valued
relationship, which is read as a list of one or none.

`not { }` around a condition on a relationship's target is one existence check for each condition, as
`or` is: two conditions under it need not hold of the same related node.

#### List-Valued Properties (`hasItem`)

Filter on a caller value being contained in a **list-valued node property** — the mirror of `inList`
(`inList` is *property in caller-list*; `hasItem` is *caller-value in list-property*):

```kotlin
// propositions whose `grounding: List<String>` contains this chunk id
graphObjectManager.loadAll<PropositionView> {
    where { proposition.grounding hasItem "chunk-1" }
}
// -> WHERE 'chunk-1' IN proposition.grounding
```

Renders as portable openCypher (`$value IN node.listProp`) on Neo4j, FalkorDB, and Memgraph, AND-s
with any other predicate, and works inside `loadNearest { where { } }`. (Named `hasItem` rather than
`contains` because Kotlin reserves `operator fun contains` for the `in` operator and requires it to
return `Boolean`, while the DSL operators register by side-effect.)

#### Ordering

```kotlin
val results = graphObjectManager.loadAll<PersonCareer> {
    where {
        person.bio.isNotNull()
    }
    orderBy {
        person.name.asc()
    }
}
```

**Where the nulls go.** Left to itself the database decides where the rows with no value for the
ordered property go, and Cypher ranks null above every value, so a descending order starts with
them. `nullsLast()` and `nullsFirst()` say otherwise, whichever way the values run:

```kotlin
val best = graphObjectManager.loadAll<PropositionView> {
    orderBy { proposition.effectiveConfidence.desc().nullsLast() }
    limit(20)
}
```

That is the 20 most confident, with the unranked rows only for what the limit leaves, in one
statement. Reading the ranked rows and then the unranked ones is not the same thing: under read
committed each read sees its own committed state, and a row that gains a value between the two is
in neither.

On a Cypher backend the order is `ORDER BY p.x IS NULL, p.x DESC` (false sorts before true); on
Oracle, `DESC NULLS LAST`. No index supplies that order: Neo4j sorts by the extra term whatever is
indexed (a `Top` over the scan, where an order an index mirrors is read from the index with no
sort), so leave the placement off a property that is never null, and expect no
[index advice](#limit--pagination) for an order that has one.

It is for the order of the roots: an order of a relationship collection that places its nulls is
refused, and so is one under `seek`, whose cursor does not say which side of the nulls it is on.
From inside an `orderBy` block it follows `asc()` / `desc()`; an order built apart from one, with
`ascending()` / `descending()`, takes the same two calls.

**Ordering by an expression.** A ranking computed from several properties is an expression over
the root's properties, built from arithmetic, `exp`, `floor`, `coalesce`, `epochSeconds` and `case`,
with every value the caller gives bound as a parameter. The ranking runs in the database with its
`LIMIT`, and the terms after it break its ties:

```kotlin
val top = graphObjectManager.loadAll<PropositionView> {
    orderBy {
        case(
            (proposition.validFrom above asOf) or (proposition.validTo atMost asOf) then 0.0,
            otherwise = proposition.confidence *
                exp(-proposition.decay * floor((epochSeconds(asOf) - proposition.lastAccessedSeconds) / 86400)),
        ).desc()
        proposition.id.asc()
    }
    limit(20)
}
```

Neo4j, FalkorDB, Memgraph and Oracle each write the arithmetic, the functions and `case`, which live in
`org.drivine.query.dsl`. A function that not every backend can write lives in a package named for its
capability, behind a Kotlin opt-in: `epochSeconds(property)`, which reads a stored moment as a number, is
`org.drivine.query.dsl.temporal.epochSeconds` and needs `@OptIn(TemporalOrder::class)` (from Java, the
statics of `TemporalOrderExpressions`). FalkorDB cannot write it, and refuses the load with an
`UnsupportedOrderFunctionException` before anything runs. An expression reads only the root's
properties, and is refused under `seek`. See [docs/0.1.7-order-by-expression.md](../docs/0.1.7-order-by-expression.md).

#### Limit & Pagination

`limit(n)` and `skip(n)` push a row bound into the generated Cypher (bound as `$_limit` / `$_skip`,
after `ORDER BY`), so "top 20 by recency" is done database-side instead of over-fetching:

```kotlin
// top 20
graphObjectManager.loadAll<PropositionView> {
    orderBy { proposition.created.desc() }
    limit(20)
}

// page 3 (20 per page)
graphObjectManager.loadAll<PropositionView> {
    orderBy { proposition.created.desc() }
    skip(40)
    limit(20)
}
```

For large or changing result sets, prefer keyset pagination with `seek`. Its properties must
match the root `orderBy` properties in the same order; Drivine derives each comparison from the
sort direction and builds the lexicographic continuation predicate. End with a unique key so ties
cannot be skipped or duplicated:

```kotlin
graphObjectManager.loadAll<SessionView> {
    orderBy {
        session.lastActivityAt.desc()
        session.sessionId.desc()
    }
    seek {
        session.lastActivityAt after cursor.lastActivityAt
        session.sessionId after cursor.sessionId
    }
    limit(pageSize)
}
```

Each cursor value is the corresponding property of the last row of the previous page. To tell the
caller whether a next page exists without a second query, ask for `limit(pageSize + 1)`, return the
first `pageSize` results, and treat the presence of the extra row as `hasMore`.

**The ordered properties must be non-null in the data.** A row whose sort key is null satisfies no
comparison, so it is dropped from every page after the first — and engines disagree about where
nulls sort, so the shape of that loss is not even portable. Use non-null properties as keys, or
filter nulls out in `where`. An order that places its nulls (`nullsLast()`) is refused with `seek`.

**Index the cursor.** Keyset pagination is only cheap if the engine can seek into an index and stop;
otherwise it scans the whole continuation and takes the top *n*, which is no better than `skip`. The
rule is that the index mirrors the cursor — a range index over exactly the `orderBy` properties, in
the same order:

```kotlin
@NodeFragment(labels = ["Session"])
@RangeIndex(properties = ["lastActivityAt", "sessionId"])   // matches the cursor below
data class SessionNode(
    @NodeId val sessionId: String,
    val lastActivityAt: Instant,
)
```
```kotlin
orderBy {
    session.lastActivityAt.desc()
    session.sessionId.desc()
}
seek {
    session.lastActivityAt after cursor.lastActivityAt
    session.sessionId after cursor.sessionId
}
```

A single-property cursor takes a single-property index (`@RangeIndex` on the field) — same rule, one
key. Profiled on Neo4j 25, 200k nodes, a 20-row page from the middle of the relation:

| Index | Database accesses |
| --- | --- |
| Composite over both cursor keys | **27** |
| Single-property on the leading key only | 200,020 |
| None | 500,010 |

With the matching index the plan is `NodeIndexSeek` → `Limit` — no sort at all, since the index
supplies the order, and it stops as soon as the page is full.

Why an index that covers only *part* of the cursor is so much worse: Drivine constrains every cursor
key with `IS NOT NULL`, because a Neo4j index excludes nodes that lack the property, so the planner
will not use a composite index unless the query provably excludes those same nodes. That conjunct is
what makes the composite index usable; against an index that doesn't contain the property, it is
just an extra property read per row. Hence: mirror the cursor, and neither half of that applies.

**This applies to Neo4j.** Memgraph and FalkorDB cannot satisfy an `ORDER BY` from an index — both
place a blocking sort between the scan and the limit, measured even in the simplest case of one
indexed property and no predicate. So on those engines keyset pagination costs O(remaining) rather
than O(page), no index will change that, and Drivine neither emits the `IS NOT NULL` conjuncts (which
cost Memgraph its range bound) nor gives index advice there. `seek` is still worth using on them for
its *stability* — pages that don't shift under concurrent writes — just not for its cost.

**Drivine tells you when the index is missing.** Any root-level `orderBy` — with or without `seek` —
is checked against the database's indexes, and reports when nothing mirrors it:

```
seek on Session(lastActivityAt, sessionId) has no matching range index, so the query scans and
sorts instead of seeking. Declare @RangeIndex(properties = ["lastActivityAt", "sessionId"]) on the
fragment, or call indexes.ensure(RangeIndexSpec("Session", listOf("lastActivityAt", "sessionId"))).
The index must cover exactly these properties, in this order.
```

Ordering without an index is *correct*, just unindexed — perfectly reasonable on a small collection
— so the default only warns, once per label/property combination. Turn it up in development or CI so
an unindexed page fails the build instead of quietly scanning in production:

```yaml
drivine:
  query:
    index-advice: FAIL      # WARN (default) | OFF
```

The property applies to every manager the factory creates. Without Spring, or to override a single
manager after it has been handed out:

```kotlin
graphObjectManager.indexAdvice = IndexAdvicePolicy.FAIL
```

An order that [places its nulls](#ordering) is never advised on either: it sorts whatever is indexed.

A query already pinned to a single root — a top-level `where` equality on the `@NodeId`, or on a
property carrying a single-property uniqueness constraint — is never advised on. Its ordering is over
one row, so no index could change the plan. An equality inside `anyOf` doesn't count, since it
constrains only one branch of the OR.

The index list is read once and cached per manager, so the check costs one round trip per process,
not one per query. If your application ensures indexes lazily, after the first ordered query has
already run, that cache will be stale — construct the manager after schema setup, or use `OFF`.

See [docs/0.0.75-index-aware-keyset-pagination.md](../docs/0.0.75-index-aware-keyset-pagination.md) for
the planner reasoning behind the mirror rule, the full measurements, and the cross-engine caveats.

`seek` rejects missing/misaligned order keys, null cursor values, use together with `skip`, and
use on any operation that would ignore it (`count`, `deleteAll`, `loadNearest`, `loadMatching`) —
those fail loudly rather than quietly returning an unpaginated result. Drivine intentionally owns
query planning but not cursor serialization; applications can expose an opaque cursor format
appropriate to their API. Java callers use `.seek(q -> List.of(...))` and the Java-friendly
`PropertyReference.after(value)` method.

For a `@GraphView`, `limit(n)` bounds **root entities** — each returned view keeps its relationships
fully populated (relationships are pattern comprehensions, so one root is one row). Pair `limit` with
`orderBy` for a deterministic top-N; without ordering the subset is an arbitrary `≤ n`. `count(…)`
ignores `limit`/`skip`. (`loadNearest` doesn't take a limit — `topK` is already its bound.) From Java,
`.limit(n)` / `.skip(n)` are on the query builder alongside `.where()` / `.orderBy()`.

#### Ordering Nested Collections (Database-Side)

The DSL supports sorting nested relationship collections directly in the database. The Cypher emitted depends on the engine's dialect:

| Engine | Strategy | A relationship of the root | A collection inside a nested view |
|--------|----------|----------------------------|-----------------------------------|
| Neo4j (default) | `apoc.coll.sortMaps()` | Sorted | Sorted |
| Neo4j (CALL) | `CALL { ORDER BY + collect }` | Sorted | Refused |
| FalkorDB | `CALL { ORDER BY + collect }` | Sorted | Sorted; refused in a nested view that holds views of its own, which is projected inline |
| Neptune | `CALL { ORDER BY + collect }` | Sorted | Refused |
| Memgraph | `CALL { ORDER BY + collect }` | Sorted | Refused |

Where a nested collection's sort is refused, the query throws `UnsupportedOperationException` and names the alternatives: APOC on Neo4j, or client-side [`@SortedBy`](#client-side-sorting-with-sortedby).

**Direct Relationship Sorting:**

```kotlin
// Sort assignees by name within each issue
val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
    where {
        issue.state eq "open"
    }
    orderBy {
        issue.id.desc()           // Root ordering (uses index)
        assignedTo.name.asc()     // Collection sorting
    }
}
```

**Nested Relationship Sorting:**

```kotlin
// Sort nested worksFor organizations within raisedBy
val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
    orderBy {
        raisedBy.worksFor.name.desc()  // Sort organizations by name descending
    }
}
```

**How it works:**
- Root-level ordering (e.g., `issue.id.desc()`) uses Cypher's `ORDER BY`, which can utilize indexes
- A sort names its collection by the whole path to it, at any depth: `raisedBy.worksFor` is `raisedBy_worksFor`, and a relationship whose name holds an underscore is read whole. Beneath a recursive view, a path names a collection from the level as from the root, so `messages.attachments` orders the root's and every level's alike
- Collection sorting strategy is selected automatically by the Cypher dialect
- On Neo4j, the default uses APOC Extended's `apoc.coll.sortMaps()` — requires APOC Extended matching your Neo4j version
- On FalkorDB, Neptune, and Memgraph, CALL subquery prologs are used — no plugins required
- To use CALL subqueries on Neo4j instead of APOC, set the Cypher dialect in your datasource config:

```yaml
database:
  datasources:
    graph:
      type: NEO4J
      cypher-dialect: FALKORDB   # Uses CALL subquery sort, no APOC needed
```

The dialect controls all engine-specific Cypher generation — existence checks, collection sorting, and nested view projections. Available dialects: `NEO4J_5` (default for Neo4j), `NEO4J_4`, `FALKORDB`, `NEPTUNE`, `MEMGRAPH`.

What a view load does not sort or order:

- A recursive collection (a view nested in itself) cannot be sorted in the query: the load throws `UnsupportedOperationException`. Use `@SortedBy`, which orders every level.
- A view is ordered, and paged by keyset, on what it projects. Ordering a view on a root property that is not a declared field of the root fragment, such as `query.issue.property("createdAt")` where `Issue` has no `createdAt` field, is refused with `IllegalArgumentException`: the projected root does not hold it, and the order would be by null. Declare the field. The same holds for a filter on such a property in a scored search (`loadNearest`, `loadMatching`). A `where { }` of a plain load reads the node and needs no field.
- A load filters first: its `WHERE` stands directly after the `MATCH`, so sorted collections, paths and counts are computed only for the roots the load keeps.

#### Client-Side Sorting with @SortedBy

For declarative client-side sorting without APOC, use the `@SortedBy` annotation on relationship fields:

```kotlin
@GraphView
data class ProjectWithContributors(
    @Root val project: Project,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("name")  // Sort by contributor name ascending
    val contributors: List<Contributor>
)

// Descending order
@GraphView
data class ProjectWithContributorsSortedDesc(
    @Root val project: Project,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("name", ascending = false)  // Sort descending
    val contributors: List<Contributor>
)

// Nested property paths (for nested GraphView relationships)
@GraphView
data class ProjectWithNestedSort(
    @Root val project: Project,
    @GraphRelationship(type = "HAS_CONTRIBUTOR", direction = Direction.OUTGOING)
    @SortedBy("contributor.name")  // Sort by nested property
    val contributors: List<ContributorWithTasks>
)
```

The `@SortedBy` annotation:
- Sorts the collection automatically after deserialization
- Supports dot notation for nested property paths (e.g., `"person.name"`)
- Works with any `Comparable` property type
- Handles nulls gracefully (sorted to end)

#### Available Operators

**Comparison:**
- `eq` - equals (=)
- `neq` - not equals (<>)
- `gt` - greater than (>)
- `gte` - greater than or equal (>=)
- `lt` - less than (<)
- `lte` - less than or equal (<=)
- `in` / `inList` - IN operator: a property value is in a caller list (`property IN $list`)
- `notIn` - NOT IN (`NOT property IN $list`)
- `hasItem` - list membership: a caller value is in a **list-valued** property (`$value IN node.listProp`) — the mirror of `inList`

**String Operations:**
- `contains` - CONTAINS
- `startsWith` - STARTS WITH
- `endsWith` - ENDS WITH
- `matches` - regex match (`=~`) — *not supported on FalkorDB*
- `containsIgnoreCase` / `eqIgnoreCase` - case-insensitive contains / equals (`toLower(...)`), read from an index on a [`@CaseInsensitive`](09-supported-engines.md#caseinsensitive-lookups-that-ignore-case) field

**Null Checking:**
- `isNull()` - IS NULL
- `isNotNull()` - IS NOT NULL

**Boolean / Label:**
- `anyOf { }` - OR of the enclosed conditions
- `not { }` - negation of the enclosed sub-expression (`NOT ( … )`)
- `instanceOf<T>()` - node carries **all** of a `@NodeFragment` type's labels
- `hasAnyLabel("A", "B")` - node carries **any** of the given labels (`ANY(l IN labels(n) WHERE l IN $p)`)

**Ordering:**
- `asc()` - ascending order
- `desc()` - descending order

#### Dynamic / Runtime-Key Predicates

When the property to filter isn't known at compile time (an arbitrary `@PropertyBag` key, or a
caller/tool-supplied filter key), reach for the untyped escape hatch instead of a generated accessor.
Values still bind as parameters (no injection). A key that is not a plain identifier, such as a dotted
`@PropertyBag` path or one with a hyphen or a space, is backtick-quoted for you, and cannot end its
quotes: a backtick in it is doubled, and so is the escape `\u0060`, which Neo4j reads as one. An empty
key is refused. FalkorDB refuses a key that holds a backtick.

```kotlin
import org.drivine.query.dsl.property     // stored-path form
import org.drivine.query.dsl.field        // resolving form
import org.drivine.query.dsl.predicate
import org.drivine.query.dsl.predicateOn

where {
    query.property("metadata.source") eq "wiki"          // stored path → n.`metadata.source` = $p
    query.field("source") eq "wiki"                       // resolves "source" via @GraphProperty/@PropertyBag
    query.predicate("metadata.tags", ComparisonOperator.HAS_ELEMENT, "kotlin")   // $p IN n.`metadata.tags`
    query.predicateOn("sectionId", ComparisonOperator.EQUALS, "s1")              // resolving, programmatic
}
```

- `property(path)` / `predicate(path, op, value)` — take the **stored** property name.
- `field(key)` / `predicateOn(key, op, value)` — take a **logical** key and resolve it to the stored
  path from the fragment's own `@GraphProperty` on-disk names and single `@PropertyBag` prefix (so a
  consumer needn't know the on-disk name or bag prefix). Throws if the key is unresolvable.
- `predicate` / `predicateOn` accept any `ComparisonOperator` (including `HAS_ELEMENT`, the dynamic twin
  of `hasItem`), so a runtime filter tree maps one leaf → one call.

#### Grouped Reads (`groups`)

`graphObjectManager.groups` groups the nodes of a fragment by a key and gives numbers about each group: a count, a sum, a ranking. It is one statement on every engine, Oracle among them, and the groups, their numbers and their order are held to one contract on each. Everything else on the manager returns the objects it maps; this returns summaries of them, so nothing is loaded to be tallied in the application.

Both managers have it. On the deprecated `GraphObjectManager` a node key is an object it loaded, and is tracked as one.

Each line of a grouped read is a clause of the query you would have written by hand, and a `val` is its `AS`:

| Here | Cypher | SQL |
| --- | --- | --- |
| `groups.load<T, _>` | `MATCH (n:T)` | `FROM t` |
| `where { … }` | `WHERE …` before the `WITH` | `WHERE …` |
| `val page = key(query.page)` | `WITH n.page AS page, …` | `GROUP BY page` |
| `val user = key(path<U>(…))` | `MATCH (n)-[…]->(user:U)`, then `WITH user, …` | a join, then `GROUP BY` its id |
| `val votes = count()` | `count(n) AS votes` | `COUNT(*) AS votes` |
| `val likes = count { … }` | `sum(CASE WHEN … THEN 1 ELSE 0 END) AS likes` | the same |
| `having { … }` | `WHERE …` after the `WITH` | `HAVING …` |
| `orderBy`, `limit` | `ORDER BY`, `LIMIT` | `ORDER BY`, `FETCH FIRST` |
| `rows { … }` | `RETURN { … }` | the select list |

The steps below use one fragment, a piece of feedback on a docs page, and five nodes of it:

```kotlin
@NodeFragment(labels = ["Feedback"])
data class FeedbackData(@NodeId val id: String, val page: String, val helpful: Boolean, val createdAt: Instant)
```

```
(f1:Feedback {page: "/install", helpful: true,  createdAt: January})
(f2:Feedback {page: "/install", helpful: true,  createdAt: March})
(f3:Feedback {page: "/install", helpful: false, createdAt: March})
(f4:Feedback {page: "/agents",  helpful: true,  createdAt: March})
(f5:Feedback {page: "/agents",  helpful: false, createdAt: March})
```

**1. Group and count.** "For each page, how many?"

```kotlin
val votes: Map<String, Long> = graphObjectManager.groups.countBy(FeedbackData::page)
// {"/agents" = 2, "/install" = 3}
```

The property says what is counted (`FeedbackData`) and what puts two of them in one group (`page`). This needs no block and no code generator. Where the field may be null, the nodes with no value are counted under `null`.

**2. More than one number.** "For each page, how many, and how many of those were helpful?" Two numbers do not fit a map, so the read is spelled out. Each line makes one column and gives a handle to it, and `rows` builds one result from the handles:

```kotlin
val ratings: List<PageVotes> = graphObjectManager.groups.load<FeedbackData, _> {
    val page  = key(query.page)                     // n.page AS page
    val votes = count()                             // count(n) AS votes
    val likes = count { query.helpful eq true }     // counts a node only when this holds
    rows { PageVotes(it[page], it[votes], it[likes]) }
}
// PageVotes("/agents", 2, 1), PageVotes("/install", 3, 2)
```

- The first type argument is **the source**: the fragment whose nodes are grouped. The second is what `rows` returns, and `_` leaves it to the compiler.
- **`key` says what puts two nodes in the same group. Every other line is a number about the group.**
- **A handle is typed.** `it[page]` is a `String` and `it[votes]` a `Long`. A number that was never declared has no handle, so ordering by it or reading it does not compile.
- `PageVotes` is any class of your own; Drivine knows nothing of it. `rows { it[page] to it[votes] }` gives a `List<Pair<String, Long>>`.

**3. The three filters.** A grouped read has three places a condition can go, and each answers a different question. A condition in the wrong place gives a wrong number and no error.

```kotlin
graphObjectManager.groups.load<FeedbackData, _> {
    where { query.createdAt gte march }             // 1. which nodes are read at all
    val page  = key(query.page)
    val votes = count()
    val likes = count { query.helpful eq true }     // 2. which nodes count toward this one number
    having { votes gte 3 }                          // 3. which groups are returned
    orderBy { votes.desc() }
    limit(10)
    rows { PageVotes(it[page], it[votes], it[likes]) }
}
```

| Filter | Removes | In the graph above |
| --- | --- | --- |
| `where { … }` | nodes, before anything is grouped | `f1` is gone, so `/install` has 2 votes |
| `count { … }` | nothing. It decides which nodes that one number counts | `likes` of `/install` is 1, and `votes` is still 2 |
| `having { … }` | whole groups, after they are counted | no page has 3 votes, so the answer is empty |

The mistake to look for is `helpful eq true` moved up into `where`. It compiles, and then `votes` counts only the helpful ones, so `likes` equals `votes` on every row.

**4. Group by a node, not a value.** "For each user, how many messages did they write?" What is counted is messages. What puts two messages in one group is having the same owner, and a path says how a message reaches its owner:

```kotlin
val top: List<Pair<String, Long>> = graphObjectManager.groups.load<MessageData, _> {
    where { query.role eq MessageRole.USER }
    val user = key(path<GuideUserData>(
        Hop("HAS_MESSAGE", Direction.INCOMING, label = "ChatSession"),
        Hop("OWNED_BY"),
    ))
    val messages = count()
    orderBy { messages.desc() }
    limit(5)
    rows { (it[user].username ?: it[user].id) to it[messages] }
}
```

```cypher
MATCH (n:StoredMessage)<-[:HAS_MESSAGE]-(:ChatSession)-[:OWNED_BY]->(k0:GuideUser:User)
WHERE n.role = $param_n_role_0
WITH DISTINCT n, k0
WITH k0, count(n) AS a0
RETURN { k0: { … }, a0: a0 } AS row
ORDER BY a0 DESC, k0.id ASC
LIMIT $_limit
```

The key comes back as the node itself, hydrated as a load of its fragment hydrates it: its subtype where the class is sealed, its `@GraphProperty` names, its `@PropertyBag` and its labels. `Hop` is the annotation a `@GraphPath` is made of, written here as a value, so no view is declared for the path. The read touches `role` of a message and nothing else of it.

**The parts**

| | |
| --- | --- |
| `where { … }` | The `where` of a load, over the source's own properties. |
| `key(query.x)` | Groups by a property. A property the fragment declares nullable is refused: it is `keyOrNull(query.x)`'s, whose nodes with no value are a group of their own. Two `key` lines are a compound key. |
| `key(path<N>(hops…))` | Groups by a `@NodeFragment` the source reaches, found by `N`'s labels at the end of the hops. |
| `count()` | `Long`. `count { … }` counts the nodes that meet the block. |
| `sum(query.x)` | `Long` for an `Int` or a `Long` property, `Double` for a `Float` or a `Double`. 0 over no values. |
| `avg(query.x)` | `Double?`. |
| `min(query.x)`, `max(query.x)` | The property's type, nullable. |
| `having { votes gte 3 }` | Compares one aggregate with a value: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`. Lines are joined by `AND`. A count is compared with any number. |
| `orderBy { votes.desc(); page.asc() }` | Orders by handles, with `nullsFirst()` and `nullsLast()`. A node key orders by its node's id. |
| `limit(n)`, `skip(n)` | Page the groups. |
| `rows { … }` | The last line, and required: its value is what the block returns. Every key and aggregate is declared before it: one declared as a row is read is refused. |

`sum`, `avg`, `min` and `max` take a filter block too: `sum(query.tokens) { query.role eq MessageRole.USER }`.

**What every engine agrees on**

- A group exists because a node is in it: no group has no members. A user with no message is not in the ranking.
- A node that reaches no key node is in no group. One that reaches several is in each, and once in each: however many ways lead from a node to a key node, it is one member of that group, in a count and in a sum alike.
- A path walks a relationship once: two hops of one type are two relationships, so a path there and back does not lead a node to itself. Two path keys may be reached over the same relationship.
- A read of keys alone gives each key once.
- An aggregate's filter never takes a node out of its group. A group none of whose nodes meet it is still returned, with 0.
- `sum`, `avg`, `min` and `max` skip a node with no value. Over no values `sum` is 0 and the others are null.
- Ties are broken by the key. Every order ends with the keys it left out, ascending, so a top 5 is the same five on every engine.
- Every value is bound as a parameter, and each condition has its own.

Where Oracle differs, and stays different:

- `min` and `max` of a text skip a value stored as `""`, which Oracle reads as no value. A key does not: `""` is a key of its own everywhere.
- `min` and `max` of a property no field declares compare it as text, so `"10"` is less than `"9"`. A key no field declares reads as a number where a number is stored.
- A key no field declares is text or a number there: a boolean reads as `"true"`, a whole `10.0` as `10`, and a list, a map or a text past 4000 bytes as no value.
- A text key is ordered by the session's `NLS_SORT`, which is binary unless it has been set otherwise.

On Oracle and Postgres, a `sum` or an `avg` of a `Double` is worked out in exact decimal, so it is more accurate: 0.1 + 0.2 is 0.3 there, where doubles give 0.30000000000000004. It differs from a graph engine's only around its 16th significant digit, and is still a `Double`.

**What is refused**, before anything is sent, where the compiler does not refuse it first: a read with no `key` (a count of everything is `count`); a `@GraphView` as the source; a `where` or a filter that names anything but the source's own properties; `key` on a property declared nullable, and `key` or an aggregate on one that holds a list; a `path` with no hops, to a class that is no `@NodeFragment`, or whose last hop asks for a label the class does not carry.

Not in this version: a view as the source, arithmetic inside `having` or `orderBy` (compute it in `rows`, as `it[votes] - it[likes]`), an aggregate over a related node's property (`@Aggregate` does that for one root), and a keyset `seek` over groups.

Without the code generator, pass the query object: `groups.load(FeedbackData::class.java, queryObject) { … }`. A subtype of a sealed fragment is read that way too, with its own query object: `load<Subtype, _> { }` is ambiguous with the base's, as `loadAll<Subtype> { }` is. **From Java** the handles come from the spec, and the block returns `rowsOf`:

```java
List<PageVotes> ratings = manager.getGroups().load(FeedbackData.class, dsl, spec -> {
    spec.onlyIf(dsl.getCreatedAt().isAtLeast(march));
    Key<String> page = spec.key(dsl.getPage());
    Aggregate<Long> votes = spec.count();
    Aggregate<Long> likes = spec.countWhere(dsl.getHelpful().isEqualTo(true));
    spec.having(votes, ComparisonOperator.GREATER_THAN_OR_EQUAL, 3);
    spec.orderBy(votes.descending());
    return spec.rowsOf(row -> new PageVotes(row.get(page), row.get(votes), row.get(likes)));
});
```

A path is `spec.key(spec.path(User.class, spec.hop("HAS_MESSAGE", Direction.INCOMING, "ChatSession"), spec.hop("OWNED_BY")))`, a sum `spec.sumOfLongs(dsl.getTokens())`, a filtered one `spec.filtered(spec.sumOfLongs(dsl.getTokens()), dsl.getRole().isEqualTo(Role.USER))`, and `manager.getGroups().countBy(FeedbackData.class, "page")` counts by a field.

### Saving Data

A save writes what the object holds. It does not depend on whether the object was loaded first, or on anything the manager remembers, because the manager remembers nothing.

```kotlin
import org.drivine.manager.Replace
import org.drivine.manager.RemovedTargets
import org.drivine.manager.nodeRef
import org.drivine.manager.update     // the reified update<T>(id) { }

graphObjectManager.save(view)                                              // every non-null field; relationships are added, never removed
graphObjectManager.save(view, Replace(IssueView::assignedTo))              // this field's list is the whole list
graphObjectManager.save(view, Replace.all())                               // every relationship field's list is the whole list
graphObjectManager.save(chunk, except = setOf(Chunk::embedding))           // write everything but these
graphObjectManager.save(person, only = setOf(Person::name))                // write just these
graphObjectManager.save(edited, from = loaded)                             // checked against the object as it was loaded
graphObjectManager.update<Person>(id) { it.copy(name = "Ada") }            // load, change, save what differs
graphObjectManager.create(person)                                          // make it only if it is not there; null when it is
graphObjectManager.edges.unrelate(nodeRef<Issue>(a), nodeRef<Person>(b), "ASSIGNED_TO")
```

A save is one Cypher statement: the root, the relationships it drops, each related node and the relationship to it. It is applied whole or not at all, with or without a transaction, on Neo4j, FalkorDB and Memgraph. Under `NullPolicy.CLEAR`, a root with a `@PropertyBag` or an open `@NodeLabels` field is read first, for the keys and labels it holds: one more statement, which writes nothing.

`only` and `except` name fields of the object, and for a view fields of its root. A view's relationships are written whatever they name. Give one or the other: both together are refused, and so is a property of a class other than the one saved.

`from` is the object as you loaded it, when what you save is a changed copy of it. The save is then checked against the stamp `from` carries, whatever stamp the copy carries, as `update` checks against what it loaded. On Neo4j it is also checked against each field of `from`'s root: see [when a save is refused](#when-a-save-is-refused). It is refused before anything is written if `from` is of another class, has another id, carries no stamp, is of a class (for a view, a root) with no `@NodeStamp` field, or is the object saved itself: an object changed in place no longer says what was loaded, so save a changed copy (a copy may share an unchanged root, as `view.copy(reviewers = …)` does). From Java, `save(edited, loaded)`.

```kotlin
val loaded = graphObjectManager.load<Person>(id)!!
// ... later, from what the user changed:
graphObjectManager.save(loaded.copy(email = form.email), from = loaded)
```

#### What `save` returns

`save` returns the saved object. On a type with a [`@NodeStamp`](#nodestamp-refusing-a-save-when-the-node-changed) field, use the returned object from then on: it carries the stamps the save left, on the root and on each related node that has one.

| The class | What comes back |
|---|---|
| declares no stamp field, on the root or on any related node | the object you passed |
| a Kotlin data class | a copy, as its `copy` would make |
| has fields that can be set, such as a Java object with setters | the object you passed, given its stamps in place |
| is immutable and not a data class, such as a Java record | a copy the object mapper rebuilds |

A class of the last kind that the object mapper cannot rebuild is refused with `IllegalArgumentException` before anything is written.

#### Relationships

A save adds the relationships the object holds and removes none. To remove, name the field in `Replace`: the field's list is then the whole list.

- A field removes only what it loads: relationships of its type and direction, to nodes with its target's labels. Two fields can share a relationship type, and a relationship that another field of the view holds, of the same type and direction, is that field's to keep.
- `Replace(field, removedTargets = DELETE_UNREFERENCED)` also deletes a removed target that nothing else refers to the way the field did. For an outgoing field that is a target no relationship points at; for an incoming field, one that points at nothing else; for an undirected field, one with no relationship left. The target's other relationships go with it. The root is never deleted, though a relationship from it to itself is removed. A node the object still holds, in another of its fields or in a view nested in one, is never deleted: a target moved from one list to another is kept. Anything more is a custom view or Cypher.
- `Replace.all()` covers every relationship field of the view itself. It is refused for an object that carries no stamp, because the lists of an object built from scratch are its defaults and not what the store holds. A view whose root declares no `@NodeStamp` field therefore names its fields.
- The lists of a view nested in the one saved only add: to trim one, use `update`, or save the nested view with `Replace`.
- A list to replace that is null is refused, whether it is named or covered by `Replace.all()`. An empty list removes every relationship of the field.
- A single-valued relationship field that is null, and is named in `Replace` or covered by `Replace.all()`, removes the relationship: for one node, null is how "none" is said.
- `Replace` trusts that the list came from a load. A list cut short by a custom query is taken as the whole list.
- `Replace` is refused, on a root that carries a stamp, if any relationship of the root was added or removed, or had its properties changed, since the object was loaded. A save that only adds is not: two writers who loaded the same view can each add to it. See [when a save is refused](#when-a-save-is-refused).
- An `UNDIRECTED` field is satisfied by a relationship stored in either direction. One is made, from the root, only when there is none.
- A node a field holds more than once is written once, as the last of them says, and joined once.
- `DELETE_UNREFERENCED` never deletes a node the object still holds in another of its fields: a target moved from one list to another is kept.
- A null property of a relationship fragment is left alone on the relationship, as a null field of a related node is. `update` clears one the change set to null.
- Where the store holds several relationships of one type between the same two nodes, a save writes its properties to each of them.
- A read-only field is never written: see [`@ReadOnly`](#readonly-a-field-that-is-loaded-and-never-written).
- `edges.unrelate(from, to, type)` removes the relationships of a type from one node to another, and `edges.unrelateAll(from, type, direction)` every one of a type. Neither deletes a node or touches a node's own properties.

**The statement's size.** The related nodes of a field are the rows of one `UNWIND`, so the statement's text does not grow with a list and an engine plans it once. Three kinds of related node still have a part of the statement each: a related view, a fragment with a `@PropertyBag` or a `@NodeLabels` field, and on FalkorDB a fragment with a `@VectorIndex` field. A long list of those makes a long statement. Index the id of each node type you save, as for any `MERGE`.

#### Load, Change and Save (`update`)

`update` loads the object, applies your change, and writes only what the change altered: the fields that differ, a field set to null, and for a view the relationships it added or dropped and the related nodes it altered. Of a related node that was loaded it writes the fields that differ and clears one set to null, and leaves the rest, so another writer's change to a field you did not touch stands. A related node the change added is written whole. A relationship another writer added in the meantime is kept, and one another writer removed stays removed. Your change may return a copy, or change the object it is given and return that.

- If the node changed between the load and the save, `update` loads it again and re-applies your change: three attempts in all by default, and then `StaleObjectException` is thrown. On Neo4j the save compares the root's fields as they were loaded as well as the stamp: see [when a save is refused](#when-a-save-is-refused).
- That needs a `@NodeStamp` field. Without one a change by another writer is not noticed.
- The id is a `String` or a `UUID`.
- `update` returns null when there is no such node, and when the node is deleted between the load and the save. It writes to the node it loaded and never makes one.
- A `StaleObjectException` that your change throws itself, from a save of its own, is thrown on and not tried again.
- A change that gives the object another id, or returns an object of another class, is refused.
- The load and the save are two statements. The save is one, and is refused if the node changed in between. A change to an open `@NodeLabels` field adds a read of the labels the node records as its own.

#### Create If Absent (`create`)

`create` makes the node only if no node has its id, in one statement. If one does, it writes nothing and returns null. With `update`, that is a save of an aggregate that loses no writer's data:

```kotlin
val stored = graphObjectManager.create(proposition)
    ?: graphObjectManager.update<Proposition>(proposition.id) { it.merge(proposition) }
```

A `save` of an object built from scratch cannot do this. Two writers that each load null and each save write the same node, and the second overwrites the first.

- **It returns the object carrying the whole stamp** the save left, as `save` does, or null when a node has the id. It is then for `update` to merge into what is there.
- **When the node is there, nothing is written**: not the root, not a related node, not a relationship, not the stamp.
- **A view** is made as `save` makes it: its root, each related node and the relationship to it. A related node is written unchecked, as `save` writes it, so one that was already there takes the object's fields.
- **An object that carries a stamp is refused**, with `IllegalArgumentException` and before anything is written: a stamp comes from a node that was loaded, and `create` makes one that is not there. Save it, or update it.
- **Two writers at the same moment.** Both find the node absent, as two `MERGE`s do. On Neo4j, Memgraph and Oracle only a uniqueness constraint on the id keeps the second from making the node again: `constraints.ensure(UniquenessConstraintSpec("Proposition", "id"))`. With one, the second waits for the first and finds its node (Neo4j), or is refused by the constraint (Memgraph, Oracle); either way it writes nothing and returns null. FalkorDB runs one write at a time, so there the second always finds the first's node, constraint or not. Without a constraint on an engine that runs writes side by side, both can make a node, and the id is then on two nodes.
- **In a transaction.** `create` joins it. On Memgraph a uniqueness constraint is checked when the transaction commits, so two transactions that each made the node fail at the commit of the second, and not at its `create`: run that transaction again, and its `create` returns null. Where the engine refuses the statement itself and the node is then found, `create` returns null, but the engine may already have marked the transaction for rollback, so an `update` after it in the same transaction fails. Run the transaction again.
- **The node is found by every label of the object's class**, as a `save` finds it. A `Manager` labelled `Worker:Manager` is found by `create(worker)`, but a node labelled only `Worker` is not found by `create(manager)`. Without a constraint, a second node is made. With a uniqueness constraint on a label the two share, the store refuses it and `create` throws.
- The statement is run again when the engine turns it away because another writer was changing the node, as a save is.
- `create` is on `StatelessGraphObjectManager` alone, as `save` and `update` are. There is no `createAll`: make each node with its own `create`.

See [docs/0.1.7-create-if-absent.md](../docs/0.1.7-create-if-absent.md) for why `create` returns null and how each engine makes the node only if it is absent.

#### Null-Write Policy (`NullPolicy`)

How a **null** field is treated on save is one declared, uniform contract (`save`, `saveAll`, every
engine, bagged or not) — defined purely on the object you pass:

```kotlin
graphObjectManager.save(chunk)                                   // IGNORE (default): merge-patch
graphObjectManager.save(chunk, nullPolicy = NullPolicy.CLEAR)    // full overwrite: nulls clear
```

- **`IGNORE` (default)** — writes only non-null fields; nulls are left untouched. A partially-loaded
  object never destroys stored data — including a `@VectorIndex` embedding (a `ChunkNode` reconstructed
  without its embedding won't wipe the stored vector). This is the safe default; no field is special.
- **`CLEAR`** — the object is authoritative: null fields clear the corresponding property (a full
  overwrite). Reach for it only with a complete object.

In a view the policy governs the root. A related node is written as under `IGNORE`: a null field of
it is left alone, and so are its stale `@PropertyBag` keys. To clear a field of a related node, use
`update`, or save that node itself.

`saveAll(..., nullPolicy = …)` behaves identically. Under
`CLEAR`, a `@PropertyBag` also drops keys absent from the current map; under `IGNORE` those keys are
left (merge-patch). See [docs/0.0.73-null-write-policy.md](../docs/0.0.73-null-write-policy.md).

#### Batch Save (`saveAll`)

Persist a collection in one group of statements. Within an ambient `@Transactional` the statements join it; otherwise they run together in a single transaction, and a failure on any item rolls the whole call back.

On Neo4j, FalkorDB and Memgraph, a batch of fragments alone, of any number of labels, is **one statement** where each fragment can be written as a row of an `UNWIND` and there are no more than 1,000 of them: it is applied whole on any of them, FalkorDB included. On Oracle a batch of fragments is one block for each 500 of them. Each view is a statement of its own, and so is a fragment with a `@PropertyBag` or a `@NodeLabels` field, or on FalkorDB one with a `@VectorIndex` field: the other fragments are still one statement. FalkorDB has no multi-statement transactions, so a batch of more than one statement is not applied whole there: in the default `WARN` mode it runs statement by statement and the first such batch logs a warning, and with `falkorDbTransactionMode` set to `STRICT` it is refused with `NonAtomicBatchException` before anything is written (inside `@Transactional`, `STRICT` refuses the transaction itself, at its first statement). `PersistenceManager.atomicBatches` says which a store does. See [docs/0.1.7-atomic-save-all.md](../docs/0.1.7-atomic-save-all.md).

```kotlin
val saved = graphObjectManager.saveAll(views)
val replaced = graphObjectManager.saveAll(views, Replace(PropositionView::mentions))
graphObjectManager.saveAll(chunks, only = setOf(Chunk::summary, Chunk::summaryModel))   // write just these fields of each
graphObjectManager.saveAll(chunks, except = setOf(Chunk::embedding))                    // write everything but these
```

- Fragments collapse into chunked `UNWIND … MERGE` statements (sub-linear round trips), with or without a `@NodeStamp` field. A view is saved by a statement of its own, as `save` does it.
- Heterogeneous collections are grouped by runtime class, and the returned list preserves input order.
- **Fragments are written in lock order**, on every store and however many statements they take: the classes in an order the classes alone fix (by their names; on Neo4j, FalkorDB and Memgraph those without a `@NodeStamp` first, and of those the ones saved one statement each first, while a stamped one saved one statement each goes last, after the views), and the fragments of each in the order of their ids, so two batches over the same nodes take their locks in the same order whatever order they were given in. Fragments of one id keep the order given, so the last is still the one written last. Views are written in the order given.
- A fragment with a `@PropertyBag` or a `@NodeLabels` field is saved one statement each. So, on FalkorDB, is a fragment with a `@VectorIndex` field, which that engine stores in a form a batch of rows cannot write.
- **A batch that only adds does not check stamps.** An object that carries a stale stamp is written all the same, where `save` of it would be refused. The object handed back then keeps the stamp it had, so a later `save` of it is refused: it does not hold what the other writer left.
- Otherwise the returned objects carry the stamps the batch left, so each can be saved again.
- A `Replace` applies to views: a batch that holds a fragment is refused with it. Each view that carries a stamp is checked as `save` checks it, and a stale one throws `StaleObjectException`. On Neo4j and Memgraph the batch is then not applied. On FalkorDB the statements that ran before the refused one stay applied.
- Inside a transaction of the caller's, a refusal undoes the batch only if that transaction is rolled back, as it is when the exception leaves it. Code that catches the exception and carries on commits what the batch wrote before the refusal.
- **One object of a batch holding another's root.** A view's save changes the nodes it holds. So a view saved with `Replace`, whose root an earlier object of the batch holds, can be refused by the batch's own doing. The exception then says so: `StaleObjectException.bySameBatch` is true. Give such a view before the objects that hold its root, or save them one at a time, each from what the save before it returned.
- A batch the engine turns away because another writer was changing the same nodes is run again, as a save is.
- Null handling follows [`NullPolicy`](#null-write-policy-nullpolicy), as for `save`.
- **`only` and `except`** name fields of each object, and for a view fields of its root. Every other field of the node is left as the store has it: neither written nor, under `NullPolicy.CLEAR`, cleared. A view's relationships are written whatever they name, and a related node is written whole. Give one or the other: both together are refused.
- **A field is named by its name.** `Chunk::summary` names the field `summary` of every class in the batch, whichever class declares the property, so it is the same rule from Kotlin and from Java. This is where `saveAll` differs from `save`, which refuses a property of a class other than the one saved. Each name must be a field of every class in the batch, or the batch is refused before anything is written. The id and the stamp are refused in `only`: neither is written from the object. A `@PropertyBag` or the `@NodeLabels` field can be named, and is then written as a save writes it.
- **With `only` or `except`, a node that is gone comes back.** `saveAll` is an upsert and a batch that only adds is not checked, so a node deleted since its object was loaded is made again, holding its id and the written fields alone, and for a view its relationships. It has its labels and a stamp, as any node a save makes. To write fields only where the node still exists, use [`patchAll`](#batch-patch-patchall).
- A batched statement is the same with fields named: each row, or on Oracle each step, carries those fields alone. A statement that saves one object, as for a view, sets those fields alone. A node gets a new stamp if a named field differs, if the save made it, or if it had none.
- The managers Drivine provides run the batch in one transaction. A `PersistenceManager` of your own that overrides `executeBatch` to do so must override `queryBatch` too, which `saveAll` calls and whose default runs each statement on its own.

#### Conditional Write by Id (`patch`)

Write named fields of one node by its id, if the node meets a predicate, in one statement. The node is not loaded: the store evaluates the predicate, so there is no second round trip and no loaded state to go stale. `update` is a load and then a checked save; `save(only = …)` needs the whole object, and cannot say "only if the stored value is older".

Advancing a session's activity, which must only move forward:

```kotlin
val result: PatchResult = graphObjectManager.patch<SessionData>(sessionId) {
    fields { query.lastActivityAt setTo activityAt }
    where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt activityAt } }
}
result.found    // a node has the id
result.applied  // the predicate held, so the fields were written
result.changed  // a value differed, and the node's stamp was renewed
```

The three are nested, so there are four outcomes:

| Outcome | `found` | `applied` | `changed` | The node's stamp |
| --- | --- | --- | --- | --- |
| No node has that id | false | false | false | there is no node |
| The node is there, and the predicate did not hold | true | false | false | as it was |
| The predicate held, and every value was already as set | true | true | false | as it was |
| The predicate held, and a value differed | true | true | true | new |

On a view the fields are the root's, and the predicate may name a relationship, as a load's does:

```kotlin
val narrated = graphObjectManager.patch<MessageInSession>(messageId) {
    fields { message.narration setTo narration }
    where { session.sessionId eq sessionId }
}.applied   // false for a message that is there, in another session
```

- **`fields`** names what to write: `property setTo value`, and `property.clear()` to remove a property. A value is typed by its property, so a null or a value of another type does not compile. A fragment's fields are reached as `query.x`, a view's by the name of its root (`message.narration`).
- **`where`** is the `where` of a load, and optional: without one the fields are written wherever the node is. It binds a value as a load's does, so a condition on a `@JsonPacked` field compares the value as given, and not as the field packs it. On FalkorDB a moment is stored as text, with all nine digits of its second (`2026-03-01T12:00:00.000000000Z`), so two moments compare in order when their offsets agree, as those of an `Instant` do. A release before this one wrote only the digits a moment had: a moment stored by one reads as before, but compares in order with a newer one, or as equal to it, only once it has been saved again.
- **It never makes a node.** A patch of an id no node has writes nothing and returns `found = false`.
- **It is not checked against a stamp, and hands none back.** The predicate is read under the node's lock, so what it compares of the node is what the last writer committed. A related node the predicate names is not locked. A node a patch changed has a new first token in its stamp, so an object loaded before the patch is refused when it is saved: load it again to go on working with it. The relationship token is left alone.
- A node whose values were already as set keeps its stamp, and none of the values is written to it. A node that never carried a stamp is given one, whatever the patch did to it. On the graph engines the lock is itself a property set and removed again, on every node the patch finds.
- A value is stored as the object mapper stores one of its type, which is how a save stores the field: an `Instant`, an enum and a `@VectorIndex` vector, which FalkorDB keeps in its own vector type. A `@JsonPacked` field is packed.
- The id, the `@NodeStamp` field, a key of a `@PropertyBag`, a `@NodeLabels` field and a field of a related node cannot be written. Nor can a field that carries `@JsonSerialize` or `@JsonFormat` be set, since a save stores it as the annotation says and a lone value cannot be; it can be cleared. Each is refused before anything is sent, and so is a field named twice.
- On a view, `found` says the root node is there, and not that the view would load: a message with no session is `found`, and not `applied` when the predicate names the session.
- The statement is applied whole or not at all, with or without a transaction, and is run again when the engine turns it away because another writer was changing the node. Inside a transaction it joins it. On Memgraph, inside a transaction that read the node before another writer changed it, the engine refuses the write itself and running it again cannot help: the patch fails, as a save does there.
- On Neo4j 4, which takes `EXISTS { }` only in a `WHERE`, the node is locked and then found again by an `OPTIONAL MATCH` that holds the predicate. The answers are the same.
- The id is taken to be unique. Where two nodes of the class share one, the graph engines write each that meets the predicate, and Oracle the one it finds first.
- `patch` is on `StatelessGraphObjectManager` alone. The generated `patch<T>(id) { }` passes the class's query DSL object; without the code generator, call `patch(id, T::class.java, queryObject) { }`.

#### Batch Patch (`patchAll`)

Write named fields of many nodes, where each node is there, and make no node. `saveAll` is an upsert, so a node deleted since its object was loaded comes back; `patchAll` leaves it gone and says so.

Re-embedding a page of messages, where a message may be deleted while the vectors are computed:

```kotlin
val embedded = page.mapIndexed { i, message -> message.copy(embedding = vectors[i], embeddingModel = modelName) }
val results: List<PatchResult> = graphObjectManager.patchAll(
    embedded,
    only = setOf(MessageData::embedding, MessageData::embeddingModel),
)
val rewritten = results.count { it.found }
```

- There is one `PatchResult` for each object, in the order given. `found` says whether the node was there, and `changed` whether a value written differed from what the node held. `applied` equals `found`: it differs only for a patch with a predicate, which [`patch`](#conditional-write-by-id-patch) of one node has.
- `only` or `except` is required. `except` means every field that can be named, but those given. To write every field of an object, save it.
- The id, the `@NodeStamp` field, a `@PropertyBag` and a `@NodeLabels` field cannot be named. The names are checked against the class of the objects: with no objects, property references are checked where they are all of one fragment, and names given as strings are not.
- The objects are where an id and values are read from, and no more. For a view the fields are its root's, and its relationships are not written.
- **A patch does not check stamps, and hands none back.** Only the named fields are written, so there is nothing else of the node to overwrite. A node a patch changed has a new stamp, so an object loaded before the patch is then stale: load it again to go on working with it.
- A node whose values were already as set keeps its stamp, and on the graph engines none of the values is written to it, so a vector index is not fed a vector it holds. A node that never carried a stamp is given one.
- Under `NullPolicy.IGNORE`, the default, a named field that is null on an object is left as the store has it. Under `CLEAR` it is cleared.
- The objects are of one class, and each id is given once and is not null. A call that breaks one of these is refused before anything is written.
- The id is taken to be unique. Where two nodes of the class share one, the graph engines write both, and Oracle the one it finds first.
- The rows go 1,000 to a statement, and 100 where a `@VectorIndex` field is written, as one batch: atomic as `saveAll` is, run again when the engine turns it away for contention, and, where it is more than one statement, refused on FalkorDB in `STRICT` mode. The rows are sent in the order of their ids, so two calls over the same nodes take their locks in the same order.
- A `@VectorIndex` field is written in the engine's own vector type, on FalkorDB too: a patch sets each field by name, where a batch save of such a fragment there is a statement for each.

#### Saving from Java

```java
graphObjectManager.save(view);
graphObjectManager.save(view, Replace.of(Set.of("assignedTo")));
graphObjectManager.save(view, Replace.of(Set.of("assignedTo"), RemovedTargets.DELETE_UNREFERENCED));
graphObjectManager.save(view, Replace.all());
graphObjectManager.saveFields(person, Add.INSTANCE, NullPolicy.IGNORE, Set.of("name"));                // only these fields
graphObjectManager.saveFields(chunk, Add.INSTANCE, NullPolicy.IGNORE, Set.of(), Set.of("embedding"));   // every field but these
List<IssueView> saved = graphObjectManager.saveAll(views);
graphObjectManager.saveAllFields(chunks, Add.INSTANCE, NullPolicy.IGNORE, Set.of("summary", "summaryModel")); // only these fields of each
List<PatchResult> patched = graphObjectManager.patchAllFields(chunks, NullPolicy.IGNORE, Set.of("embedding"));  // nodes that are there
PersonQueryDsl dsl = PersonQueryDsl.Companion.getINSTANCE();
PatchResult touched = graphObjectManager.patch(id, Person.class, dsl,
    spec -> spec.set(dsl.getName().assign("Ada")).onlyIf(dsl.getBio().isAbsent()));                   // one node, if it meets the predicate

Person updated = graphObjectManager.update(id, Person.class, p -> { p.setName("Ada"); return p; });   // null when there is no such node
graphObjectManager.update(id, Person.class, 5, p -> p.withName("Ada"));                                // five attempts
Person made = graphObjectManager.create(person);                                                      // null when a node has its id

graphObjectManager.getEdges().unrelate(new NodeRef(Issue.class, a, Set.of()), new NodeRef(Person.class, b, Set.of()), "ASSIGNED_TO");
```

- Java names fields as strings, so it calls `saveFields` where Kotlin passes property references to `save`, `saveAllFields` for `saveAll`, and `patchAllFields` for `patchAll`.
- A `patch` from Java names its fields with `property.assign(value)` and `property.cleared()` in `spec.set(…)`, and its predicate with the conditions of the Java query builder: `spec.onlyIf(…)` joins them by `AND`, and `spec.onlyIfAny(…)` by `OR`. The DSL object above is the one the Kotlin code generator makes; one the Java generator makes is `PersonQueryDsl.INSTANCE`, with `name()` for `getName()`.
- `@NodeStamp` goes on a `String` field of a Java class or on a `String` component of a record. A Java object whose fields can be set is given its new stamp in place, and `save` returns that same object. A record comes back as a copy.
- `update`'s function may change the object it is given and return it, or return another.
- A refused save throws `StaleObjectException`: `getDeleted()` says whether the node is gone, `getFoundStamp()` gives the stamp it carries now, `getBySameBatch()` says whether a `saveAll` refused itself, and `getFieldsDiffer()` whether a field differed under the stamp compared.
- `Stamps.setClause("p")` and `Stamps.linksClause("p")` give the `SET` items for Cypher of your own.

#### Saves and transactions

- A save joins the transaction it is called in, Spring's `@Transactional` or `@DrivineTransactional`, or a `transaction { }` block (see [Transactions in code](07-core-features.md#transactions-in-code-transactions)). Outside one it is its own.
- `StaleObjectException` is a `RuntimeException`, so a refusal that leaves a `@Transactional` method rolls the transaction back. It is not a `DrivineException`: a handler that catches only that does not catch it.
- A refused save has written nothing. What the transaction wrote before it is undone only if the transaction rolls back: code that catches the exception and carries on commits it.
- A checked save holds the node's write lock until the transaction commits, so another writer to the same node waits for it.
- A statement the engine turns away because another writer was changing the same node is run again, six attempts in all, on Neo4j and Memgraph. Inside a transaction the engine has usually failed the transaction with the statement, so the later attempts fail too and the first failure is what the caller sees.
- On FalkorDB each statement commits by itself. A save is still atomic, being one statement, and so is a `saveAll` of fragments alone that fits in one. An `update` is two, a load and a checked save, and a `saveAll` that holds a view is several: what ran before a failure or a refusal stays applied, unless `STRICT` mode refuses the batch before it runs.

See [docs/0.1.0-stateless-object-manager.md](../docs/0.1.0-stateless-object-manager.md) for the release that introduced this manager and what it changed.


### @NodeStamp: refusing a save when the node changed

Strongly recommended on any type that is loaded, changed and saved. It plays the role of JPA's `@Version`: optimistic locking, with a random value in place of a counter.

```kotlin
@NodeFragment(labels = ["Person"])
data class Person(
    @NodeId val id: String,
    val name: String,
    @NodeStamp val stamp: String? = null,
)
```

The field is a nullable `String` on a `@NodeFragment`, one to a fragment, in Kotlin or Java. It is refused on a `@GraphView` and on a `@RelationshipFragment`: a stamp is a node's.

#### The stamp

The stamp is two random tokens of 64 bits each, stored on the node under `__drivine.stamp` as `3fa9c1d27b40e8a6:91d0f4b2c7ee5a13`, whatever the field is called. Loading fills the field. Treat it as opaque.

- The **data token**, the first, speaks for the node's own data: its properties and labels.
- The **relationship token**, the second, speaks for the node's relationships, of every type and in both directions.
- To **mark** a node is to replace one of its tokens with a new one. A save marks what it changes, and so must Cypher of your own.

A save replaces the data token when it changes the node: a property that differs, a cleared property that held a value, a label added or dropped. A save that changes nothing leaves it as it is. The relationship token is replaced, at both ends, when a relationship is added or removed or its properties change: by a view saved from either end, by `edges`, or by the deprecated `GraphObjectManager`.

Every node a save writes is given a stamp if it has none, whether or not its type declares a `@NodeStamp` field, and so is the node at each end of a relationship that is written or removed. A stored value that is not two tokens, which no save leaves, counts as none and is replaced whole.

#### When a save is refused

A save is refused when what it would overwrite has changed since the object was loaded. Every save of an object that carries a stamp compares the data token. A save with `Replace` overwrites a relationship list, so it compares the relationship token too. A refused save writes nothing, to that node or any other, and throws `StaleObjectException`.

| Since the object was loaded, another writer… | `save` | `save` with `Replace` | `update` |
|---|---|---|---|
| changed a property or a label of the root | refused | refused | loads again and re-applies the change |
| added or removed a relationship of the root, or changed one's properties, from either end | applied | refused | applied |
| changed a related node | applied: every non-null field of the related node is written over the change | the same | applied: only the fields the change altered are written |
| deleted the root | refused, `deleted` is true | refused, `deleted` is true | returns null |
| changed anything with Cypher that does not mark it | not noticed | not noticed | not noticed |

Two cases are decided by the object, not by another writer:

| The object | `save` | `save` with `Replace` | `update` |
|---|---|---|---|
| carries a null stamp, or its root declares no `@NodeStamp` field | not checked: it makes the node or overwrites it | named fields are replaced unchecked; `Replace.all()` is refused with `IllegalArgumentException` | a change by another writer is not noticed |
| was saved over a node it had not loaded, and carries the stamp that save returned | applied | refused until it is loaded | — |

- So a node's own data can be saved while others attach relationships to it, two writers can each add a relationship to the same node, and a `Replace` of an object that carries a stamp never removes a relationship it did not load.
- The relationship token covers every relationship of the node. A `Replace` of one list is refused when a relationship of another type was added to the same node; load again, or use `update`.
- The whole save is one statement, so it is one round trip and atomic, on an engine without transactions too. The statement takes the node's write lock before it compares, so of several writers holding the same stamp exactly one succeeds and the rest are refused.
- In a view, the root is checked. A node reached through a relationship is written unchecked.
- `update` is checked against the stamp it loaded, whatever the change does with the object's.
- **On Neo4j a load can be handed a stamp newer than its data.** Neo4j reads under read committed, without a snapshot, so a load that runs while another writer commits can find that writer's stamp and the data from before its write. `update` therefore compares each field of the root as it loaded it too, under the node's lock, and loads again if one differs: a declared field's value, a `@PropertyBag`'s keys and their values, the labels a `@NodeLabels` field reads. A field loaded as what a load gives a node without the property (a `@Default`, an `@EmptyWhenAbsent` empty collection, a constructor default where the load reads every property) is taken as the same where the node has no such property, so a node saved before the field was added can still be updated. A `@JsonPacked` field is compared as the text the load read, not as its value packed again, so JSON another library, raw Cypher or a migration packed with other spacing or escaping is not taken for a change; one the node held nothing for, whose value came from a default, is packed as a save packs it. A `Set` field loads in the order the node holds its list, and is compared as the list the load read, so a list in another order than the set's, or holding an item twice, is not taken for a change either. A `save` checks the same when you give it the object as loaded, as [`from`](#saving-data): the text and the lists are kept beside the object a load returned, and beside the one an update or a save of named fields returned for a packed or set field it did not write, for as long as each is, so a `from` no load made, such as a copy, has its packed field compared as a save packs it, and a node whose text was packed another way then has the save refused, though nothing changed. A save refused because a field differs, where the node carries the stamp compared, says so, and `StaleObjectException.fieldsDiffer` is true. Without `from` it has only the stamp to go on, and in that moment it can write over the other writer's change: hold on to what you loaded and pass it, or change the node with `update`. Where only the stamp makes the round trip, as through a web form, the stamp is all there is. Memgraph and Neptune read from a snapshot, FalkorDB runs no read beside a write, and Oracle and Postgres read a statement whole, so on them the stamp is what was read.

Handling a refusal:

```kotlin
try {
    graphObjectManager.save(edited)
} catch (stale: StaleObjectException) {
    if (stale.deleted) {
        // the node is gone: decide whether to make it again
    } else {
        // another writer got there first: stale.foundStamp is what the node carries now.
        // Load it again and apply the change to what is there, which update does:
        graphObjectManager.update<Person>(edited.id) { it.copy(name = edited.name) }
    }
}
```

`update` is the shorter way when the change can be written as a function of the loaded object. It cannot help a type with no `@NodeStamp` field: there nothing is noticed, and nothing is retried.

#### The stamp a save hands back

`save` returns the object with the stamps the save left: the root's, and that of each related node that declares a stamp field. Use the returned object: if the save changed the node, the one you passed in is now stale.

- A stamp handed back carries a token of the node's only if what the token speaks for was as the object's stamp says when the save began. If another writer added or removed a relationship the object does not hold, the object keeps the relationship token it had: its own data can still be saved, and a `Replace` of it is refused until it is loaded again. If another writer changed the data of a node that was written unchecked (a related node, or an object in a `saveAll`), the object keeps the data token it had, and a save of it is refused until it is loaded again.
- An object that carried no stamp is handed the whole stamp of a node its save made. Of a node that was already there it is handed the data token alone: its lists did not come from the store. A save of the returned object that only adds is applied, and a `Replace` of it is refused until the object is loaded.
- A node the object holds more than once, in two fields or as the root and in one of its own lists, is handed the stamp the save left each time, where each copy carried the same stamp.
- `saveAll` hands stamps back as `save` does.

#### Existing data

A node written before 0.1.0 has no stamp, and a class that gains a `@NodeStamp` field loads it with the field null.

- The first save of such an object is not checked, and gives the node a stamp.
- That save returns the data token alone, the node having been there already, and `Replace.all()` is refused for an object whose stamp is null. So to replace the lists of such a node: save it, load it, then `Replace`. Or name the fields: a named `Replace` of an object with a null stamp is applied unchecked.
- To give every node of a label its stamp at once, so that the first load already carries one:

  ```kotlin
  persistenceManager.execute(QuerySpecification.withStatement(
      "MATCH (n:Person) WHERE n.`__drivine.stamp` IS NULL SET ${Stamps.setClause("n")}"
  ))
  ```

#### Cypher you write yourself

Cypher should mark what it changes. **A write that does not is not noticed**: a checked save of an object loaded before it is applied over it. `Stamps.setClause` marks a node whose mapped properties or labels it changes; `Stamps.linksClause` marks each end of a relationship it adds or removes, or whose properties it changes:

```kotlin
"MATCH (p:Person {id: \$id}) SET p.name = \$name, ${Stamps.setClause("p")}"

"""
MATCH (a:Person {id: \$a}), (b:Person {id: \$b})
CREATE (a)-[:KNOWS]->(b)
SET ${Stamps.linksClause("a")}, ${Stamps.linksClause("b")}
"""
```

- The property's name holds a dot, so Cypher that names it quotes it: ``n.`__drivine.stamp` ``.
- A node that is deleted and created again is noticed without a mark, because it has no stamp.
- Cypher that replaces every property of a node (`SET n = $props`) removes its stamp, and the next checked save of an object loaded before is refused as changed.
- **`bindObject` does not know the stamp.** An object of a stamped class bound with `bindObject("props", person)` carries its stamp under the field's own name, `stamp` here. `SET p += $props` then writes a property called `stamp` and leaves the real one as it was, so the write is not noticed; `SET p = $props` removes the real one. Save such an object with the object manager, or leave the stamp field out of what you bind and add `Stamps.setClause`.

#### What a stamp does not cover

- **A related node is not checked.** `save` writes every non-null field of it, so a stale copy of a related node overwrites another writer's change to it. `update` writes only the fields the change altered.
- **An object whose stamp is null is not checked.** It makes the node or overwrites it, as an upsert does.
- **A `saveAll` that only adds is not checked**, where a `save` of the same object is.
- **One relationship token covers every relationship of a node.** A `Replace` of one list is refused when a relationship of another type was added.
- **Deleting a node** removes its relationships without marking the nodes at their other ends.
- **Cypher that does not mark** what it changes is not noticed.
- **Saving with `CascadeType.DELETE_ALL`** has no equivalent on this manager: see [What you give up](15-migrating-from-graphobjectmanager.md#what-you-give-up).

#### Other writers and engines

- `edges.relate`, `unrelate` and `unrelateAll` mark both ends. `relate` leaves both alone when it finds the relationship there as it is; where several relationships of the type join the two nodes, it writes the properties to each and marks unless every one already carried them.
- The deprecated `GraphObjectManager` marks the nodes and relationships it changes, and only those, so a checked save notices its writes. It neither checks a stamp nor hands one back.
- On Neo4j and Memgraph, a statement the engine turns away because another writer was changing the same node is run again, six attempts in all, and a checked save is then refused as stale. FalkorDB runs one write at a time and turns none away. Only such a conflict is run again: a lost connection is not, since the save may have been applied.
- `edges` runs such a statement again too. `GraphObjectManager` does not, and the engine's error reaches the caller. On Memgraph two writers that mark the same node at the same moment conflict; on Neo4j two that take the same pair of nodes in opposite order can deadlock.
- A checked save the engine turns away on every attempt throws `StaleObjectException` with the engine's error as its cause, and no found stamp. The contended node can be one the save reaches through a relationship, so the exception says the save was turned away and that the node may have changed, not that it did. Memgraph does this inside a transaction that read the node before another writer changed it: that transaction has failed, and is to be run again from its start.
- Indexes and constraints are not affected. A save sets and removes a property `__drivine.lock` on each node it writes, within its statement, to hold the node's write lock while it compares. An unchecked write takes the lock too, so that it says truly whether it changed a node another writer is changing at the same moment, and `edges.relate` does the same for both nodes. A save that may make a node sets and removes `__drivine.made`, to tell a node it made from one it found. Neither is left on a node. A flat `@PropertyBag` does not read a property beginning `__drivine.`.
- A stamp field can be filtered and ordered on in the generated query DSL.

### @ReadOnly: a field that is loaded and never written

```kotlin
@GraphView
data class IssueOverview(
    @Root val issue: Issue,
    @GraphRelationship(type = "ASSIGNED_TO") val assignedTo: List<Person>,              // written on save
    @ReadOnly @GraphRelationship(type = "REVIEWED_BY") val reviewers: List<Person>,     // loaded only
)
```

Every save skips a `@ReadOnly` field: no relationship is written for it and the nodes it holds are not saved. Naming it in `Replace` is an error, and `Replace.all()` leaves it alone.

Three kinds of field are read-only whether or not they are declared so, because none of them names a single relationship a save could write:

- a `@GraphPath` field;
- a `@Count` or `@Aggregate` field;
- a list of fragments read over several hops (`maxDepth` above 1). A view nested in itself is not one: each level of it is one relationship from the level above, and it is saved.

`@ReadOnly` on one of them is allowed and changes nothing. To write along a path, use `edges.relate`, Cypher, or a view rooted where the hop starts.

### Deleting Data

The object manager provides type-safe methods for deleting graph objects.

#### Delete by ID

```kotlin
// Delete a single node by UUID
val deleted = graphObjectManager.delete<Person>(uuid)

// Delete a GraphView's root node (relationships are detached)
graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid)
```

#### Delete with WHERE Clause

```kotlin
// Delete only if condition is met
graphObjectManager.delete<Issue>(uuid, "n.state = 'closed'")

// For GraphViews, use the root fragment alias
graphObjectManager.delete<RaisedAndAssignedIssue>(uuid, "issue.state = 'closed'")
```

#### Delete All with Filter

```kotlin
// Delete all matching a condition
graphObjectManager.deleteAll<Issue>("n.state = 'closed'")

// For GraphViews
graphObjectManager.deleteAll<RaisedAndAssignedIssue>("issue.locked = true")
```

#### Type-Safe DSL Delete

The most powerful way - uses generated DSL for compile-time type checking:

```kotlin
// Delete closed issues
graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
    where {
        issue.state eq "closed"
    }
}

// Delete with multiple conditions
graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
    where {
        issue.state eq "open"
        issue.locked eq true
    }
}

// Delete by relationship property
graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
    where {
        assignedTo.name eq "Former Employee"
    }
}

// Delete all (no filter)
graphObjectManager.deleteAll<RaisedAndAssignedIssue> { }
```

#### Delete Behavior

A delete with no cascade uses `DETACH DELETE`:
- Removes the node and all its relationships
- Related nodes are **not** deleted (only the relationships to them); a cascade, below, deletes them too
- Returns the count of deleted nodes

```kotlin
// Delete an issue - persons remain, only ASSIGNED_TO/RAISED_BY relationships removed
graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid)

// Verify related nodes still exist
val person = graphObjectManager.load<Person>(personUuid)  // Still there!
```

#### Delete with a Cascade

Deleting a `@GraphView` by id can also delete the nodes the view reaches:

```kotlin
graphObjectManager.delete<SessionView>(sessionId)                               // NONE: the root only
graphObjectManager.delete<SessionView>(sessionId, CascadeType.DELETE_ORPHAN)    // and each node in the view that nothing outside it still needs
graphObjectManager.delete<SessionView>(sessionId, CascadeType.DELETE_ALL)       // and every node in the view
```

- The cascade follows the view's declared relationships, through nested views. A node outside the view is never deleted.
- A view that contains itself (`val parts: List<SessionTree>`, with a `maxDepth`) deletes what a load of it reads: the nodes at every level, and what each level holds through the view's other relationships.
- A cascade never stops short. If a recursive relationship reaches below its `maxDepth`, the delete throws `IllegalStateException` before anything is written, saying how deep the view reaches; raise the `maxDepth` to cover the deepest level.
- `DELETE_ALL` permanently deletes nodes that other nodes may still point at. Use it for nodes the root owns.
- `DELETE_ORPHAN` keeps a node in the view that a node outside it still refers to, at any level, together with everything the view holds below it: a bookmarked message keeps its attachments, and a pinned sub-session keeps its messages and its own sub-sessions. The rest of the view is deleted, including the session that held a kept message.
- `DELETE_ORPHAN` is not available on Memgraph.

### Generated Cypher Examples

#### Simple Load All

```kotlin
graphObjectManager.loadAll<PersonCareer>()
```

Generates:

```cypher
MATCH (person:Person:Mapped)

WITH
    person {
        bio: person.bio,
        name: person.name,
        uuid: person.uuid
    } AS person,

    [(person)-[employmentHistory_rel:WORKS_FOR]->(employmentHistory_target:Organization) |
        {
            startDate: employmentHistory_rel.startDate,
            role: employmentHistory_rel.role,
            target: employmentHistory_target {
                name: employmentHistory_target.name,
                uuid: employmentHistory_target.uuid
            }
        }
    ] AS employmentHistory

RETURN {
    person: person,
    employmentHistory: employmentHistory
} AS result
```

#### Filtered Query

```kotlin
graphObjectManager.loadAll<PersonCareer> {
    where {
        person.bio contains "Lead"
    }
}
```

Generates:

```cypher
MATCH (person:Person:Mapped)
WHERE person.bio CONTAINS $p0

WITH person { ... } AS person,
     [...] AS employmentHistory

RETURN { person: person, employmentHistory: employmentHistory } AS result
```

### Polymorphic Relationships

Drivine supports polymorphic relationship targets using label-based type discrimination. This allows a single relationship to point to different node types. You can define polymorphic types using either **sealed classes** or **interfaces**.

#### Defining Polymorphic Types with Sealed Classes

Use a sealed class hierarchy with `@NodeFragment` labels to define polymorphic types:

```kotlin
// Base sealed class - the "WebUser" label is shared by all subtypes
@NodeFragment(labels = ["WebUser"])
sealed class WebUser {
    abstract val uuid: UUID
    abstract val displayName: String
}

// Subtype with additional "Anonymous" label
@NodeFragment(labels = ["WebUser", "Anonymous"])
data class AnonymousWebUser(
    override val uuid: UUID,
    override val displayName: String,
    val anonymousToken: String  // Subtype-specific property
) : WebUser()

// Subtype with additional "Registered" label
@NodeFragment(labels = ["WebUser", "Registered"])
data class RegisteredWebUser(
    override val uuid: UUID,
    override val displayName: String,
    val email: String  // Subtype-specific property
) : WebUser()
```

In Neo4j, nodes have multiple labels:
- `(:WebUser:Anonymous {displayName: "Guest", anonymousToken: "abc123"})`
- `(:WebUser:Registered {displayName: "Alice", email: "alice@example.com"})`

#### Defining Polymorphic Types with Interfaces

For library-friendly polymorphism where implementations are defined externally by consumers, use interfaces with runtime registration.

The library defines the interface:

```kotlin
// Library code - interface with @NodeFragment
@NodeFragment(labels = ["SessionUser"])
interface SessionUser {
    @get:NodeId  // Required for Drivine change detection during save
    val id: String
    val displayName: String
}

// Library's GraphView uses the interface
@GraphView
data class StoredSession(
    @Root val session: SessionData,
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: SessionUser  // Interface type
)
```

Consumers implement the interface and register at startup:

```kotlin
// Consumer's implementation.
// Only the subtype's own label is needed — the parent's "SessionUser"
// label is inherited from the interface's @NodeFragment automatically,
// so saved nodes carry (:AppUser:SessionUser).
@NodeFragment(labels = ["AppUser"])
data class AppUser(
    @NodeId override val id: String,
    override val displayName: String,
    val email: String  // Consumer's custom fields
) : SessionUser

// Register in configuration (handles both Drivine and Jackson)
@Bean
fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
    val pm = factory.get("neo")
    pm.registerSubtype(
        SessionUser::class.java,
        listOf("AppUser", "SessionUser"),  // labels the persisted node carries
        AppUser::class.java
    )
    return pm
}
```

The `registerSubtype()` call configures both:
- Drivine's label-based polymorphism for loading
- Jackson's abstract type mapping for save operations

> **Note:** `@NodeFragment` labels declared on a parent interface (or superclass)
> are inherited at save time — a subtype persists with the union of its own labels
> and every annotated supertype's labels, de-duplicated. You no longer need to
> repeat the parent's labels in each subtype's `@NodeFragment`.

**Use interfaces when:**
- Implementations are defined in different modules/libraries
- You want to allow external extensions
- The type hierarchy isn't known at compile time

**Use sealed classes when:**
- All subtypes are defined in your codebase
- You want exhaustive `when` checking in Kotlin
- Subtypes are automatically discovered (no registration needed)

#### Using Polymorphic Relationships

Reference the sealed class in your `@GraphView`:

```kotlin
@GraphView
data class GuideUserWithPolymorphicWebUser(
    @Root val core: GuideUser,
    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser?  // Polymorphic - could be Anonymous or Registered
)
```

When loading, Drivine automatically deserializes to the correct subtype based on labels:

```kotlin
val results = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> { }

results.forEach { guide ->
    when (val user = guide.webUser) {
        is AnonymousWebUser -> println("Anonymous: ${user.anonymousToken}")
        is RegisteredWebUser -> println("Registered: ${user.email}")
        null -> println("No web user")
    }
}
```

#### Filtering Polymorphic Types

There are two approaches to filter by polymorphic subtype:

**Approach 1: Type-Specific View (Compile-Time)**

Create a view that uses the specific subtype:

```kotlin
@GraphView
data class AnonymousGuideUser(
    @Root val core: GuideUser,
    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: AnonymousWebUser  // Specific type, not WebUser
)

// Only returns guides with AnonymousWebUser
val anonymousGuides = graphObjectManager.loadAll<AnonymousGuideUser> { }
```

The generated query automatically filters by the subtype's labels.

**Approach 2: instanceOf DSL (Runtime)**

Use `instanceOf<T>()` to filter at query time while keeping the polymorphic view:

```kotlin
import org.drivine.query.dsl.instanceOf

// Filter to only anonymous users
val results = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> {
    where {
        webUser.instanceOf<AnonymousWebUser>()
    }
}

// Combine with other conditions
val activeAnonymous = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> {
    where {
        core.guideProgress gte 10
        webUser.instanceOf<AnonymousWebUser>()
    }
}

// Use in OR conditions
val anonymousOrRegistered = graphObjectManager.loadAll<GuideUserWithPolymorphicWebUser> {
    where {
        anyOf {
            webUser.instanceOf<AnonymousWebUser>()
            webUser.instanceOf<RegisteredWebUser>()
        }
    }
}
```

The `instanceOf<T>()` function:
- Extracts labels from the `@NodeFragment` annotation on type `T`
- Generates a Cypher label check: `WHERE EXISTS { ... WHERE webUser:WebUser:Anonymous }`
- Works with `anyOf` for OR conditions

| Approach | When to Use |
|----------|-------------|
| Type-specific view | You always want a specific subtype; compile-time type safety |
| `instanceOf<T>()` | Dynamic filtering; single view for multiple subtypes |

### Required vs Optional Relationships

Drivine distinguishes between required (non-nullable) and optional (nullable) relationships in `@GraphView` classes.

#### Optional Relationships (Nullable)

When a relationship property is nullable, Drivine returns all root nodes, even those without the relationship:

```kotlin
@GraphView
data class GuideUserWithOptionalWebUser(
    @Root val core: GuideUser,
    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser?  // Nullable - relationship is optional
)

// Returns ALL GuideUsers, even those without a WebUser
val results = graphObjectManager.loadAll<GuideUserWithOptionalWebUser> { }
results.forEach { guide ->
    if (guide.webUser != null) {
        println("Has web user: ${guide.webUser.displayName}")
    } else {
        println("No web user")
    }
}
```

#### Required Relationships (Non-Nullable)

When a relationship property is non-nullable, Drivine automatically filters out root nodes that don't have the relationship:

```kotlin
@GraphView
data class GuideUserWithRequiredWebUser(
    @Root val core: GuideUser,
    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser  // Non-nullable - relationship is required!
)

// Only returns GuideUsers that HAVE a WebUser
val results = graphObjectManager.loadAll<GuideUserWithRequiredWebUser> { }
// All results guaranteed to have webUser != null
```

The generated Cypher includes a `WHERE EXISTS` clause:

```cypher
MATCH (core:GuideUser)
WHERE EXISTS { (core)-[:IS_WEB_USER]->(:WebUser) }  -- Filters out nodes without relationship
WITH core, ...
RETURN { ... }
```

This prevents `MissingKotlinParameterException` that would occur if a null value was deserialized into a non-nullable property.

#### Summary

| Property Type | Behavior | Use Case |
|---------------|----------|----------|
| `val webUser: WebUser?` | Returns all root nodes | Optional relationship, handle null in code |
| `val webUser: WebUser` | Filters to only nodes with relationship | Required relationship, guaranteed non-null |
| `val webUsers: List<WebUser>` | Returns all root nodes (empty list if none) | Collection relationships are always safe |

