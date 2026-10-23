<a href="https://drivine.org/"><img src="https://raw.githubusercontent.com/liberation-data/drivine/master/website/public/images/splash.jpg" alt="Drivine: graph database client for TypeScript, Python and the JVM"></a>

# Drivine4j

[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**Docs: [drivine.org](https://drivine.org/jvm/)**, for Kotlin & Java, TypeScript, and Python.

A graph database client library for Java and Kotlin supporting **Neo4j**, **FalkorDB**, **Amazon Neptune**, **Memgraph**, **Oracle** (SQL/PGQ) and **Postgres** (plain SQL, with pgvector for vector search) with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach)
2. **Object manager** (`StatelessGraphObjectManager`) - High-level API with annotated models and type-safe DSL.

Drivine4j is the graph database client library for [Embabel](https://hub.embabel.com) - Agentic AI for the JVM. 

> **On Node.js or Python?** [Drivine](https://github.com/liberation-data/drivine) (`npm install @liberation-data/drivine`)
> and [drivine-python](https://github.com/liberation-data/drivine-python) (`pip install drivine`) are drivine4j's
> TypeScript and Python twins, with the same object manager, query DSL, schema management and engines. The three share
> a storage format: a store written from any of them is read, stamped and checked alike by the others.

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
    implementation("org.drivine:drivine4j:0.2.0")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'org.drivine:drivine4j:0.2.0'
}
```

#### Maven
```xml
<dependency>
    <groupId>org.drivine</groupId>
    <artifactId>drivine4j</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Spring Boot Starter

For a Spring application, the starter brings the core library and the Spring wiring (see [Configuration](#1-configuration)):

```kotlin
dependencies {
    implementation("org.drivine:drivine4j-spring-boot-starter:0.2.0")
}
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
    implementation("org.drivine:drivine4j:0.2.0")
    ksp("org.drivine:drivine4j-codegen:0.2.0")
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
                    <version>0.2.0</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

**Note:** Maven support for KSP uses the third-party [kotlin-maven-symbol-processing](https://github.com/Dyescape/kotlin-maven-symbol-processing) extension.

## Quick Start

### 1. Configuration

There are two ways to set Drivine up in Spring: let the starter configure it, or enable it yourself.

**With Spring Boot auto-configuration.** Put the starter on the classpath and name a datasource:

```yaml
database:
  datasources:
    neo:
      type: NEO4J
      host: localhost
      port: 7687
      user-name: neo4j
      password: password
      database-name: neo4j
```

That's all. The starter provides the `DatabaseRegistry`, `PersistenceManagerFactory`,
`GraphObjectManagerFactory`, `SchemaManager` and Drivine's transaction manager. A `DataSourceMap` bean of your own
also counts as a datasource, in place of the properties. With neither, the starter adds nothing, so it is safe on any
classpath. Every bean backs off when you define your own (the startup schema run is switched off with `drivine.schema.enabled=false` instead), and `spring.autoconfigure.exclude=org.drivine.autoconfigure.DrivineAutoConfiguration`
turns the starter off.

Each datasource needs a `type`, here and with `@EnableDrivinePropertiesConfig` below. A name under `database.datasources` with properties but no `type` fails startup,
naming the datasource and the properties found for it, so a misspelt or partial entry isn't silently dropped.

**Explicitly, with `@EnableDrivine`.** Where auto-configuration doesn't run (a plain `@Configuration` context, or a test
that names its classes), or where you'd rather the wiring be visible, enable Drivine on a configuration class and give it a
`DataSourceMap`:

```kotlin
@Configuration
@EnableDrivine
class AppConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        val props = ConnectionProperties(
            type = DatabaseType.NEO4J,
            host = "localhost",
            port = 7687,
            userName = "neo4j",
            password = "password",
            databaseName = "neo4j"
        )
        return DataSourceMap(mapOf("neo" to props))
    }
}
```

or add `@EnableDrivinePropertiesConfig` beside `@EnableDrivine` to build the `DataSourceMap` from the properties above.
The two ways combine: an application that has `@EnableDrivine` and the starter gets each bean once.

Either way, you ask the factories for the managers you use:

```kotlin
@Bean
fun neoManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("neo")
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
>
> Upgrading from 0.0.x? [docs/0.1.0-stateless-object-manager.md](docs/0.1.0-stateless-object-manager.md) lists what changed and what to do about data saved before it.

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

A node of the ordered hop that has no value for the property comes last, whichever way the path is read; an empty text is a value, and the lowest text. Targets whose first values are equal come in no set order. The property should hold values of one type: every backend reads the order the same way where it does, and a text of digits such as `'0100'` is ordered as text. Where it holds texts on some nodes and numbers on others, Neo4j, FalkorDB and Oracle read every text before every number, lowest first, and Memgraph refuses to compare them.

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
graphObjectManager.loadMatching<ChunkNode>("graph databases", topK = 20) {
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

// to filter a subtype by its OWN property, pass that subtype's DSL
graphObjectManager.loadAll<ChunkNode, _>(ChunkNodeQueryDsl.INSTANCE) {
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
properties, and is refused under `seek`. See [docs/0.1.7-order-by-expression.md](docs/0.1.7-order-by-expression.md).

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
- A `sum` or an `avg` of a `Double` is decimal arithmetic, so 0.1 + 0.2 is 0.3 there and 0.30000000000000004 on the graph engines.
- A text key is ordered by the session's `NLS_SORT`, which is binary unless it has been set otherwise.

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

`from` is the object as you loaded it, when what you save is a changed copy of it. The save is then checked against the stamp `from` carries, whatever stamp the copy carries, as `update` checks against what it loaded. On Neo4j it is also checked against each field of `from`'s root: see [when a save is refused](#when-a-save-is-refused). It is refused before anything is written if `from` is of another class, has another id, carries no stamp, or is the object saved itself: an object changed in place no longer says what was loaded, so save a changed copy (a copy may share an unchanged root, as `view.copy(reviewers = …)` does). From Java, `save(edited, loaded)`.

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

See [docs/0.1.7-create-if-absent.md](docs/0.1.7-create-if-absent.md) for why `create` returns null and how each engine makes the node only if it is absent.

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

Persist a collection in one group of statements. Within an ambient `@Transactional` the statements join it; otherwise they run together in a single transaction, and a failure on any item rolls the whole call back.

On Neo4j, FalkorDB and Memgraph, a batch of fragments alone, of any number of labels, is **one statement** where each fragment can be written as a row of an `UNWIND` and there are no more than 1,000 of them: it is applied whole on any of them, FalkorDB included. On Oracle a batch of fragments is one block for each 500 of them. Each view is a statement of its own, and so is a fragment with a `@PropertyBag` or a `@NodeLabels` field, or on FalkorDB one with a `@VectorIndex` field: the other fragments are still one statement. In it the classes go in the order of their names and the rows of each in the order of their ids, so two batches over the same nodes take their locks in the same order. FalkorDB has no multi-statement transactions, so a batch of more than one statement is not applied whole there: in the default `WARN` mode it runs statement by statement and the first such batch logs a warning, and with `falkorDbTransactionMode` set to `STRICT` it is refused with `NonAtomicBatchException` before anything is written (inside `@Transactional`, `STRICT` refuses the transaction itself, at its first statement). `PersistenceManager.atomicBatches` says which a store does. See [docs/0.1.7-atomic-save-all.md](docs/0.1.7-atomic-save-all.md).

```kotlin
val saved = graphObjectManager.saveAll(views)
val replaced = graphObjectManager.saveAll(views, Replace(PropositionView::mentions))
graphObjectManager.saveAll(chunks, only = setOf(Chunk::summary, Chunk::summaryModel))   // write just these fields of each
graphObjectManager.saveAll(chunks, except = setOf(Chunk::embedding))                    // write everything but these
```

- Fragments collapse into chunked `UNWIND … MERGE` statements (sub-linear round trips), with or without a `@NodeStamp` field. A view is saved by a statement of its own, as `save` does it.
- Heterogeneous collections are grouped by runtime class, and the returned list preserves input order.
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
- A refused save throws `StaleObjectException`: `getDeleted()` says whether the node is gone, `getFoundStamp()` gives the stamp it carries now, and `getBySameBatch()` says whether a `saveAll` refused itself.
- `Stamps.setClause("p")` and `Stamps.linksClause("p")` give the `SET` items for Cypher of your own.

#### Saves and transactions

- A save joins the transaction it is called in, Spring's `@Transactional` or `@DrivineTransactional`. Outside one it is its own.
- `StaleObjectException` is a `RuntimeException`, so a refusal that leaves a `@Transactional` method rolls the transaction back. It is not a `DrivineException`: a handler that catches only that does not catch it.
- A refused save has written nothing. What the transaction wrote before it is undone only if the transaction rolls back: code that catches the exception and carries on commits it.
- A checked save holds the node's write lock until the transaction commits, so another writer to the same node waits for it.
- A statement the engine turns away because another writer was changing the same node is run again, six attempts in all, on Neo4j and Memgraph. Inside a transaction the engine has usually failed the transaction with the statement, so the later attempts fail too and the first failure is what the caller sees.
- On FalkorDB each statement commits by itself. A save is still atomic, being one statement, and so is a `saveAll` of fragments alone that fits in one. An `update` is two, a load and a checked save, and a `saveAll` that holds a view is several: what ran before a failure or a refusal stays applied, unless `STRICT` mode refuses the batch before it runs.

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
- **On Neo4j a load can be handed a stamp newer than its data.** Neo4j reads under read committed, without a snapshot, so a load that runs while another writer commits can find that writer's stamp and the data from before its write. `update` therefore compares each field of the root as it loaded it too, under the node's lock, and loads again if one differs: a declared field's value, a `@PropertyBag`'s keys and their values, the labels a `@NodeLabels` field reads. A field loaded as what a load gives a node without the property (a `@Default`, an `@EmptyWhenAbsent` empty collection, a constructor default where the load reads every property) is taken as the same where the node has no such property, so a node saved before the field was added can still be updated. A `save` checks the same when you give it the object as loaded, as [`from`](#saving-data). Without `from` it has only the stamp to go on, and in that moment it can write over the other writer's change: hold on to what you loaded and pass it, or change the node with `update`. Where only the stamp makes the round trip, as through a web form, the stamp is all there is. Memgraph and Neptune read from a snapshot, FalkorDB runs no read beside a write, and Oracle and Postgres read a statement whole, so on them the stamp is what was read.

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
- **Saving with `CascadeType.DELETE_ALL`** has no equivalent on this manager: see [What you give up](#what-you-give-up).

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

Object-manager saves join these transactions: see [Saves and transactions](#saves-and-transactions) for what a refused save does to one.

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

A node or relationship in a row comes back as a map of its properties, whether it is a whole column or inside a list or a map, so `collect(n)`, `nodes(p)`, `relationships(p)`, `[n, m]` and `{actor: n}` give maps too. On FalkorDB a node's map also holds its `labels`, and a relationship's its `_type`, `_source` and `_destination`. A path inside a list or a map comes back as `{nodes, edges}`. A path that is a whole column comes back as the driver's `org.neo4j.driver.types.Path` on Neo4j and Memgraph, and as `{nodes, edges}` on FalkorDB.

## API Reference

### PersistenceManager

```kotlin
interface PersistenceManager {
    fun <T> query(spec: QuerySpecification<T>): List<T>
    fun <T> getOne(spec: QuerySpecification<T>): T
    fun <T> maybeGetOne(spec: QuerySpecification<T>): T?
    fun <T> execute(spec: QuerySpecification<T>)
    fun executeBatch(specs: List<QuerySpecification<*>>)                      // several statements, one transaction
    fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>>      // the same, returning each statement's rows
    val atomicBatches: Boolean                                                // whether a batch of several statements is applied whole
}
```

`executeBatch` and `queryBatch` run their statements in one transaction on the managers Drivine provides, or in the transaction they are called in. FalkorDB has no transactions: there each statement commits by itself, `atomicBatches` is false, a batch of one statement runs with no transaction, and a batch of more runs statement by statement (`WARN`) or is refused with `NonAtomicBatchException` before any of it runs (`STRICT`). The interface's own defaults run each statement on its own, so a `PersistenceManager` you implement must override both to make a batch atomic; `saveAll` calls `queryBatch`.

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
    val type: DatabaseType,                  // NEO4J, FALKORDB, NEPTUNE, MEMGRAPH, ORACLE, POSTGRES
    val host: String = "",
    val port: Int? = null,                   // the engine's own when null: 1521 on Oracle, 5432 on Postgres
    val userName: String? = null,
    val password: String? = null,
    val idleTimeout: Int? = null,
    val connectionTimeout: Int? = null,
    val poolMax: Int? = null,
    val databaseName: String? = null,        // on Oracle, the service to connect to; on Postgres, the database
    val defaultGraphPath: String? = null,    // on Oracle and Postgres, the name of the graph and its tables
    val protocol: String? = null,
    val cypherDialect: CypherDialect? = null,
    val falkorDbTransactionMode: FalkorDbTransactionMode? = null,
    val region: String? = null,
    val neptuneAuth: NeptuneAuthMode? = null,
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

> An object of a class with a [`@NodeStamp`](#cypher-you-write-yourself) field is bound with its stamp under the field's own name, not under `__drivine.stamp`. `SET n = $props` removes the node's stamp, and `SET n += $props` writes a stray property and leaves the stamp unmarked, so a checked save does not notice the write.

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
| Oracle 23ai / 26ai | `ORACLE` | Full ACID | In the statement | User and password |
| Postgres 14+ | `POSTGRES` | Full ACID | In the statement | User and password |

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

**Transactions:** FalkorDB does not support multi-statement transactions. `@Transactional` methods work but each query executes and commits independently. An object-manager `save` is one statement and so still atomic, and so is a `saveAll` of fragments alone that fits in one; an `update` is two and a `saveAll` that holds a view several, and what ran before a failure or a refused save stays applied. By default, `startTransaction()` logs a debug message, `rollbackTransaction()` logs a warning, and the first batch of more than one statement logs a warning that it is not applied whole. To enforce strict no-transaction usage (throw on `@Transactional`, and refuse a batch of more than one statement with `NonAtomicBatchException` before any of it runs), set:

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
- Nested pattern comprehensions returned NULL before [FalkorDB#1888](https://github.com/FalkorDB/FalkorDB/issues/1888) was fixed — Drivine projects a nested view with a CALL subquery prolog, one level of its relationships deep. A nested view that holds views of its own, itself among them, is projected inline with nested comprehensions, which current FalkorDB reads
- FalkorDB 6 empties a pattern comprehension that follows one nesting another in the same projection ([FalkorDB#3004](https://github.com/FalkorDB/FalkorDB/issues/3004); 4.x reads it right) — a recursive view whose levels hold a nested view loads its first level only there, until the fix is released
- `collect()` on null includes null maps ([FalkorDB#1889](https://github.com/FalkorDB/FalkorDB/issues/1889)) — Drivine filters with `CASE WHEN IS NOT NULL`
- FalkorDB 6 plans an `OPTIONAL MATCH` of several hops from the labelled node it ends at, and reads every relationship into that label ([FalkorDB#3332](https://github.com/FalkorDB/FalkorDB/issues/3332)) — a `@GraphPath` field leaves the label out of its pattern there and tests it on the node reached
- FalkorDB 6 scans a label for a `WHERE` in parentheses, where it would have used an index ([FalkorDB#3331](https://github.com/FalkorDB/FalkorDB/issues/3331)) — a load writes none around its conditions or around a keyset cursor (`seek`), and a load or a count of a fragment writes none around an `anyOf` that is its whole filter. An `anyOf` keeps its parentheses everywhere else, and FalkorDB 6 scans for it where nothing else in the filter has an index: beside another condition, nested in another `anyOf`, in a view, and in a `deleteAll`

CASCADE `DELETE_ORPHAN` is supported on current FalkorDB ([FalkorDB#1890](https://github.com/FalkorDB/FalkorDB/issues/1890) is fixed in the graph module Drivine tracks); older builds lacking that fix are not supported for orphan delete.

### Amazon Neptune

Neptune is AWS's managed graph database. Drivine connects via the Bolt protocol with two authentication modes.

`StatelessGraphObjectManager`, `@NodeStamp` and the stamps `edges` writes have not been run against Neptune as of 0.1.0, and neither have [grouped reads](#grouped-reads-groups). Loading and querying are as before.

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

### Oracle

Oracle Database 23ai or later, over JDBC. Oracle does not speak Cypher: Drivine reads a graph with
SQL/PGQ (`GRAPH_TABLE`) and writes it as rows, since Oracle refuses data manipulation through a
property graph. Models, the query DSL and `StatelessGraphObjectManager` are the same as on the graph
engines. Add the driver yourself: `com.oracle.database.jdbc:ojdbc11`.

```yaml
database:
  datasources:
    graph:
      type: ORACLE
      host: localhost
      port: 1521
      database-name: FREEPDB1     # the service to connect to
      user-name: app
      password: secret
      default-graph-path: drivine # names the property graph and its tables; this is the default
```

`user-name` and `database-name` are required, and `port` is 1521 unless given. A `cypher-dialect` or a
`protocol` means nothing here and is ignored with a warning. The graph's name names tables, so it is
letters, digits and underscores, begins with a letter, and is at most 114 characters.

Drivine makes a connection pool (HikariCP) for the datasource: `pool-max` connections at most (40
unless given), `connection-timeout` milliseconds to wait for one (5,000 unless given), and
`idle-timeout` milliseconds before an idle one is closed (the pool's own default unless given). The
pool asks the database for a connection as it is made, so a service that cannot be reached or a user
that is turned away fails at startup. Each connection keeps 64 statements prepared
(`oracle.jdbc.implicitStatementCacheSize`): a save is the same block each time, and is neither sent
nor parsed again.

To use a `DataSource` the application already has, register a provider in code:
`registry.register(JdbcConnectionProvider("graph", DatabaseType.ORACLE, dataSource, graphName = "drivine"))`.
Drivine does not close a `DataSource` it was given, and does not configure it: turn the driver's
statement cache on there yourself. For a pool of Drivine's own (HikariCP) without the YAML:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.ORACLE, JdbcConnectionProvider.oracleUrl("localhost", 1521, "FREEPDB1"), "app", "secret",
    graphName = "drivine",
)
```

**How a graph is stored.** Two tables and the property graph over them, made when a manager first
speaks to the graph: `<graph>_node (nid, labels, props, stamp)` and
`<graph>_edge (eid, src, dst, type, props)`, with `labels` and `props` as JSON. Labels, property
names and relationship types are data, so `@NodeLabels`, `@PropertyBag` and `edges.relate` work as
they do elsewhere. To SQL written by hand the graph has one vertex label, `node`, and one edge label,
`edge`; a Cypher label is a test on `labels` and a relationship type a test on `type`. A node's stamp is
the `stamp` column, and a load returns it as `__drivine.stamp`. Deleting a node's row deletes its
relationships with it. A third table, `<graph>_schema`, records the indexes and constraints Drivine
made: see [Schema Management](#schema-management-indexes--constraints).

**Notes:**
- A statement written by hand is SQL. Parameters are named `$name` as in Cypher. A collection is
  bound as JSON, for `JSON_TABLE` to read. A column of the JSON type comes back as a map, so
  `SELECT JSON_OBJECT(… RETURNING JSON)` hydrates with `.transform(MyView::class.java)`.
- A statement that writes and returns rows is a PL/SQL block that hands them back with
  `DBMS_SQL.RETURN_RESULT`. A block is one statement: one that fails writes nothing.
- A raw `whereClause` is a SQL predicate. A property of the root may be written as Cypher writes it
  (`issue.state = 'open'`); anything more is SQL over `issue.props`, `issue.labels` and `issue.stamp`.
- A save is one statement and checks the stamp, as on the graph engines. Another writer that changes a
  node in SQL should give it a new stamp too: the first sixteen characters of `stamp` for its
  properties or labels, the last sixteen for its relationships. `Stamps.setClause` and
  `Stamps.linksClause` are Cypher and do not apply.
- A save the database turns away because another writer held a row it wanted, a deadlock (`ORA-00060`)
  or a serialization failure (`ORA-08177`), is run again, as on Neo4j and Memgraph.
- `saveAll` saves fragments 500 to a statement, and each view by a statement of its own. The batch is
  one transaction.
- `patchAll` writes 500 nodes to a statement, and 100 where a `@VectorIndex` field is written, in one transaction.
- `patch` is one block. Its predicate is run inside the block as a statement of its own, since Oracle Free 23.26
  takes no parameter inside a `GRAPH_TABLE` of a block's own SQL, which is how a predicate on a relationship is written.
- A delete can cascade with `DELETE_ALL` and with `DELETE_ORPHAN`.
- Index the id of each node type you save: `constraints.ensure(UniquenessConstraintSpec("Person", "id"))`
  makes a unique index and the index a node is found by. Without one a save reads the table.
- A range index reads a property as text. A comparison or an order by a number or a moment is correct
  and is not served by that index, so an ordered or keyset-paged load is given no index advice.
- A range index is two indexes on Oracle: one over the property whatever the label, which a save and
  a load by id find a node by, and one over the property of the label's nodes alone. A `loadAll`, a
  `count`, a `deleteAll` or a `groups` whose `where` compares an indexed property with a text (`eq`,
  `gt`, `gte`, `lt`, `lte`) reads the second, and does not test the label on its own: the one index
  answers both. What else the `where` holds is tested on the nodes it gives. `IN`, `neq`, a test of
  part of a text, a comparison inside an `or`, and one with a number are read as before. A
  uniqueness constraint over one property already has an index over the label's nodes, its unique
  index, which reads the same value: a filter on that property is read through it, and no second
  one is made. A range index made by an earlier version is found, listed and dropped as it is, and
  the next `ensure` of it adds the index over the label's nodes. The backend reads which there are
  from `<graph>_schema` on a thread of its own, at most once a minute and again once this process
  has made or dropped an index or a constraint. A read never waits for it: until the first is done,
  and while another is under way, statements are written with what was read last.
- Oracle reads an empty string as null, so a stamp of `''` is no stamp. A comparison with `""` in the
  query DSL is written to find a property stored empty all the same; SQL of yours that compares
  `JSON_VALUE(…)` with `''` finds nothing.
- `matches` takes a regular expression in Oracle's POSIX dialect, matched against the whole value. A
  leading `(?i)` ignores case; what Java adds, such as `(?:…)`, a look-around or `\b`, is not read. A
  backreference names the group the expression numbers it by, `\1` to `\8`; `\9` is refused, as the
  group that anchors the expression would make it the tenth, which POSIX does not have.
- A vector index (`indexes.ensure(VectorIndexSpec(...))`, or `@VectorIndex` through schema management)
  is a table of embeddings beside the nodes, kept in step by a trigger, with an Oracle vector index
  over it by neighbour partitions. `loadNearest` goes through it and is approximate, as on the graph
  engines. With no index made, it measures every node of the label and is exact.
- A full-text index is a table of the indexed text beside the nodes, with an Oracle Text index over
  it. `loadMatching` reads words, a `"phrase"`, `+must`, `-not` and a `prefix*`. A backslash before
  punctuation or a space makes that character text: `prefix\*` is the word and `\-not` is not taken
  away. A backslash before a letter or a digit is a gap between two words, where Lucene drops it:
  `foo\bar` is the phrase `foo bar` here and the word `foobar` on Neo4j. The index holds what has
  been committed, so a search does not see its own transaction's writes. Oracle Text is in the
  regular Free image and not the slim one: without it, or with no index made, a search counts the
  words in the text of every node of the label, and does see them. An `analyzer` is recorded with
  the index and not applied.
- A property over 4,000 characters is stored and loaded whole, and compares as absent in a predicate.
- The deprecated `GraphObjectManager.save` and `saveAll` throw `UnsupportedOperationException` on Oracle;
  that manager's loads, queries and deletes work. Cypher statements written by hand do not run, and
  neither do the migration tools `RelationshipDirectionRepair` and `PathRelationshipReport`, which are
  Cypher: they repair what saves before 0.1.0 wrote, and no graph on Oracle is that old.

**What a read looks like.** A load is one SQL/PGQ statement, written to be read beside the Cypher it
stands for. A row is called what the model calls it, with an underscore so that a field may be called
`user` or `order`, and the node of a `MATCH` has its row's name. `IS node` and `IS edge` stand where
Cypher has a label and a type, since both are data: a label is a test of `labels`, a type of `type`.
`doc` is the node as the map a load returns. Where Cypher loads a view with

```cypher
MATCH (claim:Claim) WHERE claim.id = $id
RETURN { claim: claim {.*}, people: [(claim)-[:MENTIONS]->(people:Human) | people {.*}] } AS result
```

Oracle is sent

```sql
SELECT JSON_OBJECT(
    'claim' VALUE claim_.doc,
    'people' VALUE COALESCE((
        SELECT JSON_ARRAYAGG(people_.doc ORDER BY people_.eid RETURNING JSON)
        FROM GRAPH_TABLE (drivine
            MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(JSON_SERIALIZE(people_.labels), '"Human"') > 0)
            COLUMNS (claim_.nid AS src, e.eid, people_.doc)) people_
        WHERE people_.src = claim_.nid), JSON('[]'))
    RETURNING JSON) AS result
FROM GRAPH_TABLE (drivine
    MATCH (claim_ IS node WHERE INSTR(JSON_SERIALIZE(claim_.labels), '"Claim"') > 0)
    COLUMNS (claim_.nid, claim_.props, claim_.doc)) claim_
WHERE JSON_VALUE(claim_.props, '$.id') = $id
```

A relationship field is a query of its own, under the field it fills: Oracle does not let a
`GRAPH_TABLE` name the row it is nested in, so each returns the node it starts from as `src` and is
joined to that row outside. `COLUMNS` names only what the statement reads of a row, and every column
of the root once a `whereClause` written by hand is in it. A second row for
one field is numbered (`people2_`), as when a condition on `people` is tested beside the field itself.
A label is tested with `JSON_EXISTS(… '$[*]?(@ == "Claim")')` where every node of the label is read,
which the index over labels answers, and with `INSTR` where the node is found by its id or at the end
of a relationship. Where a range index on the label answers the `where`, the property is read as that
index reads it and the label is not tested on its own:

```sql
SELECT n_.doc AS result
FROM GRAPH_TABLE (drivine
    MATCH (n_ IS node)
    COLUMNS (n_.labels, n_.props, n_.doc)) n_
WHERE CASE WHEN JSON_EXISTS(n_.labels, '$[*]?(@ == "Issue")') THEN JSON_VALUE(n_.props, '$.repoKey') END = $_p0
  AND JSON_VALUE(n_.props, '$.state') = $_p1
```

A `@GraphPath` field, and a list read to a depth, is the path's own rows grouped by the node reached,
so each node is there once however many ways lead to it:

```sql
'employers' VALUE COALESCE((
    SELECT JSON_ARRAYAGG(employers_.doc ORDER BY employers_.nid RETURNING JSON)
    FROM (
        SELECT reached_.src, reached_.nid, ANY_VALUE(reached_.doc) AS doc
        FROM GRAPH_TABLE (drivine
            MATCH (claim_ IS node)
                -[e1 IS edge WHERE e1.type = 'MENTIONS']-> (m1 IS node WHERE INSTR(JSON_SERIALIZE(m1.labels), '"Human"') > 0)
                -[e2 IS edge WHERE e2.type = 'WORKS_AT']-> (reached_ IS node WHERE INSTR(JSON_SERIALIZE(reached_.labels), '"Company"') > 0)
            COLUMNS (claim_.nid AS src, reached_.nid, reached_.doc)) reached_
        GROUP BY reached_.src, reached_.nid
    ) employers_
    WHERE employers_.src = claim_.nid), JSON('[]'))
```

**Testing.** `OracleTestContainer` starts Oracle Free (`gvenzl/oracle-free:23-faststart`; another with
`ORACLE_TEST_IMAGE` or `-Dtest.oracle.image`) and gives a user, `drivine`, in the service `FREEPDB1`.
`@EnableDrivineTestConfig` uses it for a datasource of type `ORACLE`, or a local instance with
`USE_LOCAL_ORACLE=true` (see [TEST_CONTAINERS.md](TEST_CONTAINERS.md)). Add the JDBC driver to the test
classpath. Without Spring:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
    OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
)
```

A test written for the graph engines usually seeds its graph and reads it back in Cypher, which Oracle
does not run. `SqlCypherFixtures` works such a statement out over the graph instead, so the test's
fixtures stay as they are (it was `OracleCypherFixtures`, which still works, before Postgres kept a
graph the same way):

```kotlin
val fixtures = SqlCypherFixtures.over(persistenceManager)   // Cypher fixtures here, everything else to the store
fixtures.execute(QuerySpecification.withStatement("CREATE (:Person {id: 'ada'})-[:KNOWS]->(:Person {id: 'bob'})"))

SqlCypherFixtures.run(persistenceManager, "MATCH (p:Person) RETURN p.id ORDER BY p.id")   // ["ada", "bob"]
SqlCypherFixtures.clear(persistenceManager)                                               // every node and relationship
```

- It reads the Cypher fixtures use and no more: `CREATE`, `MATCH` and `MERGE` patterns with literal or
  bound properties, `WHERE` (comparisons joined by `AND`), `SET`, `REMOVE`, `DETACH DELETE`, `UNWIND`,
  `WITH`, and a `RETURN` of one expression or a `count`, with `ORDER BY`. Anything else is refused:
  `OR`, `ON CREATE SET`, `SKIP`, `LIMIT`, a `RETURN` of several columns.
- Keywords are read in any case, and a `//` comment is skipped. A statement is taken for a fixture
  when it opens with `MATCH (`, `CREATE (`, `MERGE (` or `UNWIND`, after any comments; any other, one
  that opens with `WITH` included, goes to the store as SQL.
- Each statement reads the whole graph into memory and writes back what it changed, a row at a time. It
  is for a test's handful of nodes and nothing else.
- The manager must name its graph (`graphName`), as one over a `JdbcConnectionProvider` does.
- `TestCleanup.beforeEach(persistenceManager, "my-test")` deletes the nodes whose `createdBy` is the tag,
  in SQL on Oracle, as it does in Cypher elsewhere.
- `SqlCypherFixtures.forStore(persistenceManager)` is the manager itself on a graph engine and the
  fixture interpreter over it on Oracle or Postgres, so one test, or one `PersistenceManager` bean of a
  test's Spring context, serves every store. Drivine's sample app and its own Spring tests use it so.

Drivine's own Oracle tests are left out of a plain `./gradlew test`: the image is large and the
database wants two gigabytes. Run them with

```bash
ORACLE_TESTS=true ./gradlew :test --tests '*Oracle*'
```

CI runs the same command, in a job of its own (`test (Oracle)`).

### Postgres

Postgres 14 or later (tested on 17), over JDBC: plain SQL, with no extension beyond pgvector, which
vector search uses where the server has it. Drivine keeps a graph in the same tables as on Oracle, so
a node carries any number of labels (a label is data, not a table), and models, the query DSL and
`StatelessGraphObjectManager` are the same as on the graph engines. Add the driver yourself:
`org.postgresql:postgresql`.

```yaml
database:
  datasources:
    graph:
      type: POSTGRES
      host: localhost
      port: 5432
      database-name: app          # the database to connect to
      user-name: app
      password: secret
      default-graph-path: drivine # names the graph's tables; this is the default
```

`user-name` and `database-name` are required, and `port` is 5432 unless given. A `cypher-dialect` or a
`protocol` means nothing here and is ignored with a warning. The graph's name names tables, so it is
letters, digits and underscores, begins with a letter, and is at most 49 characters; Postgres folds
it to lower case, so `Contracts` and `contracts` are one graph. The pool is as on Oracle (HikariCP,
`pool-max`, `connection-timeout`, `idle-timeout`), and sends a string without a type, so a statement
casts it as it needs (`stringtype=unspecified`). To use a `DataSource` the application already has:
`registry.register(JdbcConnectionProvider("graph", DatabaseType.POSTGRES, dataSource, graphName = "drivine"))`.
Drivine does not configure a `DataSource` it is given: set `stringtype=unspecified` on it yourself, as
statements written by hand and the test kit's fixtures bind JSON text where a column is `jsonb`. For a
pool of Drivine's own without the YAML:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.POSTGRES, JdbcConnectionProvider.postgresUrl("localhost", 5432, "app"), "app", "secret",
    graphName = "drivine",
)
```

**How a graph is stored.** The tables Oracle keeps, made when a manager first speaks to the graph:
`<graph>_node (nid, labels, props, stamp)` and `<graph>_edge (eid, src, dst, type, props)`, with
`labels` (a JSON array) and `props` as `jsonb`, and `<graph>_schema` for what schema management made.
A GIN index over `labels` answers whether a node carries a label. Beside them are three functions,
`<graph>_write`, `<graph>_find` and `<graph>_owns`, which a save is carried out by. A store that an
earlier version of Drivine made is brought up to date on first use, and keeps its rows. A node's stamp
is the `stamp` column, returned by a load as `__drivine.stamp`. Deleting a node's row deletes its
relationships with it.

**Notes:**
- A statement written by hand is SQL. Parameters are named `$name`; a collection is bound as JSON, for
  `CAST($list AS jsonb)` to read. A `json` or `jsonb` column comes back as a map, so
  `SELECT jsonb_build_object(…)` hydrates with `.transform(MyView::class.java)`. A function body in
  dollar quotes, `$fn$ … $fn$`, is left as it is.
- A statement that writes and returns rows is one with `RETURNING`, or a data-modifying `WITH`: one
  statement, which writes nothing when it fails.
- A raw `whereClause` is a SQL predicate. A property of the root may be written as Cypher writes it
  (`issue.state = 'open'`); anything more is SQL over `issue.props`, `issue.labels` and `issue.stamp`
  (`issue.props ->> 'state'`). Postgres's `?` operators cannot be used: JDBC takes a `?` for a parameter.
- A save is a plan of steps, the same plan Oracle's block carries out, which `<graph>_write` carries
  out in one statement: applied whole or not at all, and stamped and checked as on the graph engines.
  A node is found by its id and its labels, then locked by its key and looked at again. Another writer
  that changes a node in SQL should give it a new stamp, as on Oracle.
- A save the database turns away for a serialization failure (`40001`) or a deadlock (`40P01`) is run
  again, as on Neo4j, Memgraph and Oracle.
- `saveAll` saves fragments 500 to a statement, and each view by a statement of its own, in one
  transaction; `patchAll` writes 500 nodes to a statement.
- A delete is one statement, and can cascade with `DELETE_ALL` and `DELETE_ORPHAN`.
- A number is compared and ordered as a number only where one is stored: the text `'7'` is not `7`,
  as on the graph engines. Postgres keeps an empty string apart from null, so `""` is a value.
- Text is ordered by the database's collation, so a page of mixed-case text may not be in the order
  Neo4j gives (`a, b, B` rather than `B, a, b`). The range indexes are made in that collation, so an
  order and a seek are read through them.
- Index the id of each node type you save, `constraints.ensure(UniquenessConstraintSpec("Person", "id"))`,
  or with a range index: without one a save reads the label's nodes to find the one it writes.
- `matches` takes a regular expression in Postgres's dialect, matched against the whole value. A
  leading `(?i)` ignores case.
- A vector index is a partial HNSW index of pgvector over the embeddings of the label's nodes that
  are of its size; a node written with another size is left out, not refused. `loadNearest` goes
  through it and is approximate; with no index made, it measures every node of the label whose
  embedding is of the vector's size, and is exact. pgvector is made with the graph where the server
  has it and the user may: without it, everything else works.
- `loadMatching` reads the full-text syntax Oracle reads, and counts each term in the text as Oracle
  does without Oracle Text, so the two score alike. A full-text index is a GIN index of `pg_trgm`
  trigrams over the text, which narrows a search to the nodes that hold a term's words; the same
  counting then scores them, and a search sees its own transaction's writes. An `analyzer` is
  recorded with the index and not applied.
- The deprecated `GraphObjectManager.save` and `saveAll`, and the Cypher migration tools, do not run
  on Postgres, as on Oracle; that manager's loads, queries and deletes work.

**What a read looks like.** A load is one statement, written to be read beside the Cypher it stands
for. A row is called what the model calls it, with an underscore so that a field may be called `user`
or `order`. A relationship field is a subquery over the nodes reached from the root's row: a path is
joins over the edge table, a step to a depth is a recursive query anchored at the node it starts from,
and each label is tested on its own, so that a partial index over the nodes of one is used. Where
Cypher loads a view with

```cypher
MATCH (claim:Claim) WHERE claim.id = $id
RETURN { claim: claim {.*},
         companies: [(claim)-[:MENTIONS]->(companies:Company) | companies {.*}],
         people: [(claim)-[:MENTIONS]->(people:Human) | people {.*}] } AS result
```

Postgres is sent

```sql
SELECT jsonb_build_object(
    'claim', (claim_.props || jsonb_build_object('labels', claim_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', claim_.stamp))),
    'companies', COALESCE((
        SELECT jsonb_agg((companies_.props || jsonb_build_object('labels', companies_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', companies_.stamp))) ORDER BY companies_.eid)
        FROM (
            SELECT e.src AS src, e.eid, e.props AS eprops, companies_.nid, companies_.labels, companies_.props, companies_.stamp
            FROM drivine_edge e
                JOIN drivine_node companies_ ON companies_.nid = e.dst
            WHERE e.src = claim_.nid
              AND e.type = 'MENTIONS'
              AND companies_.labels @> '["Company"]'
        ) companies_), '[]'::jsonb),
    'people', COALESCE((
        SELECT jsonb_agg((people_.props || jsonb_build_object('labels', people_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', people_.stamp))) ORDER BY people_.eid)
        FROM (
            SELECT e.src AS src, e.eid, e.props AS eprops, people_.nid, people_.labels, people_.props, people_.stamp
            FROM drivine_edge e
                JOIN drivine_node people_ ON people_.nid = e.dst
            WHERE e.src = claim_.nid
              AND e.type = 'MENTIONS'
              AND people_.labels @> '["Human"]'
        ) people_), '[]'::jsonb)) AS result
FROM drivine_node claim_
WHERE claim_.labels @> '["Claim"]'
  AND (claim_.props ->> 'id') = CAST($id AS text)
```

**Testing.** `PostgresTestContainer` starts Postgres with pgvector (`pgvector/pgvector:pg17`; another
with `POSTGRES_TEST_IMAGE` or `-Dtest.postgres.image`) and gives a user, `drivine`, and a database,
`drivine`. `@EnableDrivineTestConfig` uses it for a datasource of type `POSTGRES`, or a local
instance with `USE_LOCAL_POSTGRES=true` (see [TEST_CONTAINERS.md](TEST_CONTAINERS.md)). Add the JDBC
driver to the test classpath. A test that seeds its graph in Cypher keeps its fixtures with
`SqlCypherFixtures`, as on Oracle, and `TestCleanup.beforeEach` works on Postgres too. Without Spring:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
    PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
)
```

Drivine's own Postgres tests run with

```bash
POSTGRES_TESTS=true ./gradlew :test --tests '*Postgres*'
```

CI runs the same command, in a job of its own (`test (Postgres)`), and then the sample app's stateless
tests and the root module's stateless Spring tests on a Postgres datasource
(`DATABASE_DATASOURCES_GRAPH_TYPE=POSTGRES`, `DATABASE_DATASOURCES_NEO_TYPE=POSTGRES`).

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
field's property, or a declared field's name, is rejected at save: a load hands those to their fields,
the `@NodeStamp` field among them.

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
  their stamp when they change something, so a `Replace` from an object loaded before is refused. A
  `relate` that merges takes both nodes' write locks before it looks for the relationship, so two
  of them on the same node run one after the other, or one is turned away by the engine and run again.
  A `relate` that finds the relationship there as it is, and an `unrelate` that finds none, mark neither.

### Cypher Dialect

Each engine uses a Cypher dialect that controls query generation. The dialect is auto-detected from the database type but can be overridden:

```yaml
      cypher-dialect: NEO4J_5    # NEO4J_5, NEO4J_4, FALKORDB, NEPTUNE, MEMGRAPH
```

## Schema Management (Indexes & Constraints)

Drivine manages vector indexes, full-text indexes, range indexes, and uniqueness constraints across Neo4j, Memgraph, FalkorDB and Oracle — with idempotent, drift-aware `ensure` semantics and engine differences (DDL syntax, introspection, FalkorDB's Redis-command constraints) handled for you. Opt-in: declare nothing, pay nothing.

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
| `Created` | Nothing existed and the item was created, or it existed without part of what the engine makes for it (on Oracle, a range index without its index over the label's nodes) and that part was made |
| `AlreadyMatching` | A matching item exists; nothing changed |
| `Drift` | An item exists with a **different shape** (e.g. vector dimensions changed). Nothing changed — call `recreate(spec)` to replace it (destructive) |
| `Violation` | Constraint only: existing data violates it — a key the constraint covers repeats. Includes a bounded sample of the conflicting values. A refusal the data does not bear out (no key repeats) is thrown, not reported as a `Violation` |
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

**Oracle.** Every item is an index over an expression, since a property is a path into a JSON column, and what Drivine made is recorded in the table `<graph>_schema`, which `ensure` and drift detection read. An item whose index or table was dropped by hand is made again by the next `ensure`.

| | Oracle |
|---|---|
| Vector indexes | A table of embeddings (`VECTOR(n, FLOAT32)`) beside the nodes, kept in step by a trigger and filled from the nodes there are, with `CREATE VECTOR INDEX … ORGANIZATION NEIGHBOR PARTITIONS` over it. An embedding of another dimension leaves its node out of the index |
| Full-text indexes | A table of the indexed text beside the nodes, kept in step by a trigger, with an Oracle Text search index over it. Without Oracle Text the index is recorded and nothing is made |
| Range indexes | Two indexes. `CREATE INDEX … (JSON_VALUE(props, '$.p'))`, the property read as text, over the property whatever the label, so two labels that index a property of one name share it: a save and a load by id find a node by it. And `CREATE INDEX … (CASE WHEN JSON_EXISTS(n.labels, '$[*]?(@ == "Label")') THEN JSON_VALUE(n.props, '$.p') END)`, over the property of the label's nodes alone, which a filter on the label's nodes reads. Composite supported |
| Uniqueness | A unique index over the property for the nodes that carry the label, with the range index a node is found by. A node that lacks one of the properties is not constrained. Over one property, the unique index stands for the range index's index over the label's nodes, and none is made beside it |
| Item names | Recorded; the Oracle object's name is derived from the graph, label and properties, and recorded with it. A drop drops the object the record names |

The store's identity (`DrivineStore`) and the schema marker are nodes of the graph on Oracle too, and the graph's tables are made for them when they are the first thing asked of a new graph.

**Postgres.** Every item is a partial index over the nodes of its label, recorded in `<graph>_schema` as on Oracle. An index dropped by hand is not listed, and is made again by the next `ensure`. The store's identity and the schema marker are nodes of the graph, as on Oracle.

| | Postgres |
|---|---|
| Vector indexes | `CREATE INDEX … USING hnsw ((CAST(CAST(props -> 'p' AS text) AS vector(n))) vector_cosine_ops) WHERE labels @> '["Label"]' AND …`, over the embeddings of the label's nodes that are of its size (`vector_l2_ops` for Euclidean). Needs pgvector, which the index makes where it may |
| Full-text indexes | `CREATE INDEX … USING gin ((lower(…)) gin_trgm_ops) WHERE labels @> '["Label"]'`, over the text a search reads, with `pg_trgm`, which the index makes where it may |
| Range indexes | `CREATE INDEX … ((props ->> 'p')) WHERE labels @> '["Label"]'`, one per label. Composite supported |
| Uniqueness | The same index made `UNIQUE`, which also finds a node, so no range index is made beside it. A node that lacks one of the properties is not constrained |
| Item names | Recorded; the index's name is derived from the graph, label and properties, and Postgres keeps it in lower case. A drop drops the index the record names |

Neptune and generic openCypher have no schema management support — operations fail loudly rather than silently no-op.

## Multi-Database Support

```kotlin
@Configuration
class MultiDbConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        return DataSourceMap(mapOf(
            "analytics" to ConnectionProperties(
                type = DatabaseType.NEO4J,
                host = "analytics.neo4j.com",
                databaseName = "analytics"
            ),
            "users" to ConnectionProperties(
                type = DatabaseType.NEO4J,
                host = "users.neo4j.com",
                databaseName = "users"
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

An object manager for a named database comes from the factory:

```kotlin
@Bean
fun analyticsObjects(factory: GraphObjectManagerFactory): StatelessGraphObjectManager = factory.stateless("analytics")
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
      user-name: neo4j
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

`.nullsLast()` or `.nullsFirst()` after `ascending()` / `descending()` says where the rows with no
value go, as [in Kotlin](#ordering): `.orderBy(dsl -> dsl.getIssue().getClosedAt().descending().nullsLast())`.

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

### Grouped Reads

A [grouped read](#grouped-reads-groups) is not on the query builder: it is `getGroups()` on either manager, and its handles come from the spec. That section has an example; this is what each Kotlin line is in Java.

| Kotlin | Java |
|--------|------|
| `where { … }` | `spec.onlyIf(condition, …)` |
| `key(query.x)`, `keyOrNull(query.x)` | `spec.key(dsl.getX())`, `spec.keyOrNull(dsl.getX())` |
| `key(path<N>(Hop(…), …))` | `spec.key(spec.path(N.class, spec.hop("TYPE", Direction.INCOMING, "Label"), …))` |
| `count()`, `count { … }` | `spec.count()`, `spec.countWhere(condition, …)` |
| `sum(query.x)` | `spec.sumOfInts`, `sumOfLongs`, `sumOfFloats`, `sumOfDoubles` |
| `avg`, `min`, `max` | `spec.avg(dsl.getX())`, `spec.min(…)`, `spec.max(…)` |
| `sum(query.x) { … }` | `spec.filtered(spec.sumOfLongs(dsl.getX()), condition, …)` |
| `having { votes gte 3 }` | `spec.having(votes, ComparisonOperator.GREATER_THAN_OR_EQUAL, 3)` |
| `orderBy { votes.desc() }` | `spec.orderBy(votes.descending())`, then `.nullsFirst()` / `.nullsLast()` |
| `rows { … }` | `return spec.rowsOf(row -> …)` |
| `groups.countBy(T::x)` | `manager.getGroups().countBy(T.class, "x")` |

A Java field does not say whether it may be null, so `key` of one is not refused: a node with no value is reported when its row is read, and `keyOrNull` reads it as null.

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
| Order | `.orderBy(dsl -> dsl.getProp().ascending())`, `.descending()`; then `.nullsLast()` / `.nullsFirst()` |
| Keyset cursor | `.seek(dsl -> List.of(dsl.getProp().cursorAfter(v)))` |
| Grouped read | `manager.getGroups().load(T.class, dsl, spec -> …)`: see [Grouped Reads](#grouped-reads) |

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

`GraphObjectManager` is deprecated in favour of `StatelessGraphObjectManager`. It still works, with the fixes and the stamping that 0.1.0 brought to both managers, and is described in [docs/legacy-graph-object-manager.md](docs/legacy-graph-object-manager.md). Everything 0.1.0 changed is listed once, in [docs/0.1.0-stateless-object-manager.md](docs/0.1.0-stateless-object-manager.md#what-changed).

### Why

`GraphObjectManager` keeps a snapshot of every object it loads or saves, and a save writes only what differs from the snapshot. Whether a save was correct therefore depended on things you could not see from the call: whether this manager had loaded the object, whether it was still in the session, and whether anything else had written to the node since. Each of those produced a real bug:

- A node deleted by plain Cypher and saved again came back with only some of its properties.
- A relationship dropped from a list was removed or kept depending on whether the object had been evicted from the session.
- A view the manager had not loaded wrote a path field as a direct relationship.

A library should make the graph easier to work with, not require forensics to save an object safely. A `StatelessGraphObjectManager` save depends only on the object and the arguments you pass, and with a `@NodeStamp` field it is refused when another writer got there first.

### Upgrade steps

1. Move the core library and the code generator to 0.1.0 together, and compile again. Code compiled against 0.0.x does not link: the loading and query methods of `GraphObjectManager` are now declared on `GraphObjectOperations`.
2. A build that treats warnings as errors now fails on `GraphObjectManager` and `GraphObjectManagerFactory.get()`, which are deprecated. Injecting a `GraphObjectManager` by type warns too. Until the code has moved, suppress it where it is used: `@Suppress("DEPRECATION")` in Kotlin, `@SuppressWarnings("deprecation")` in Java.
3. Deploy 0.1.0 to everything that writes to the store.
4. If any view has an `INCOMING` relationship field, a `@GraphPath` field or a list read over several hops, and was ever saved, repair what the old saves left: see [Data saved before 0.1.0](#data-saved-before-010). Do it after step 3, since a writer still on 0.0.x writes the same mistakes again.
5. Replace `factory.get()` with `factory.stateless()`, and change the saves as the table below says.
6. Add a `@NodeStamp` field to each type you load, change and save. See [Existing data](#existing-data) for nodes that have no stamp yet.

### What you give up

- **Dirty-only writes are no longer automatic.** Ask for them with `update { }`, `only` or `except`.
- **Saving with `CascadeType.DELETE_ALL` has no equivalent.** Remove the relationships with `Replace`, then delete the nodes:

  ```kotlin
  val dropped = loaded.attachments - kept
  graphObjectManager.save(loaded.copy(attachments = kept), Replace(MessageView::attachments))
  dropped.forEach { graphObjectManager.delete<Attachment>(it.id) }
  ```
- **Saves have no interface.** `save`, `saveAll`, `patch`, `patchAll` and `update` are on `StatelessGraphObjectManager` itself. `GraphObjectOperations` covers loading, querying and deleting, so code that saves depends on the class.
- **The session.** There is none, so nothing to clear, and nothing that remembers what was loaded.

### What changes in your code

Loading, querying and deleting are the same: both managers implement `GraphObjectOperations`, and the generated query DSL, whose extensions now have that receiver, works on either. Saves change as follows.

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
- A `@GraphPath`, `@Count` or `@Aggregate` field, and a list of fragments read over several hops, is read-only with either manager: see [`@ReadOnly`](#readonly-a-field-that-is-loaded-and-never-written).
- Nodes saved by either manager, and the node at each end of a relationship either manager or `edges` writes or removes, now carry the property `__drivine.stamp`, whether or not their type declares a `@NodeStamp` field. It cannot be turned off. A test that compares a node's whole property map, Cypher that copies one node's properties to another, an export, and anything of your own that reads every property of a node, such as `properties(n)` mapped onto a class that refuses unknown properties, will see it. Drivine's own mapping ignores it. Names beginning `__drivine.` are Drivine's own, should your code need to tell them from its own.

### Data saved before 0.1.0

Three things a view save before 0.1.0 wrote are wrong in the store, and stay wrong until they are repaired:

- A relationship of a field declared `Direction.INCOMING` was written from the view's root to the target, so it points the wrong way and the view does not load it back. `RelationshipDirectionRepair` reports these and turns them round.
- A `@GraphPath` field of a view the manager had not loaded was written as one direct relationship, of the first hop's type, from the root to each node the field held. `PathRelationshipReport` counts these and gives the Cypher that removes them.
- A list read over several hops (`maxDepth` above 1) was written as one direct relationship from the root to each node, however far away. `PathRelationshipReport.reportSeveralHopLists` counts what may be left of those.

The tools, what each finding means, and the order to run them in are in [docs/0.1.0-stateless-object-manager.md](docs/0.1.0-stateless-object-manager.md#repairing-data-saved-before-010). In short: back up, run the reports over every view of the model, read them, and only then repair. The repair changes relationships in the store, by label and type; it cannot tell a relationship a save wrote from one made some other way.

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
