# Drivine4j

[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A graph database client library for Java and Kotlin supporting **Neo4j**, **FalkorDB**, **Amazon Neptune**, and **Memgraph** with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach)
2. **Object manager** (`StatelessGraphObjectManager`) - High-level API with annotated models and type-safe DSL.

Drivine4j is the graph database client library for [Embabel](https://hub.embabel.com) - Agentic AI for the JVM. 

## Philosophy

### Composition Over Inheritance

A typical ORM defines a reusable object model. From this model, statements are generated to hydrate to and from the model to the database. This addresses the so-called impedance mismatch between the object model and the database. However, there are drawbacks:

* Generated queries work well for simple cases, but can get out of hand and degrade performance when it's more complex. Debugging these generated statements can be painful.
* One model for many use cases is a big ask - the original CRUD cases work well, but more complex cases mean the model gets in the way more than it helps.

These trade-offs might be acceptable for relational databases, but with graph databases this mismatch doesn't really exist.

Just as we favor composition over inheritance in software development, we prefer composition when mapping results from complex queries. A person can play many roles: sometimes we're here to help them have a great holiday, other times to manage a team, in others they're a person of interest. With Drivine, you compose views as needed:

```kotlin
@GraphView
data class HolidayingPerson(
    @Root val person: Person,
    @GraphRelationship(type = "BOOKED_HOLIDAY")
    val holidays: List<Holiday>
)
```

Behind the scenes, Drivine generates efficient Cypher:

```cypher
MATCH (person:Person {firstName: $firstName})
WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.*}] AS holidays
RETURN {
         person:   properties(person),
         holidays: holidays
       }
```

Composition lets us mix and match as needed.  


## Requirements

- **Java 21+**
- **Kotlin:**
  - For PersistenceManager API: Any Kotlin version
  - For the object manager API: **Kotlin 2.2.0+** (requires context parameters feature)

## Installation

### Core Library

#### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("org.drivine:drivine4j:0.1.0")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'org.drivine:drivine4j:0.1.0'
}
```

#### Maven
```xml
<dependency>
    <groupId>org.drivine</groupId>
    <artifactId>drivine4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Code Generation (For the Type-Safe Query DSL)

If you want to use the object manager with the type-safe query DSL, you need to add the code generation processor.

> **Note for Java Projects:** Both Java and Kotlin are fully supported at runtime. The code generator (KSP) produces Kotlin DSL extensions, but a Java-friendly query builder API is also available. Define your `@GraphView` and `@NodeFragment` classes in either language. See the [Java Interoperability](#java-interoperability) section for details.

#### Gradle (Kotlin DSL)

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
    kotlin("jvm") version "2.2.0"
}

