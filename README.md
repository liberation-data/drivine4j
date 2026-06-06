# Drivine4j

[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A graph database client library for Java and Kotlin supporting **Neo4j**, **FalkorDB**, **Amazon Neptune**, and **Memgraph** with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach)
2. **GraphObjectManager** - High-level API with annotated models and type-safe DSL. 

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
  - For GraphObjectManager API: **Kotlin 2.2.0+** (requires context parameters feature)

## Installation

### Core Library

#### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("org.drivine:drivine4j:0.0.30")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'org.drivine:drivine4j:0.0.30'
}
```

#### Maven
```xml
<dependency>
    <groupId>org.drivine</groupId>
    <artifactId>drivine4j</artifactId>
    <version>0.0.30</version>
</dependency>
```

### Code Generation (For GraphObjectManager with Type-Safe DSL)

If you want to use `GraphObjectManager` with the type-safe query DSL, you need to add the code generation processor.

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
    implementation("org.drivine:drivine4j:0.0.30")
    ksp("org.drivine:drivine4j-codegen:0.0.30")
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
                    <version>0.0.30</version>
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
                .withStatement<Any>("MATCH (p:Person {city: \$city}) RETURN properties(p)")
                .bind(mapOf("city" to city))
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun findById(id: String): Person? {
        return manager.maybeGetOne(
            QuerySpecification
                .withStatement<Any>("MATCH (p:Person {uuid: \$id}) RETURN properties(p)")
                .bind(mapOf("id" to id))
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun create(person: Person): Person {
        return manager.getOne(
            QuerySpecification
                .withStatement<Any>("CREATE (p:Person) SET p = \$props RETURN properties(p)")
                .bindObject("props", person)
                .transform(Person::class.java)
        )
    }

    @Transactional
    fun update(uuid: String, patch: Partial<Person>): Person {
        val props = patch.toMap()
        return manager.getOne(
            QuerySpecification
                .withStatement<Any>(
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

## GraphObjectManager - Type-Safe Graph Mapping

`GraphObjectManager` provides a high-level API for working with graph-mapped objects using annotated models. It generates efficient Cypher queries automatically and provides a type-safe DSL for filtering and ordering.

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
    @Count("ACTED_IN")                                              val movieCount: Long,
    @Aggregate(AggregateFunction.AVG, type = "RATED", property = "score") val avgRating: Double,
    @Aggregate(AggregateFunction.SUM, type = "RATED", property = "score") val totalRating: Double,
)
```

`@Count` needs no property; `SUM`/`AVG`/`MIN`/`MAX` aggregate a numeric property of the related nodes. Aggregates are single-hop. For group-by *ranking* (top-N), use `PersistenceManager` + `.transform<T>()` with Cypher — that's not a node-rooted view.

> All three — path traversal and aggregates — work identically across Neo4j, Memgraph, and FalkorDB.

### Loading Data

#### Load All Instances

```kotlin
@Component
class PersonService @Autowired constructor(
    private val graphObjectManager: GraphObjectManager
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
// 1. Count everything of a type
val total: Long = graphObjectManager.count(Issue::class.java)

// 2. Count with a simple WHERE filter (aliases match loadAll: `n` for fragments,
//    the root fragment field name for views)
graphObjectManager.count(Issue::class.java, "n.state = 'open'")

// 3. Count with the type-safe DSL (pass the generated query object)
graphObjectManager.count(RaisedAndAssignedIssue::class.java, RaisedAndAssignedIssueQueryDsl.INSTANCE) {
    where { query.issue.state eq "open" }
}
```

**Fragments** are a straight node count of the fragment's labels.

**Views count only roots that satisfy the view's _required_ relationships** — non-optional, non-collection `@GraphRelationship`s — so the result equals `loadAll(...).size`, *not* a naive node count. For example, `RaisedAndAssignedIssue` requires `raisedBy` (a single, non-null `RAISED_BY`):

```kotlin
graphObjectManager.count(Issue::class.java)                    // 3 — every Issue node
graphObjectManager.count(RaisedAndAssignedIssue::class.java)   // 2 — only Issues with a RAISED_BY edge
```

An `Issue` with no `RAISED_BY` is a valid `Issue` node but is **not** a `RaisedAndAssignedIssue`, so it is excluded — just as `loadAll` would exclude it. Optional (nullable) and collection relationships place no such constraint.

From Java, the same three overloads apply (use `.count(Issue.class)` etc.); the DSL overload takes the generated query object and a lambda.

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
- `in` - IN operator

**String Operations:**
- `contains` - CONTAINS
- `startsWith` - STARTS WITH
- `endsWith` - ENDS WITH

**Null Checking:**
- `isNull()` - IS NULL
- `isNotNull()` - IS NOT NULL

**Ordering:**
- `asc()` - ascending order
- `desc()` - descending order

### Saving Data

#### Simple Save (Dirty Tracking)

GraphObjectManager tracks loaded objects and only saves changed fields:

```kotlin
// Load an object
val person = graphObjectManager.loadOrThrow<PersonCareer>(uuid)

// Modify it
val updated = person.copy(
    person = person.person.copy(bio = "Updated bio")
)

// Save - only dirty fields are written!
graphObjectManager.save(updated)
```

#### Save with Relationship Changes

```kotlin
val person = graphObjectManager.loadOrThrow<PersonCareer>(uuid)

// Remove all employment history
val updated = person.copy(employmentHistory = emptyList())

graphObjectManager.save(updated, CascadeType.NONE)
```

### Deleting Data

GraphObjectManager provides type-safe methods for deleting graph objects.

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

All delete operations use `DETACH DELETE`:
- Removes the node and all its relationships
- Related nodes are **not** deleted (only the relationships to them)
- Returns the count of deleted nodes

```kotlin
// Delete an issue - persons remain, only ASSIGNED_TO/RAISED_BY relationships removed
graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid)

// Verify related nodes still exist
val person = graphObjectManager.load<Person>(personUuid)  // Still there!
```

### CASCADE Policies

When saving `@GraphView` objects with modified relationships, `CascadeType` determines what happens to target nodes:

#### CascadeType.NONE (Default - Safest)

Only deletes the relationship, leaves target nodes intact:

```kotlin
graphObjectManager.save(updated, CascadeType.NONE)
```

Use when: Target nodes are shared or should persist independently.

#### CascadeType.DELETE_ORPHAN (Safe Deletion)

Deletes relationship and target only if no other relationships exist to the target:

```kotlin
graphObjectManager.save(updated, CascadeType.DELETE_ORPHAN)
```

Use when: You want to clean up orphaned nodes but preserve shared ones.

**Example:** Removing a person's employment at a solo startup deletes the startup (orphaned), but removing employment at a company with other employees keeps the company.

#### CascadeType.DELETE_ALL (Destructive)

Always deletes both the relationship and target nodes:

```kotlin
graphObjectManager.save(updated, CascadeType.DELETE_ALL)
```

⚠️ **Warning:** Permanently deletes data. Use with caution.

Use when: Target nodes are exclusively owned and should be deleted with the relationship.

### Session and Dirty Tracking

`GraphObjectManager` maintains a session that tracks loaded objects:

1. **On Load**: Takes a snapshot of the object's state
2. **On Save**: Compares current state to snapshot
3. **Optimization**: Only writes changed fields (dirty checking)

This means:
- **Loaded objects**: Optimized saves (only dirty fields)
- **New objects**: Full saves (all fields written)

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
        .withStatement<Any>("MATCH (p:Person) RETURN properties(p)")
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
        .withStatement<Any>("MATCH (p:Person) RETURN properties(p)")
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
                .withStatement<Any>(findActiveUsers.statement)
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
    .withStatement<T>(cypherQuery)       // Start with Cypher query
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
- Includes null values by default (allows explicit property removal)
- Ignores unknown properties when deserializing

To exclude nulls on specific properties, use `@JsonInclude(JsonInclude.Include.NON_NULL)`.

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

### Cypher Dialect

Each engine uses a Cypher dialect that controls query generation. The dialect is auto-detected from the database type but can be overridden:

```yaml
      cypher-dialect: NEO4J_5    # NEO4J_5, NEO4J_4, FALKORDB, NEPTUNE, MEMGRAPH
```

## Schema Management (Indexes & Constraints)