kotlin {
    compilerOptions {
        // Required for context parameters DSL
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

dependencies {
    implementation("org.drivine:drivine4j:0.1.0")
    ksp("org.drivine:drivine4j-codegen:0.1.0")
}
```

#### Maven

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>2.2.0</version>
            <configuration>
                <compilerPlugins>
                    <compilerPlugin>ksp</compilerPlugin>
                </compilerPlugins>
                <args>
                    <!-- Required for context parameters DSL -->
                    <arg>-Xcontext-parameters</arg>
                </args>
            </configuration>
            <dependencies>
                <!-- KSP extension for Maven -->
                <dependency>
                    <groupId>com.dyescape</groupId>
                    <artifactId>kotlin-maven-symbol-processing</artifactId>
                    <version>1.6</version>
                </dependency>

                <!-- Drivine code generator -->
                <dependency>
                    <groupId>org.drivine</groupId>
                    <artifactId>drivine4j-codegen</artifactId>
                    <version>0.1.0</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

**Note:** Maven support for KSP uses the third-party [kotlin-maven-symbol-processing](https://github.com/Dyescape/kotlin-maven-symbol-processing) extension.

## Quick Start

### 1. Configuration

```kotlin
@Configuration
@ComponentScan("org.drivine")
class AppConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        val props = ConnectionProperties(
            host = "localhost",
            port = 7687,
            username = "neo4j",
            password = "password",
            database = "neo4j"
        )
        return DataSourceMap(mapOf("neo" to props))
    }
}
```

### 2. Domain Model

```kotlin
data class Person(
    val uuid: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val age: Int
)
```

### 3. Repository Pattern

```kotlin
@Component
class PersonRepository @Autowired constructor(
    @Qualifier("neoManager") val manager: PersistenceManager
) {
    @Transactional
    fun findByCity(city: String): List<Person> {
        return manager.query(
            QuerySpecification
                .withStatement("MATCH (p:Person {city: \$city}) RETURN properties(p)")
                .bind(mapOf("city" to city))
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun findById(id: String): Person? {
        return manager.maybeGetOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$id}) RETURN properties(p)")
                .bind(mapOf("id" to id))
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun create(person: Person): Person {
        return manager.getOne(
            QuerySpecification
                .withStatement("CREATE (p:Person) SET p = \$props RETURN properties(p)")
                .bindObject("props", person)
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun update(uuid: String, patch: Partial<Person>): Person {
        val props = patch.toMap()
        return manager.getOne(
            QuerySpecification
                .withStatement(
                    "MATCH (p:Person {uuid: \$uuid}) SET p += \$props RETURN properties(p)"
                )
                .bind(mapOf("uuid" to uuid, "props" to props))
                .transform(Person::class.java)
        )
    }
}
```

### Important: RETURN Clause Best Practices

When using `PersistenceManager` with Cypher queries, always return a **single map** or **scalar value** -- not multiple columns. This ensures correct mapping with `.transform()` and avoids issues with NULL values.

```cypher
-- WRONG: Multiple columns -- hard to map, NULL values cause errors
RETURN a.name, a.age, b.title

-- CORRECT: Return a single map
RETURN { name: a.name, age: a.age, title: b.title } AS result

-- CORRECT: Return a single property map
RETURN properties(p)

-- CORRECT: Return a scalar value
RETURN count(p) AS total
```

If you need to aggregate across multiple nodes, compose the result into a single map in your `RETURN` clause:

```cypher
MATCH (p:Proposition)-[:HAS_MENTION]->(m:Mention)
WITH m.type AS entityType, m.name AS name, count(p) AS mentionCount
ORDER BY mentionCount DESC
LIMIT 30
RETURN {
  entityType: entityType,
  name: name,
  mentionCount: mentionCount
} AS result
```

This way `.transform(MyDto::class.java)` can map the result directly to a data class.

## Object Manager - Type-Safe Graph Mapping

`StatelessGraphObjectManager` is a high-level API for working with graph-mapped objects using annotated models. It generates efficient Cypher queries automatically and provides a type-safe DSL for filtering and ordering.

It keeps no state between calls. What a load returns is what the store holds, and what a save writes depends only on the object and the arguments you pass.

Get one from the `GraphObjectManagerFactory`, which the Spring Boot starter provides:

```kotlin
@Bean
fun graphObjectManager(factory: GraphObjectManagerFactory): StatelessGraphObjectManager = factory.stateless()
```

> Earlier releases documented `GraphObjectManager`, which tracks what it loads in a session. It is deprecated: see [Migrating from GraphObjectManager](#migrating-from-graphobjectmanager).

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

`@Count` needs no property; `SUM`/`AVG`/`MIN`/`MAX` aggregate a numeric property of the related nodes. Aggregates are single-hop. For group-by *ranking* (top-N), use `PersistenceManager` + `.transform<T>()` with Cypher — that's not a node-rooted view.

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
graphObjectManager.count(Issue::class.java)                    // 3 — every Issue node
graphObjectManager.count(RaisedAndAssignedIssue::class.java)   // 2 — only Issues with a RAISED_BY edge
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
    graphObjectManager.loadNearest(PropositionView::class.java, queryEmbedding, topK = 20)

// Search a fragment: ranks bare PropositionNodes
val nodes: List<Scored<PropositionNode>> =
    graphObjectManager.loadNearest(PropositionNode::class.java, queryEmbedding, topK = 20)
```

The embedding to search is identified by a `@VectorIndex` annotation on the searched fragment (the
view's root fragment, or the fragment itself) —
**inferred** when the fragment declares one embedding, or **named explicitly** to pick among
several. `@VectorIndex` is the query-side declaration of "this embedding is searchable" and is
independent of how the index is *created*: it works whether you create the index from the annotation
(`SchemaCatalog.fromFragments(...)`) or from an explicit spec (`SchemaCatalog.of(VectorIndexSpec(...))`);
in the latter case, add the annotation to the fragment as well — it carries no creation side effects
on its own.

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
    graphObjectManager.loadNearest(PropositionView::class.java, queryEmbedding, topK = 20)

hits.forEach { println("${it.score} → ${it.value.proposition.text}") }

// Disambiguate when a node carries several embeddings, and/or floor by similarity
graphObjectManager.loadNearest(PropositionView::class.java, "titleEmbedding", queryEmbedding, topK = 20, threshold = 0.8)
```

Each result is a `Scored<T>(value, score)`; the score is normalized to **similarity, higher = more
similar** on every engine, so ordering and `threshold` mean the same thing regardless of backend.

**`topK` is the index's `k`, not a guaranteed result count.** When searching a **view**, its
*required* relationships (and the optional `threshold`) are applied **after** the K-nearest search,
so a candidate that ranks in the top K but fails the filter is dropped — meaning **`loadNearest` can
return fewer than `topK` results**. A **fragment** search has no relationship filter, so it returns
the full top K (minus any `threshold` cut).

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
mitigates this. See [0.0.79-vector-search-k.md](docs/0.0.79-vector-search-k.md).

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

The label changes only which index is located: the node still carries its own `:Proposition` label, so
`where { }` and the projection are unaffected. The index name re-derives as
`${label}_${property}_vector`, matching what `VectorIndexSpec` would create — so a fragment whose
`@VectorIndex` pins an explicit name cannot be partitioned, and says so.
See [0.0.79-vector-partitioning.md](docs/0.0.79-vector-partitioning.md).

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
See [0.0.79-vector-index-tuning.md](docs/0.0.79-vector-index-tuning.md).

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
`@VectorIndex`-bearing **view** (its root fragment) *and* bare **fragment** (mirroring the generated
`loadAll { }`). The filtered form works on a fragment too — filter its own properties directly:

```kotlin
// filtered vector search over a bare @NodeFragment
graphObjectManager.loadNearest(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE, queryVector, topK = 20) {
    where { query.containerSectionId eq "sec-1" }
}
```

The predicate is applied **after** the K-nearest search, so it really does prune — a node that ranks
in the top K but fails the predicate is dropped, and `topK` remains the index's `k` (the result may
contain fewer rows). Property predicates filter the projected root; relationship quantifiers
(`any{}`/`none{}`) filter the projected relationship collection (`any(m IN mentions WHERE …)`).
Multiple `any{}` AND together — "mentions *all* of these entities" is one `any{}` per id. Referencing
a relationship the view does not project is an error.

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
same annotation the schema feature uses to *create* it. `threshold` defaults to `0.0` (keep everything);
`topK` is applied as a trailing `LIMIT`. Each result is a `Scored<T>(value, score)`, most relevant
first, with polymorphic dispatch (a `loadMatching<SealedBase>` returns each hit as its concrete subtype).

The query string is passed through to the engine's full-text language (Lucene syntax on Neo4j:
`AND`/`OR`/`"phrase"`/`field:term`); raw user input is not escaped for you. Backends without a native
full-text index throw `UnsupportedOperationException`.

**Filtering with `where { }`** works exactly like `loadNearest` — full-text relevance plus property
predicates in one query, on a view or a bare fragment:

```kotlin
graphObjectManager.loadMatching(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE, "graph databases", topK = 20) {
    where { query.containerSectionId eq "sec-1" }
}
```

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

// to filter a subtype by its OWN property, use the explicit 3-arg form with that subtype's DSL
graphObjectManager.loadAll(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE) {
    where { query.containerSectionId eq "sec-1" }
}
```

Use `query.<field>` (the Kotlin field name); `@GraphProperty` on-disk names are mapped for you. The same
`<Fragment>QueryDsl.INSTANCE` powers the filtered `loadNearest` / `loadMatching` forms above.

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

Rendered as an existence subquery, per engine — `EXISTS { (root)-[:HAS_MENTION]->(m) WHERE … }` on
Neo4j, `size([(root)-[:HAS_MENTION]->(m) WHERE … | 1]) > 0` on Memgraph, a `CALL`-subquery count on
FalkorDB — with `none{}` wrapping it in `NOT (…)`. `any{}` is the explicit form of the flat
`mentions.resolvedId eq id` shorthand; prefer it for `none`, for correlated multi-condition blocks,
or for clarity. (Backends without subquery support throw, per the grammar default.)

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
filter nulls out in `where`.

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

A query already pinned to a single root — a top-level `where` equality on the `@NodeId`, or on a
property carrying a single-property uniqueness constraint — is never advised on. Its ordering is over
one row, so no index could change the plan. An equality inside `anyOf` doesn't count, since it
constrains only one branch of the OR.

The index list is read once and cached per manager, so the check costs one round trip per process,
not one per query. If your application ensures indexes lazily, after the first ordered query has
already run, that cache will be stale — construct the manager after schema setup, or use `OFF`.

See [docs/0.0.75-index-aware-keyset-pagination.md](docs/0.0.75-index-aware-keyset-pagination.md) for
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

| Engine | Strategy | Nested Sort |
|--------|----------|-------------|
| Neo4j (default) | `apoc.coll.sortMaps()` | Supported |
| Neo4j (CALL) | `CALL { ORDER BY + collect }` | Supported |
| FalkorDB | `CALL { ORDER BY + collect }` | Supported (via CALL prolog) |
| Neptune | `CALL { ORDER BY + collect }` | Supported (via CALL prolog) |
| Memgraph | `CALL { ORDER BY + collect }` | Supported (no APOC, uses CALL) |

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
- `containsIgnoreCase` / `eqIgnoreCase` - case-insensitive contains / equals (`toLower(...)`)

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
`@PropertyBag` path or one with a hyphen or a space, is backtick-quoted for you. FalkorDB refuses a
key that holds a backtick.

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

### Saving Data

A save writes what the object holds. It does not depend on whether the object was loaded first, or on anything the manager remembers, because the manager remembers nothing.

```kotlin
graphObjectManager.save(view)                                              // every field; relationships are added, never removed
graphObjectManager.save(view, Replace(IssueView::assignedTo))              // this field's list is the whole list
graphObjectManager.save(view, Replace.all())                               // every relationship field is
graphObjectManager.save(chunk, except = setOf(Chunk::embedding))           // write everything but these
graphObjectManager.save(person, only = setOf(Person::name))                // write just these
graphObjectManager.update<Person>(id) { it.copy(name = "Ada") }            // load, change, save what differs
graphObjectManager.edges.unrelate(nodeRef<Issue>(a), nodeRef<Person>(b), "ASSIGNED_TO")
```

`save` returns the saved object. On a type with a [`@NodeStamp`](#nodestamp-refusing-a-save-when-the-node-changed) field, use the returned object from then on: it carries the stamps the save left, on the root and on each related node that has one. A Kotlin data class comes back as a copy. A class whose fields can be set, such as a Java object with setters, is given its stamps in place and returned itself.

A save is one Cypher statement: the root, the relationships it drops, each related node and the relationship to it. It is applied whole or not at all, with or without a transaction, on Neo4j, FalkorDB and Memgraph. Under `NullPolicy.CLEAR`, a root with a `@PropertyBag` or an open `@NodeLabels` field is read first, for the keys and labels it holds: one more statement, which writes nothing.

`only` and `except` name fields of the object, and for a view fields of its root. A view's relationships are written whatever they name.

#### Relationships

A save adds the relationships the object holds and removes none. To remove, name the field in `Replace`: the field's list is then the whole list.

- A field removes only what it loads: relationships of its type and direction, to nodes with its target's labels. Two fields can share a relationship type.
- `Replace(field, removedTargets = DELETE_UNREFERENCED)` also deletes a removed target that nothing else refers to the way the field did. For an outgoing field that is a target no relationship points at; for an incoming field, one that points at nothing else; for an undirected field, one with no relationship left. The target's other relationships go with it. The root is never deleted, though a relationship from it to itself is removed. Anything more is a custom view or Cypher.
- `Replace.all()` covers every relationship field of the view itself. The lists of a view nested in it only add: to trim one, use `update`, or save the nested view with `Replace`. It is refused for an object that carries no stamp, because the lists of an object built from scratch are its defaults and not what the store holds. A view whose root declares no `@NodeStamp` field therefore names its fields.
- A list to replace that is null is refused, whether it is named or covered by `Replace.all()`. An empty list removes every relationship of the field.
- `Replace` trusts that the list came from a load. A list cut short by a custom query is taken as the whole list.
- `Replace` is refused, on a root that carries a stamp, if any relationship of the root was added or removed since the object was loaded: from this end or the other, by a view, `edges` or `GraphObjectManager`. A save that only adds is not: two writers who loaded the same view can each add to it. See [`@NodeStamp`](#nodestamp-refusing-a-save-when-the-node-changed).
- A save is one statement whose text does not grow with a list: the related nodes of a field are its rows. Index the id of each node type you save, as for any `MERGE`.
- An `UNDIRECTED` field is satisfied by a relationship stored in either direction. One is made, from the root, only when there is none.
- A node a field holds more than once is written once, as the last of them says, and joined once.
- `DELETE_UNREFERENCED` never deletes a node the object still holds in another of its fields: a target moved from one list to another is kept.
- A null property of a relationship fragment clears the property on the relationship, whatever the `NullPolicy`: the policy governs the root's fields.
- `edges.unrelate(from, to, type)` removes the relationships of a type from one node to another, and `edges.unrelateAll(from, type, direction)` every one of a type. Neither deletes a node or touches a node's own properties.

#### Load, Change and Save (`update`)

`update` loads the object, applies your change, and writes only what the change altered: the fields that differ, a field set to null, and for a view the relationships it added or dropped and the related nodes it altered. Of a related node that was loaded it writes the fields that differ and clears one set to null, and leaves the rest, so another writer's change to a field you did not touch stands. A related node the change added is written whole. A relationship another writer added in the meantime is kept, and one another writer removed stays removed. Your change may return a copy, or change the object it is given and return that.

- If the node changed between the load and the save, `update` loads it again and re-applies your change: three attempts in all by default, and then `StaleObjectException` is thrown.
- That needs a `@NodeStamp` field. Without one a change by another writer is not noticed.
- `update` returns null when there is no such node.
- The load and the save are two statements. The save is one, and is refused if the node changed in between. A change to an open `@NodeLabels` field adds a read of the labels the node records as its own.

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
left (merge-patch). See [docs/0.0.73-null-write-policy.md](docs/0.0.73-null-write-policy.md).

#### Batch Save (`saveAll`)

Persist a collection in one group of statements. Within an ambient `@Transactional` the statements join it; otherwise they run together in a single transaction, and a failure on any item rolls the whole call back. FalkorDB has no multi-statement transactions: there each statement is atomic and the batch is not, and with `falkorDbTransactionMode` set to `STRICT` a batch is refused, as any transaction is.

```kotlin
val saved = graphObjectManager.saveAll(views)
val replaced = graphObjectManager.saveAll(views, Replace(PropositionView::mentions))
```

- Fragments collapse into chunked `UNWIND … MERGE` statements (sub-linear round trips), with or without a `@NodeStamp` field. A view is saved by a statement of its own, as `save` does it.
- Heterogeneous collections are grouped by runtime class, and the returned list preserves input order.
- Fragments with a `@PropertyBag` or a `@NodeLabels` field are saved one statement each.
- A batch that only adds does not check stamps: an object that carries a stale stamp is written all the same. The object handed back then keeps the stamp it had, so a later `save` of it is refused: it does not hold what the other writer left.
- Otherwise the returned objects carry the stamps the batch left, so each can be saved again.
- A `Replace` is part of the batch, and is checked as `save` checks it: a view that carries a stamp is refused with `StaleObjectException` if the node or its relationships changed since it was loaded, and then the batch is not applied. A view that carries no stamp is not checked, as with `save`.
- A batch the engine turns away because another writer was changing the same nodes is run again, as a save is.
- A fragment with a `@VectorIndex` field is saved one statement each on FalkorDB, which stores a vector in a form a batch of rows cannot write.
- Null handling follows [`NullPolicy`](#null-write-policy-nullpolicy), as for `save`.

#### Saving from Java

```java
graphObjectManager.save(view);
graphObjectManager.save(view, Replace.of(Set.of("assignedTo")));
graphObjectManager.save(view, Replace.of(Set.of("assignedTo"), RemovedTargets.DELETE_UNREFERENCED));
graphObjectManager.save(view, Replace.all());
graphObjectManager.saveFields(person, Add.INSTANCE, NullPolicy.IGNORE, Set.of("name"));                // only these fields
graphObjectManager.saveFields(chunk, Add.INSTANCE, NullPolicy.IGNORE, Set.of(), Set.of("embedding"));   // every field but these
List<IssueView> saved = graphObjectManager.saveAll(views);

Person updated = graphObjectManager.update(id, Person.class, p -> { p.setName("Ada"); return p; });   // null when there is no such node
graphObjectManager.update(id, Person.class, 5, p -> p.withName("Ada"));                                // five attempts

graphObjectManager.getEdges().unrelate(new NodeRef(Issue.class, a, Set.of()), new NodeRef(Person.class, b, Set.of()), "ASSIGNED_TO");
```

- Java names fields as strings, so it calls `saveFields` where Kotlin passes property references to `save`.
- A Java object whose fields can be set is given its new stamp in place, and `save` returns that same object. `update`'s function may change the object it is given and return it, or return another.
- A refused save throws `StaleObjectException`: `getDeleted()` says whether the node is gone, and `getFoundStamp()` gives the stamp it carries now.
- `Stamps.setClause("p")` and `Stamps.linksClause("p")` give the `SET` items for Cypher of your own.

See [docs/0.1.0-stateless-object-manager.md](docs/0.1.0-stateless-object-manager.md) for the release that introduced this manager and what it changed.


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

- The stamp is two random tokens, stored on the node under `__drivine.stamp` as `3fa9c1d27b40e8a6:91d0f4b2c7ee5a13`. Loading fills the field. Treat it as opaque.
- The first token speaks for the node's own data. A save that changes a property, or adds or drops a label, replaces it. A save that changes nothing leaves it as it is.
- The second token speaks for the node's relationships. It is replaced, at both ends, when a relationship is added or removed or its properties change: by a view saved from either end, by `edges`, or by `GraphObjectManager`.
- A save is refused when what it would overwrite has changed since the object was loaded. Every save of an object that carries a stamp compares the first token. A save with `Replace` overwrites a relationship list, so it compares the second too.
- So a node's own data can be saved while others attach relationships to it, two writers can each add a relationship to the same node, and a `Replace` of an object that carries a stamp never removes a relationship it did not load. An object whose stamp is null, or whose root declares none, is not checked.
- The second token covers every relationship of the node. A `Replace` of one list is refused when a relationship of another type was added to the same node; load again, or use `update`.
- A refused save writes nothing, to that node or any other, and `StaleObjectException` says what happened. The whole save is one statement, so it is one round trip and atomic, on an engine without transactions too. The statement takes the node's write lock before it compares, so of several writers holding the same stamp exactly one succeeds and the rest are refused.
- A save of an object whose stamp is null is not checked: it creates the node or overwrites it.
- `save` returns the object with the stamps the save left: the root's, and that of each related node that declares a stamp field. Use the returned object: if the save changed the node, the one you passed in is now stale. A stamp handed back carries a token of the node's only if what the token speaks for was as the object's stamp says when the save began. If another writer added or removed a relationship the object does not hold, the object keeps the relationship token it had: its own data can still be saved, and a `Replace` of it is refused until it is loaded again. If another writer changed the data of a node that was written unchecked (a related node, or an object in a `saveAll`), the object keeps the node token it had, and a save of it is refused until it is loaded again.
- `update` retries on a conflict, loading again and re-applying your change. It is checked against the stamp it loaded, whatever the change does with the object's, and refuses a change that gives the object another id.
- In a view, the root is checked. A node reached through a relationship is written unchecked. `save` writes every field of it that is not null, so a stale copy of a related node overwrites another writer's change to it. `update` writes only the fields the change altered.
- `edges.relate`, `unrelate` and `unrelateAll` write the token at both ends, and `relate` leaves both alone when it finds the relationship there as it is. On Memgraph two of them that touch the same node at once can conflict; `edges` does not retry, and the engine's error reaches the caller. The same holds for a save by the deprecated `GraphObjectManager`.
- `saveAll` stamps the nodes it changes and hands the stamps back as `save` does. It checks a view saved with `Replace`, and nothing else. The deprecated `GraphObjectManager` stamps the nodes and relationships it changes too, so a checked save notices its writes.
- Deleting a node removes its relationships without marking the nodes at their other ends.
- Indexes and constraints are not affected. A checked save sets and removes a property `__drivine.lock` within its statement, to hold the node's write lock; it is never left on a node. A flat `@PropertyBag` does not read a property beginning `__drivine.`.

**Cypher you write yourself** should mark what it changes, or a checked save will not notice the change. `Stamps.setClause` marks a node whose mapped properties it changes; `Stamps.linksClause` marks each end of a relationship it adds or removes:

```kotlin
"MATCH (p:Person {id: \$id}) SET p.name = \$name, ${Stamps.setClause("p")}"

"""
MATCH (a:Person {id: \$a}), (b:Person {id: \$b})
CREATE (a)-[:KNOWS]->(b)
SET ${Stamps.linksClause("a")}, ${Stamps.linksClause("b")}
"""
```

A node that is deleted and created again is noticed without this, because it has no stamp. For the same reason, Cypher that replaces every property of a node (`SET n = $props`) removes its stamp, and the next checked save of an object loaded before is refused as changed.

### @ReadOnly: a field that is loaded and never written

```kotlin
@GraphView
data class IssueOverview(
    @Root val issue: Issue,
    @GraphRelationship(type = "ASSIGNED_TO") val assignedTo: List<Person>,              // written on save
    @ReadOnly @GraphRelationship(type = "REVIEWED_BY") val reviewers: List<Person>,     // loaded only
)
```

Every save skips a `@ReadOnly` field: no relationship is written for it and the nodes it holds are not saved. Naming it in `Replace` is an error.

A `@GraphPath`, `@Count` or `@Aggregate` field is read-only whether or not it is declared so: none of them names a single relationship a save could write. `@ReadOnly` on one is allowed and changes nothing. To write along a path, use `edges.relate`, Cypher, or a view rooted where the hop starts.

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
graphObjectManager.delete<SessionView>(sessionId, CascadeType.DELETE_ORPHAN)    // and each node in the view left with no relationship
graphObjectManager.delete<SessionView>(sessionId, CascadeType.DELETE_ALL)       // and every node in the view
```

- The cascade follows the view's declared relationships, through nested views. A node outside the view is never deleted.
- `DELETE_ALL` permanently deletes nodes that other nodes may still point at. Use it for nodes the root owns.
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

## Core Features (PersistenceManager)

### Fluent Query Building

```kotlin
val activeAdults = manager.query(
    QuerySpecification
        .withStatement("MATCH (p:Person) RETURN properties(p)")
        .transform(Person::class.java)
        .filter { it.age >= 18 }           // Client-side filtering
        .filter { it.email != null }
        .map { it.firstName }              // Transform to String
        .limit(10)
)
```

### Chainable Transformations

```kotlin
val fullNames: List<String> = manager.query(
    QuerySpecification
        .withStatement("MATCH (p:Person) RETURN properties(p)")
        .transform(Person::class.java)    // Map to Person
        .filter { it.age > 25 }            // Filter
        .map { "${it.firstName} ${it.lastName}" }  // Transform to String
)
```

### Transaction Management

```kotlin
@Component
class UserService @Autowired constructor(
    private val personRepo: PersonRepository,
    private val emailService: EmailService
) {
    @Transactional  // Spring's @Transactional works
    fun registerUser(person: Person) {
        val created = personRepo.create(person)
        emailService.sendWelcome(created.email)
        // Auto-commits on success, rolls back on exception
    }

    @DrivineTransactional  // Or use Drivine's annotation
    fun updateUserProfile(uuid: String, updates: Partial<Person>) {
        personRepo.update(uuid, updates)
    }
}
```

### Partial Updates

```kotlin
val updates = partial<Person> {
    set(Person::email, "newemail@example.com")
    set(Person::age, 30)
}
personRepo.update(personId, updates)
```

### External Query Files

Place `.cypher` files in `src/main/resources/queries/`:

```cypher
// queries/findActiveUsers.cypher
MATCH (p:Person)
WHERE p.isActive = true
RETURN properties(p)
```

Load and use:

```kotlin
@Configuration
class QueryConfig @Autowired constructor(
    private val loader: QueryLoader
) {
    @Bean
    fun findActiveUsers() = CypherStatement(loader.load("findActiveUsers"))
}

@Component
class PersonRepository @Autowired constructor(
    @Qualifier("neoManager") val manager: PersistenceManager,
    val findActiveUsers: CypherStatement
) {
    fun getActive(): List<Person> {
        return manager.query(
            QuerySpecification
                .withStatement(findActiveUsers.statement)
                .transform(Person::class.java)
        )
    }
}
```

### Multiple Query Results

```kotlin
// Expect exactly one result (throws if 0 or >1)
val person: Person = manager.getOne(spec)

// Expect 0 or 1 result (returns null if not found)
val maybePerson: Person? = manager.maybeGetOne(spec)

// Return all results
val people: List<Person> = manager.query(spec)

// Execute without returning results (for mutations)
manager.execute(spec)
```

## API Reference

### PersistenceManager

```kotlin
interface PersistenceManager {
    fun <T> query(spec: QuerySpecification<T>): List<T>
    fun <T> getOne(spec: QuerySpecification<T>): T
    fun <T> maybeGetOne(spec: QuerySpecification<T>): T?
    fun <T> execute(spec: QuerySpecification<T>)
}
```

### QuerySpecification

```kotlin
QuerySpecification
    .withStatement(cypherQuery)       // Start with Cypher query
    .bind(params)                        // Bind parameters
    .transform(TargetClass::class.java)  // Map to target type
    .filter { predicate }                // Client-side filtering
    .map { transformation }              // Transform results
    .limit(n)                            // Limit results
    .skip(n)                             // Skip first n results
```

### ConnectionProperties

```kotlin
data class ConnectionProperties(
    val host: String = "localhost",
    val port: Int = 7687,
    val username: String? = null,
    val password: String? = null,
    val database: String? = null,
    val encrypted: Boolean = false
)
```

### Binding Objects

Use `bindObject()` to serialize objects to Neo4j-compatible types using Jackson:

```kotlin
// Automatically converts Enums to String, UUID to String, Instant to ZonedDateTime
val task = Task(id = "1", priority = Priority.HIGH, status = Status.OPEN, dueDate = Instant.now())
manager.execute(
    QuerySpecification
        .withStatement("CREATE (t:Task) SET t = $props")
        .bindObject("props", task)
)
```

The Neo4j ObjectMapper automatically:
- Converts `Enum` to `String`
- Converts `UUID` to `String`
- Converts `Instant` to `ZonedDateTime`
- Converts `Date` to `ZonedDateTime`
- Includes null values by default (so a bound map/object can carry explicit nulls)
- Ignores unknown properties when deserializing

To exclude nulls on specific properties, use `@JsonInclude(JsonInclude.Include.NON_NULL)`.

> This governs the low-level `PersistenceManager` binding only. Whether a null field **clears** a
> property on an object-manager `save`/`saveAll` is governed by
> [`NullPolicy`](#null-write-policy-nullpolicy) (default `IGNORE` — nulls are left untouched).

## Supported Engines

Drivine4j supports multiple graph database engines from the same codebase. Switch engines with a one-line YAML change — your models, queries, and DSL code stay the same.

| Engine | Type | Transactions | Collection Sort | Auth |
|--------|------|-------------|----------------|------|
| Neo4j 5.x | `NEO4J` | Full ACID | APOC (default) or CALL subquery | Basic (user/pass) |
| Neo4j 4.x | `NEO4J` | Full ACID | APOC (required) | Basic (user/pass) |
| FalkorDB | `FALKORDB` | Passthrough (no multi-statement) | CALL subquery | None |
| Amazon Neptune | `NEPTUNE` | Full ACID | CALL subquery | IAM SigV4 or None (tunnel) |
| Memgraph | `MEMGRAPH` | Full ACID | CALL subquery | Basic (user/pass) or None |

### Neo4j

The default engine. Works out of the box with Testcontainers or a local instance:

```yaml
database:
  datasources:
    graph:
      type: NEO4J
      host: localhost
      port: 7687
      user-name: neo4j
      password: your-password
      database-name: neo4j
```

### FalkorDB

FalkorDB is an in-memory graph database built on Redis. It offers extremely fast query execution but does not support multi-statement transactions.

```yaml
database:
  datasources:
    graph:
      type: FALKORDB
      host: localhost
      port: 6379
      database-name: mygraph
```

**Transactions:** FalkorDB does not support multi-statement transactions. `@Transactional` methods work but each query executes and commits independently. By default, `startTransaction()` logs a debug message and `rollbackTransaction()` logs a warning. To enforce strict no-transaction usage (throw on `@Transactional`), set:

```yaml
      falkor-db-transaction-mode: STRICT   # default: WARN
```

**Jedis:** the FalkorDB client needs Jedis 8. Spring Boot's dependency management sets an older one (6.0.0 on Boot 3.5, 7.4.1 on Boot 4.1), and a managed version in your build wins over the one Drivine declares, so a FalkorDB datasource fails at start-up with `NoSuchMethodError` on `DefaultJedisClientConfig.Builder.autoNegotiateProtocol`. Set it yourself:

```xml
<!-- Maven, with the Spring Boot parent -->
<properties>
    <jedis.version>8.0.1</jedis.version>
</properties>

<!-- Maven, importing a BOM: declare it BEFORE the import -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
            <version>8.0.1</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```groovy
// Gradle, with the Spring Boot plugin
ext['jedis.version'] = '8.0.1'
```

**Known limitations:**
- Nested pattern comprehensions return NULL ([FalkorDB#1888](https://github.com/FalkorDB/FalkorDB/issues/1888)) — Drivine works around this with CALL subquery prologs
- `collect()` on null includes null maps ([FalkorDB#1889](https://github.com/FalkorDB/FalkorDB/issues/1889)) — Drivine filters with `CASE WHEN IS NOT NULL`

CASCADE `DELETE_ORPHAN` is supported on current FalkorDB ([FalkorDB#1890](https://github.com/FalkorDB/FalkorDB/issues/1890) is fixed in the graph module Drivine tracks); older builds lacking that fix are not supported for orphan delete.

### Amazon Neptune

Neptune is AWS's managed graph database. Drivine connects via the Bolt protocol with two authentication modes:

**IAM SigV4 authentication (recommended for production):**

```yaml
database:
  datasources:
    graph:
      type: NEPTUNE
      host: your-cluster.us-east-1.neptune.amazonaws.com
      port: 8182
      region: us-east-1
      neptune-auth: IAM
```

Requires AWS credentials available via the standard AWS credential chain (`~/.aws/credentials`, environment variables, IAM role, etc.) and the AWS SDK on the classpath:

```gradle
implementation 'software.amazon.awssdk:auth:2.31.3'
implementation 'software.amazon.awssdk:regions:2.31.3'
implementation 'software.amazon.awssdk:http-client-spi:2.31.3'
```

Tokens are automatically refreshed before expiry.

**No authentication (SSH tunnel for development):**

```yaml
database:
  datasources:
    graph:
      type: NEPTUNE
      host: localhost
      port: 8182
      neptune-auth: NONE
```

Use with an SSH tunnel to a Neptune cluster that has IAM auth disabled:
```bash
ssh -N -o ServerAliveInterval=60 -L 8182:your-cluster.neptune.amazonaws.com:8182 ec2-user@bastion-ip
```

**Known limitations:**
- No `date()` function — use string dates or `datetime()` for temporal properties
- No list/array property values — annotate collection fields with `@JsonPacked` to store as JSON strings
- `collSortMaps` uses `{key: 'prop', order: 'asc'}` syntax (differs from APOC)

### Memgraph

Memgraph is an in-memory, Bolt-compatible graph database with Neo4j-compatible Cypher. Drivine reuses the Neo4j driver stack — only the Cypher dialect differs (no APOC; collection sorting via `CALL` subqueries).

```yaml
database:
  datasources:
    graph:
      type: MEMGRAPH
      host: localhost
      port: 7687
      user-name: ""        # Memgraph accepts empty credentials by default
      password: ""
```

**Notes:**
- Full ACID transactions (`startTransaction` / `commit` / `rollback` all work as expected)
- `EXISTS { pattern }` and nested pattern comprehensions are supported, so `@GraphView` queries use the same inline projector as Neo4j
- No APOC — use MAGE for procedures; collection sorting uses CALL subqueries by default
- No `CascadeType.DELETE_ORPHAN`: Memgraph cannot use `EXISTS` inside `WITH`, so a save or delete that asks for it throws `UnsupportedOperationException`. On `StatelessGraphObjectManager`, `Replace(field, removedTargets = DELETE_UNREFERENCED)` works on Memgraph
- For MAGE algorithms or Memgraph Lab, switch the image to `memgraph/memgraph-platform`

### @JsonPacked Annotation

For engines that don't support list property values (Neptune), annotate collection fields to transparently serialize as JSON strings:

```kotlin
@RelationshipFragment
data class WorkHistory(
    val role: String,
    @JsonPacked val tags: List<String>? = null,
    val target: Organization
)
```

On write: `["backend", "senior"]` is stored as the string `'["backend","senior"]'`. On read: the JSON string is deserialized back to `List<String>`. Works across all engines.

### @GraphProperty Annotation

Overrides the **on-disk property name** for a fragment field, decoupling the stored graph property from
the Kotlin/Java field name:

```kotlin
@NodeFragment(labels = ["Chunk"])
data class ChunkNode(
    @NodeId val id: String,
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
)
```

The field is `containerSectionId` everywhere in your code and the DSL (`query.containerSectionId`), but
it's stored/matched as `container_section_id` in the graph. The mapping is applied on save, load, the
generated query DSL, and index creation. It also feeds model-aware key resolution — `field("containerSectionId")`
and `field("container_section_id")` both resolve to the on-disk name.

### @PropertyBag Annotation

Maps an open `Map<String, *>` field to a set of **flat, prefixed node properties** — round-tripped on
save and load. Unlike `@JsonPacked` (one opaque JSON string), each entry becomes a real property, so
it's visible, filterable, and indexable. Mirrors Spring Data Neo4j's `@CompositeProperty` (available
as an alias).

```kotlin
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId val id: String,
    val text: String,
    @PropertyBag val metadata: Map<String, Any?> = emptyMap(),   // -> metadata.<key> properties
)
```

`metadata = {"source": "wiki", "score": 3}` persists as `metadata.source = "wiki"`,
`metadata.score = 3` alongside `id`/`text`. Use `prefix` to decouple the graph namespace from the
field name and `delimiter` to change the separator; a fragment may carry several bags.

- **Values** must be storable Neo4j primitives or homogeneous arrays (String, Number, Boolean,
  temporal, or arrays/lists thereof) — a nested map/object throws an `IllegalArgumentException`
  naming the key.
- **Stale keys** (removing an entry then saving) are removed under `NullPolicy.CLEAR`: the keys the
  node holds are read, and those the map no longer has are removed. The default `IGNORE` is a
  merge-patch and leaves them.
- **Read asymmetry**: `Map<String, Any?>` reads back driver-mapped types (an `Int` written returns as
  `Long`).
- **Filter by key** in the type-safe DSL — composes on the load path and inside `loadNearest` /
  `loadMatching`:

```kotlin
loadAll<PropositionView> { where { proposition.metadata.key("source") eq "wiki" } }
// -> WHERE proposition.`metadata.source` = $p
```

For a **runtime** key (not known at compile time), use the dynamic
[`property(path)` / `field(key)`](#dynamic--runtime-key-predicates) predicates instead of `.key(...)`.

Works across Neo4j, FalkorDB, and Memgraph.

**A flat bag** has no prefix: each entry is stored under its bare key, and the bag reads back every
property that no declared field and no other bag accounts for. It fits a node whose properties are
open-ended and were never namespaced. A fragment may have one, and an entry whose key is a declared
field's property is rejected at save.

```kotlin
@NodeFragment(labels = ["Thing"])
data class ThingNode(
    @NodeId val id: String,
    val name: String,
    @PropertyBag(flat = true) val properties: Map<String, Any?> = emptyMap(),   // -> <key> properties
)
```

### @NodeLabels Annotation

Maps a set-valued field to the node's **labels**, beyond the fixed ones the fragment declares. The
field is filled from the node's labels on load and written as real labels on save, so
`MATCH (n:Admin)` finds a node whose field holds `Admin`.

```kotlin
enum class Role { Admin, Reviewer, Author }

@NodeFragment(labels = ["Person"])
data class PersonNode(
    @NodeId val id: String,
    @NodeLabels val roles: Set<Role>,       // a closed set: only these labels are the field's
)

@NodeFragment(labels = ["Thing"])
data class ThingNode(
    @NodeId val id: String,
    @NodeLabels val labels: Set<String>,    // an open set: any label
)
```

Saving follows `NullPolicy`, as every field does:

| | `IGNORE` (default) | `CLEAR` |
|---|---|---|
| Labels in the field | added | added |
| Labels that are the field's and not in it | left | removed |

Which labels are the field's depends on its element type:

- **An enum**: its members. It reads only those, and a label outside the enum is ignored, never an
  error. An enum may not name one of the fragment's own labels.
- **`String`**: the labels it has itself written, which it records on the node in the list property
  `__drivine.labels.<fieldName>`. Because the record is on the node, a `CLEAR` save removes the same
  labels whichever process loaded the object, or if none did. It reads every label the node carries,
  so an object loaded and then saved under `CLEAR` takes on all the labels it read.

A label that is not the field's — the fragment's own, another fragment's, one written by other code —
is never removed. Names beginning `__drivine.` are reserved: a field or bag may not use them.

A fragment may carry one `@NodeLabels` field. `saveAll` saves such a fragment one statement per
object. On a relationship target in a `@GraphView`, a `String` field adds and does not remove.

### Relationships of a Runtime Type

A `@GraphView` declares its relationships, type included. When the type is data — a graph whose
relationship types are not known when the model is written — use `graphObjectManager.edges`:

```kotlin
val lyre = nodeRef<ThingNode>("lyre")
val ada = nodeRef<PersonNode>("ada", "Author")       // must also carry the label Author

graphObjectManager.edges.relate(lyre, ada, type = "OWNED_BY", properties = mapOf("since" to 1990))
graphObjectManager.edges.relate(lyre, ada, type = "PLAYED_BY", mode = RelateMode.CREATE)

val owners: List<PersonNode> = graphObjectManager.edges.loadRelated(lyre, "OWNED_BY", Direction.OUTGOING)

graphObjectManager.edges.unrelate(lyre, ada, "PLAYED_BY")     // every PLAYED_BY from lyre to ada
graphObjectManager.edges.unrelateAll(lyre, "OWNED_BY")        // every OWNED_BY that leaves lyre
```

- A `NodeRef` names a stored node by fragment class and id. Both ends are matched, never created:
  if either is absent, or lacks a label the reference names, nothing is written and `relate`
  returns false. Neither node is loaded and neither's properties are touched.
- `RelateMode.MERGE` (the default) keeps at most one relationship of the type between the two nodes
  in that direction and sets its properties; `CREATE` makes another each time.
- `loadRelated` returns each related node once, as the target fragment.
- `unrelate` and `unrelateAll` remove relationships and return how many. No node is deleted.
- `relate`, `unrelate` and `unrelateAll` give the nodes at both ends a new relationship token in
  their stamp when they change something, so a `Replace` from an object loaded before is refused.
  A `relate` that finds the relationship there as it is, and an `unrelate` that finds none, mark neither.

### Cypher Dialect

Each engine uses a Cypher dialect that controls query generation. The dialect is auto-detected from the database type but can be overridden:

```yaml
      cypher-dialect: NEO4J_5    # NEO4J_5, NEO4J_4, FALKORDB, NEPTUNE, MEMGRAPH
```

## Schema Management (Indexes & Constraints)

Drivine manages vector indexes, full-text indexes, range indexes, and uniqueness constraints across Neo4j, Memgraph, and FalkorDB — with idempotent, drift-aware `ensure` semantics and engine differences (DDL syntax, introspection, FalkorDB's Redis-command constraints) handled for you. Opt-in: declare nothing, pay nothing.

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

persistenceManager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
persistenceManager.constraints.ensure(UniquenessConstraintSpec("Membership", listOf("tenantId", "userId")))
```

`ensure` returns an `EnsureResult`:

| Result | Meaning |
|---|---|
| `Created` | Nothing existed; the item was created |
| `AlreadyMatching` | A matching item exists; nothing changed |
| `Drift` | An item exists with a **different shape** (e.g. vector dimensions changed). Nothing changed — call `recreate(spec)` to replace it (destructive) |
| `Violation` | Constraint only: existing data violates it. Includes a bounded sample of the conflicting values |
| `Recreated` | From an explicit `recreate(spec)` — old item dropped, new one created |

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

### Version-triggered rebuild

Drift detection only catches changes introspection can *see* (dimensions, similarity, properties). A change it can't see — swapping the embedding model for one with the **same dimensions** — leaves stale vectors behind. Tag a catalog with a version token to force a one-time rebuild when that token changes:

```kotlin
@Bean
fun chunkSchema(embeddingService: EmbeddingService) = SchemaCatalog.of(
    VectorIndexSpec("Chunk", "embedding", embeddingService.dimensions),
).withVersion(embeddingService.modelId)   // bump this → recreate once
```

How it works: the last-applied token is stored in a reserved `_DrivineSchema` marker node per database (portable across all three engines). On `enforce()`, a **changed** token drops and recreates that catalog's items, then records the new token; a first-ever token is *adopted* without recreating, so turning versioning on never nukes a healthy schema.

> Recreating a vector index rebuilds it from the stored embedding *properties* — it does **not** re-embed. After a model swap, re-embed the nodes first (same dimensions → the index even auto-updates), then `enforce()`/`recreateAll()` for the structural half. Drivine manages schema, not your embeddings.

### Configuration

```yaml
drivine:
  schema:
    enabled: true              # master switch for startup initialization
    mode: FAIL_FAST            # FAIL_FAST (default) or WARN on drift/violations
    recreate-on-drift: false   # destructive: rebuild items whose shape changed
    recreate-on-startup: false # destructive: rebuild everything every startup
    violation-sample-size: 10  # conflicting rows sampled on constraint violations
```

### Per-engine notes

| | Neo4j | Memgraph | FalkorDB |
|---|---|---|---|
| Vector indexes | `CREATE VECTOR INDEX … IF NOT EXISTS` | `WITH CONFIG {…}`, uSearch metrics | `OPTIONS {…}`, unnamed |
| Full-text indexes | `CREATE FULLTEXT INDEX … ON EACH […]` (+ analyzer) | `CREATE TEXT INDEX … ON :L(props)` | `CREATE FULLTEXT INDEX FOR (n:L) ON (n.prop)`, per-property, unnamed |
| Range indexes | Named, composite supported | Label-property style | Per-label coverage; extended incrementally |
| Uniqueness | `REQUIRE … IS UNIQUE` | `ASSERT … IS UNIQUE` | **Redis command** `GRAPH.CONSTRAINT` (not Cypher) — Drivine issues it at driver level, auto-creates the required backing index, and polls the asynchronous build |
| Item names | Yes | Vector only | No |

Neptune and generic openCypher have no schema management support — operations fail loudly rather than silently no-op.

## Multi-Database Support

```kotlin
@Configuration
class MultiDbConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        return DataSourceMap(mapOf(
            "analytics" to ConnectionProperties(
                host = "analytics.neo4j.com",
                database = "analytics"
            ),
            "users" to ConnectionProperties(
                host = "users.neo4j.com",
                database = "users"
            )
        ))
    }
}

@Component
class AnalyticsRepository @Autowired constructor(
    @Qualifier("analytics") val manager: PersistenceManager
) { /* ... */ }

@Component
class UserRepository @Autowired constructor(
    @Qualifier("users") val manager: PersistenceManager
) { /* ... */ }
```

## Testing

```kotlin
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonRepositoryTest @Autowired constructor(
    private val repository: PersonRepository
) {
    @Test
    fun `should find person by city`() {
        val results = repository.findByCity("New York")
        assertThat(results).isNotEmpty
    }
}
```

### Automated Test Configuration (Testcontainers + Local Dev)

Drivine provides `@EnableDrivineTestConfig` for seamless test setup that works in both local development and CI:

**1. Define datasource in `application-test.yml`:**

```yaml
database:
  datasources:
    neo:
      host: localhost
      port: 7687
      username: neo4j
      password: password
      type: NEO4J
      database-name: neo4j
```

**2. Use `@EnableDrivineTestConfig` in your test configuration:**

```kotlin
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
class TestConfig
```

**3. Control behavior with environment variable:**

```bash
# Use local Neo4j (for development - fast, inspectable)
export USE_LOCAL_NEO4J=true
./gradlew test

# Use Testcontainers (for CI - isolated, default)
./gradlew test  # USE_LOCAL_NEO4J defaults to false
```

**What happens automatically:**

- **Local Mode** (`USE_LOCAL_NEO4J=true`): Uses your application-test.yml settings as-is, connects to your local Neo4j
- **CI Mode** (default): Starts a Neo4j Testcontainer automatically and overrides host/port/password from your properties

**Benefits:**
- ✅ One configuration works for both local dev and CI
- ✅ Zero boilerplate - no manual container setup
- ✅ Fast local development with real Neo4j
- ✅ Reliable CI with Testcontainers
- ✅ Easy debugging - set `@Rollback(false)` and inspect your local DB

### Manual TestContainers Setup

If you need more control, you can still configure Testcontainers manually:

```kotlin
@Configuration
@EnableDrivine
class TestConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        val props = ConnectionProperties(
            host = extractHost(DrivineTestContainer.getConnectionUrl()),
            port = extractPort(DrivineTestContainer.getConnectionUrl()),
            userName = DrivineTestContainer.getConnectionUsername(),
            password = DrivineTestContainer.getConnectionPassword(),
            type = DatabaseType.NEO4J,
            databaseName = "neo4j"
        )
        return DataSourceMap(mapOf("neo" to props))
    }

    private fun extractHost(boltUrl: String): String =
        boltUrl.substringAfter("bolt://").substringBefore(":")

    private fun extractPort(boltUrl: String): Int =
        boltUrl.substringAfter("bolt://").substringAfter(":").toIntOrNull() ?: 7687
}
```

## Java Query DSL

Drivine4j provides a fluent, type-safe query API for Java that mirrors the Kotlin DSL capabilities.

### Basic Usage

```java
import org.drivine.query.dsl.JavaQueryBuilderKt;

List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .loadAll();
```

The pattern is:
1. `query(graphObjectManager, GraphViewClass)` - Start a query
2. `filterWith(QueryDslClass)` - Specify the generated DSL for type-safe filtering
3. Chain `where()`, `whereAny()`, `orderBy()` as needed
4. Terminate with `loadAll()`, `loadFirst()`, or `deleteAll()`

### Available Operators

**Comparison:**
```java
.where(dsl -> dsl.getIssue().getId().isEqualTo(100L))      // equals
.where(dsl -> dsl.getIssue().getId().isNotEqualTo(100L))   // not equals
.where(dsl -> dsl.getIssue().getId().isGreaterThan(100L))  // greater than
.where(dsl -> dsl.getIssue().getId().isAtLeast(100L))      // greater than or equal
.where(dsl -> dsl.getIssue().getId().isLessThan(100L))     // less than
.where(dsl -> dsl.getIssue().getId().isAtMost(100L))       // less than or equal
```

**String Operations:**
```java
.where(dsl -> dsl.getIssue().getTitle().hasSubstring("Bug"))
.where(dsl -> dsl.getIssue().getTitle().hasPrefix("Feature"))
.where(dsl -> dsl.getIssue().getTitle().hasSuffix("needed"))
```

**Null Checks:**
```java
.where(dsl -> dsl.getIssue().getBody().isAbsent())
.where(dsl -> dsl.getIssue().getBody().isPresent())
```

**Collections:**
```java
.where(dsl -> dsl.getIssue().getState().isIn(Arrays.asList("open", "reopened")))
```

### Multiple Conditions (AND)

Chain multiple `where()` calls for AND logic:

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .where(dsl -> dsl.getIssue().getLocked().isEqualTo(false))
    .where(dsl -> dsl.getIssue().getId().isAtLeast(100L))
    .loadAll();
// WHERE issue.state = 'open' AND issue.locked = false AND issue.id >= 100
```

### OR Conditions

Use `whereAny()` for OR logic:

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .whereAny(dsl -> Arrays.asList(
        dsl.getIssue().getState().isEqualTo("open"),
        dsl.getIssue().getState().isEqualTo("reopened")
    ))
    .loadAll();
// WHERE (issue.state = 'open' OR issue.state = 'reopened')
```

Combine AND and OR:

```java
// locked=false AND (state='open' OR state='reopened')
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getLocked().isEqualTo(false))
    .whereAny(dsl -> Arrays.asList(
        dsl.getIssue().getState().isEqualTo("open"),
        dsl.getIssue().getState().isEqualTo("reopened")
    ))
    .loadAll();
```

### Ordering

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .orderBy(dsl -> dsl.getIssue().getId().descending())
    .loadAll();
```

### Load First

Get only the first matching result:

```java
RaisedAndAssignedIssue result = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .orderBy(dsl -> dsl.getIssue().getId().descending())
    .loadFirst();  // Returns null if no matches
```

### Polymorphic Filtering with instanceOf

Filter by `@NodeFragment` subtype using `instanceOf()`:

```java
import org.drivine.sample.fragment.AnonymousWebUser;
import org.drivine.sample.fragment.RegisteredWebUser;

// Filter to only anonymous web users
List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
    .loadAll();

// Combine with other conditions
List<GuideUserWithPolymorphicWebUser> activeAnonymous = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .where(dsl -> dsl.getCore().getGuideProgress().isAtLeast(10))
    .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
    .loadAll();

// Use in OR conditions
List<GuideUserWithPolymorphicWebUser> allUsers = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .whereAny(dsl -> Arrays.asList(
        dsl.getWebUser().instanceOf(AnonymousWebUser.class),
        dsl.getWebUser().instanceOf(RegisteredWebUser.class)
    ))
    .loadAll();
```

### Delete with DSL

```java
int deleted = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("closed"))
    .deleteAll();
```

### Java DSL Summary

The Java methods are named differently from their Kotlin counterparts (`isEqualTo` for `eq`, `ascending` for
`asc`, …). Kotlin's are context-parameter members that register themselves inside a `where`/`orderBy` block;
from Kotlin 2.3 two members sharing a name are ambiguous there, so each language gets its own. Renamed in
0.0.87 — see [0.0.87-java-dsl-names.md](docs/0.0.87-java-dsl-names.md) for the old → new table.

| Operation | Example |
|-----------|---------|
| Equality | `.isEqualTo("value")`, `.isNotEqualTo("value")` |
| Comparison | `.isGreaterThan(n)`, `.isAtLeast(n)`, `.isLessThan(n)`, `.isAtMost(n)` |
| Strings | `.hasSubstring("x")`, `.hasPrefix("x")`, `.hasSuffix("x")` |
| Null | `.isAbsent()`, `.isPresent()` |
| Collections | `.isIn(Arrays.asList(...))` |
| Type filter | `.instanceOf(SubtypeClass.class)` |
| AND | Chain multiple `.where()` |
| OR | `.whereAny(dsl -> Arrays.asList(...))` |
| Order | `.orderBy(dsl -> dsl.getProp().ascending())`, `.descending()` |
| Keyset cursor | `.seek(dsl -> List.of(dsl.getProp().cursorAfter(v)))` |

## Java Interoperability

### DSL Generation Note

The code generator (KSP) only processes **Kotlin source files**. For the best experience:
- Define your `@GraphView` classes in Kotlin to get the generated type-safe DSL
- Your `@NodeFragment` classes can be in Java or Kotlin
- At runtime, both Java and Kotlin classes work fully with the object manager

### Recommended Pattern

**Best Practice:** Define `@GraphView` classes in Kotlin, everything else can be Java.

```java
// Java fragments work great!
@NodeFragment(labels = {"Person"})
public class Person {
    @NodeId public UUID uuid;
    public String name;
    public String bio;
}
```

```kotlin
// Define GraphViews in Kotlin to get DSL generation
@GraphView
data class PersonContext(
    @Root val person: Person,  // References Java class!
    @GraphRelationship(type = "WORKS_FOR")
    val worksFor: List<Organization>
)
```

```java
// Use from Java with the fluent DSL API
List<PersonContext> results = JavaQueryBuilderKt
    .query(graphObjectManager, PersonContext.class)
    .filterWith(PersonContextQueryDsl.class)
    .where(dsl -> dsl.getPerson().getName().hasSubstring("Alice"))
    .loadAll();
```

### Summary

| Feature | Java Support | Notes |
|---------|-------------|-------|
| `@NodeFragment` | ✅ Full | Works identically in Java and Kotlin |
| `@RelationshipFragment` | ✅ Full | Works identically in Java and Kotlin |
| `@GraphView` runtime | ✅ Full | Loading, saving, polymorphism all work |
| Type-safe DSL | ✅ Full | Use `filterWith()` API from Java |
| `instanceOf()` | ✅ Full | Filter by `@NodeFragment` subtype |
| DSL generation | ⚠️ Kotlin only | Define `@GraphView` in Kotlin |
| Generic collections | ✅ Full | Java reflection handles `List<T>`, `Set<T>` |
| Polymorphic types | ✅ Full | Works with sealed classes or `@JsonSubTypes` |

## Migrating from GraphObjectManager

`GraphObjectManager` is deprecated in favour of `StatelessGraphObjectManager`. It still works, with the fixes and the stamping listed below, and is described in [docs/legacy-graph-object-manager.md](docs/legacy-graph-object-manager.md).

### Why

`GraphObjectManager` keeps a snapshot of every object it loads or saves, and a save writes only what differs from the snapshot. Whether a save was correct therefore depended on things you could not see from the call: whether this manager had loaded the object, whether it was still in the session, and whether anything else had written to the node since. Each of those produced a real bug:

- A node deleted by plain Cypher and saved again came back with only some of its properties.
- A relationship dropped from a list was removed or kept depending on whether the object had been evicted from the session.
- A view the manager had not loaded wrote a path field as a direct relationship.

A library should make the graph easier to work with, not require forensics to save an object safely. A `StatelessGraphObjectManager` save depends only on the object and the arguments you pass, and with a `@NodeStamp` field it is refused when another writer got there first.

### What you give up

- **Dirty-only writes are no longer automatic.** Ask for them with `update { }`, `only` or `except`.
- **Saving with `CascadeType.DELETE_ALL` has no equivalent.** Remove the relationships with `Replace`, then delete the nodes:

  ```kotlin
  val dropped = loaded.attachments - kept
  graphObjectManager.save(loaded.copy(attachments = kept), Replace(MessageView::attachments))
  dropped.forEach { graphObjectManager.delete<Attachment>(it.id) }
  ```
- **Saves have no interface.** `save`, `saveAll` and `update` are on `StatelessGraphObjectManager` itself. `GraphObjectOperations` covers loading, querying and deleting, so code that saves depends on the class.
- **Recompile.** Code compiled against 0.0.x must be compiled again against 0.1.0: the loading and query methods of `GraphObjectManager` are now declared on `GraphObjectOperations`.
- **Deprecation warnings.** `GraphObjectManager` and `GraphObjectManagerFactory.get()` are deprecated, so a build that treats warnings as errors needs the move or a suppression.
- **Generated DSL.** Generated query DSL extensions have the receiver `GraphObjectOperations`. Calls on a `GraphObjectManager` compile as before.

### What changes in your code

Loading, querying and deleting are the same: both managers implement `GraphObjectOperations`, and the generated query DSL works on either. Saves change as follows.

| With `GraphObjectManager` | With `StatelessGraphObjectManager` |
|---|---|
| `factory.get()` | `factory.stateless()` |
| `save(obj)` of a new object | `save(obj)` |
| load, change a field, `save` | `update(id) { it.copy(...) }`, or `save` of the loaded object |
| load, drop an item from a list, `save` | `update(id) { ... }`, or `save(obj, Replace(View::field))` |
| `save(obj, CascadeType.DELETE_ORPHAN)` | `save(obj, Replace(View::a, View::b, removedTargets = RemovedTargets.DELETE_UNREFERENCED))`, naming each relationship field |
| `save(obj, CascadeType.PRESERVE)` | `save(obj)` |
| `save(obj, CascadeType.DELETE_ALL)` | no equivalent |
| `clearSession()` | not needed |

- `DELETE_UNREFERENCED` deletes a removed target that nothing else refers to the way the field did: see [Relationships](#relationships). `DELETE_ORPHAN` deletes one with no relationship in either direction. A removed target that nothing points at, and that points at something itself, is deleted by the first through an outgoing field and kept by the second.
- Add a `@NodeStamp` field to each type you load, change and save.
- A `@GraphPath`, `@Count` or `@Aggregate` field is read-only with either manager, whether or not it is declared `@ReadOnly`: it is loaded and never written. So is a list of fragments read over several hops (`maxDepth` above 1): a save used to write each item as a direct relationship from the root.
- A cascading delete no longer follows a read-only field. Before, `delete(id, View::class.java, CascadeType.DELETE_ALL)` deleted the nodes of a `@ReadOnly` field, and for a `@GraphPath` field the nodes a direct relationship of the first hop's type led to.
- A view whose root id is stored under another name (`@NodeId @GraphProperty("…")`) is now loaded, updated and deleted by id. Before, the lookup used the field's name and found nothing.
- A view save through an `UNDIRECTED` field no longer adds a second relationship beside one stored towards the root.
- Nodes saved by either manager, and the node at each end of a relationship either manager or `edges` writes or removes, now carry the property `__drivine.stamp`, whether or not their type declares a `@NodeStamp` field. It cannot be turned off. A test that compares a node's whole property map, and Cypher that copies one node's properties to another, will see it. Cypher of your own that returns every property of a node, such as `properties(n)`, returns it too. That is harmless; names beginning `__drivine.` are Drivine's own, should your code need to tell them from its own.

### Relationships saved through an `INCOMING` field

Before 0.1.0 a view save wrote every relationship from the view's root to the target, whatever direction the field declared. A field declared `Direction.INCOMING` was therefore stored pointing the wrong way, and the view did not load it back. Saves are fixed in 0.1.0. Relationships already stored need turning round, and `RelationshipDirectionRepair` does that:

```kotlin
val repair = RelationshipDirectionRepair(persistenceManager)

val findings = repair.report(PersonView::class.java, IssueView::class.java)   // every view of the model; changes nothing
findings.forEach { println("${it.view.simpleName}.${it.field}: ${it.wrongWay} wrong way, ${it.rightWay} right way. ${it.ambiguity ?: ""}") }

findings.filter { it.ambiguity == null }.forEach { repair.repair(it) }
```

- Back the store up first, and run the repair once nothing built on a version before 0.1.0 is still writing: what such a writer saves afterwards points the wrong way again.
- `report` gives one finding for each `INCOMING` relationship field, in the views you pass and the views nested in them. A field you have since declared `@ReadOnly` has one too, because the old save wrote it as it wrote every other. A list read over several hops (`maxDepth` above 1) has none. `wrongWay` counts the relationships pointing from a root to a target, which is what the old save wrote.
- A relationship between two nodes that each carry the labels of both the root and the target is counted in `eitherWay` instead, and in neither `wrongWay` nor `rightWay`: either node can be the root, so it may already point the right way. `repair` never turns one of those, forced or not. Where a view rooted at `Organization` reads `PART_OF` from a `Company`, a relationship between two nodes labelled `Company:Organization` is one.
- `repair` turns round the relationships counted in `wrongWay`, keeping their properties. Where one already points the right way between the same two nodes, the two become one: the one that already pointed the right way keeps every property it has, and takes from the other the properties it lacks. Nothing in the store says which of the two is the newer, so where both have a property with different values, the value on the one turned round is lost. `collisions` counts those relationships in the report, before anything is changed. Several that point the wrong way between the same two nodes become one as well, with the properties of them all. A relationship turned round is not matched again, so running the repair a second time changes nothing.
- The node at each end of a relationship the repair deals with gets a new relationship token in its stamp, so a `Replace` of an object loaded before the repair is refused and does not remove what the repair turned round.
- `repair(finding, force, batchSize)` runs one statement for each batch, 10,000 relationships by default, and returns how many wrong-way relationships it dealt with: each one it turned round and each one it dropped for another between the same two nodes. Relationships that already pointed the right way are not counted. Run it outside a transaction, so each batch is committed as it finishes.
- A finding is **ambiguous** when another of the views declares the same relationship pointing away from the root, so those relationships may be meant, or has a `@GraphPath` field with a hop that is stored that way, so they may be hops of the path. A read-only field counts, and so does a view rooted at a subtype: its nodes carry the root's labels too. `repair` refuses an ambiguous finding unless you pass `force = true`.
- Where a view has a `@GraphPath` field as well, run `PathRelationshipReport` (below) first and deal with what it finds. A relationship the old save wrote for a path field also points away from the root, and turned round it would be loaded by an `INCOMING` field of the same type.
- A field whose root and target can be the same nodes (a person who follows a person, or a field with a root or a target whose fragment has no label) cannot be repaired by the tool: nothing tells a relationship written the wrong way from one that is meant. `finding.repairable` is false, and `force` does not change that. Repair those with Cypher that knows your data.
- The counts are by label and type. The tool cannot tell a relationship a save wrote from one made some other way between the same kinds of node.
- Run it once, as a migration. A relationship that was added with Cypher or `edges.relate` in the direction the field reads was never wrong and is left alone.

### A path field that was written as a relationship

Before 0.1.0, saving a view the manager had not loaded wrote a `@GraphPath` field as one direct relationship, of the first hop's type, from the root to each node the field held. Saves no longer write a path field. `PathRelationshipReport` counts what was left behind:

```kotlin
PathRelationshipReport(persistenceManager).report(ClaimEmployers::class.java, ClaimView::class.java).forEach {
    println("${it.view.simpleName}.${it.field}: ${it.direct} direct ${it.type} relationships. ${it.ambiguity ?: ""}")
    println("  to remove them: ${it.removalStatement}")
}
```

It reports and does not remove. A direct relationship of that type to that kind of node is often meant. A finding is ambiguous, and says why, when one of the views declares that relationship (a read-only field counts), or when a hop of this path or of another `@GraphPath` field in the views is stored as that relationship: of the same type, from nodes that can be the root's to nodes that can be the ones the path ends at. A hop that names no label reaches any node. Two kinds of node can be the same when one has every label of the other, or when the views name a kind that has the labels of both. So pass every view of the model: a path field the report is not given cannot make a finding ambiguous. Each finding carries the Cypher that would remove what it counted, for you to run once you have looked. That statement is not batched, and it removes every relationship counted, whether a save wrote it or not.

### How we know nothing else changed

Every test of `GraphObjectManager` in this repository has a mirror that runs on `StatelessGraphObjectManager`, but for four that save with `CascadeType.DELETE_ALL`, which has no equivalent. A comparison test runs the same scenarios through both managers on Neo4j, FalkorDB and Memgraph and compares the graphs they leave: for flat targets, relationships with properties and nested views, with and without another writer in between, they match.

## Building from Source

```bash
# Run tests
./gradlew test

# Build library
./gradlew build

# Publish to local Maven (~/.m2/repository)
./gradlew publishToMavenLocal
```

## License

Apache License 2.0

## Links

- GitHub: https://github.com/liberation-data/drivine4j
- Issues: https://github.com/liberation-data/drivine4j/issues