Drivine manages vector indexes, range indexes, and uniqueness constraints across Neo4j, Memgraph, and FalkorDB — with idempotent, drift-aware `ensure` semantics and engine differences (DDL syntax, introspection, FalkorDB's Redis-command constraints) handled for you. Opt-in: declare nothing, pay nothing.

### Imperative API

Every `PersistenceManager` exposes `indexes` and `constraints` managers. Operations always run in auto-commit mode (schema DDL cannot run inside a data transaction):

```kotlin
// Idempotent — call on every startup
val result = persistenceManager.indexes.ensure(
    VectorIndexSpec(label = "Proposition", property = "embedding", dimensions = 1536)
)
persistenceManager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
persistenceManager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))  // composite

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
)

// Composite declarations go on the class
@NodeFragment(labels = ["Message"])
@RangeIndex(properties = ["sessionId", "createdAt"])
@Unique(properties = ["sessionId", "sequence"])
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
    .where(dsl -> dsl.getIssue().getState().eq("open"))
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
.where(dsl -> dsl.getIssue().getId().eq(100L))      // equals
.where(dsl -> dsl.getIssue().getId().neq(100L))     // not equals
.where(dsl -> dsl.getIssue().getId().gt(100L))      // greater than
.where(dsl -> dsl.getIssue().getId().gte(100L))     // greater than or equal
.where(dsl -> dsl.getIssue().getId().lt(100L))      // less than
.where(dsl -> dsl.getIssue().getId().lte(100L))     // less than or equal
```

**String Operations:**
```java
.where(dsl -> dsl.getIssue().getTitle().contains("Bug"))
.where(dsl -> dsl.getIssue().getTitle().startsWith("Feature"))
.where(dsl -> dsl.getIssue().getTitle().endsWith("needed"))
```

**Null Checks:**
```java
.where(dsl -> dsl.getIssue().getBody().isNull())
.where(dsl -> dsl.getIssue().getBody().isNotNull())
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
    .where(dsl -> dsl.getIssue().getState().eq("open"))
    .where(dsl -> dsl.getIssue().getLocked().eq(false))
    .where(dsl -> dsl.getIssue().getId().gte(100L))
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
        dsl.getIssue().getState().eq("open"),
        dsl.getIssue().getState().eq("reopened")
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
    .where(dsl -> dsl.getIssue().getLocked().eq(false))
    .whereAny(dsl -> Arrays.asList(
        dsl.getIssue().getState().eq("open"),
        dsl.getIssue().getState().eq("reopened")
    ))
    .loadAll();
```

### Ordering

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().eq("open"))
    .orderBy(dsl -> dsl.getIssue().getId().desc())
    .loadAll();
```

### Load First

Get only the first matching result:

```java
RaisedAndAssignedIssue result = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().eq("open"))
    .orderBy(dsl -> dsl.getIssue().getId().desc())
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
    .where(dsl -> dsl.getCore().getGuideProgress().gte(10))
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
    .where(dsl -> dsl.getIssue().getState().eq("closed"))
    .deleteAll();
```

### Java DSL Summary

| Operation | Example |
|-----------|---------|
| Equality | `.eq("value")`, `.neq("value")` |
| Comparison | `.gt(n)`, `.gte(n)`, `.lt(n)`, `.lte(n)` |
| Strings | `.contains("x")`, `.startsWith("x")`, `.endsWith("x")` |
| Null | `.isNull()`, `.isNotNull()` |
| Collections | `.isIn(Arrays.asList(...))` |
| Type filter | `.instanceOf(SubtypeClass.class)` |
| AND | Chain multiple `.where()` |
| OR | `.whereAny(dsl -> Arrays.asList(...))` |
| Order | `.orderBy(dsl -> dsl.getProp().asc())` |

## Java Interoperability

### DSL Generation Note

The code generator (KSP) only processes **Kotlin source files**. For the best experience:
- Define your `@GraphView` classes in Kotlin to get the generated type-safe DSL
- Your `@NodeFragment` classes can be in Java or Kotlin
- At runtime, both Java and Kotlin classes work fully with `GraphObjectManager`

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
    .where(dsl -> dsl.getPerson().getName().contains("Alice"))
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
