# Drivine4j

[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A Neo4j client library for Java and Kotlin with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach)
2. **GraphObjectManager** - High-level API with annotated models and type-safe DSL (new in 4.0)

## Philosophy

### Composition Over Inheritance

A typical ORM defines a reusable object model. From this model statements are generated to hydrate to and from the model to the database. This addresses the so-called impedance mismatch between the object model and the database. However, there are drawbacks:

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
    implementation("org.drivine:drivine4j:0.0.1-SNAPSHOT")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'org.drivine:drivine4j:0.0.1-SNAPSHOT'
}
```

#### Maven
```xml
<dependency>
    <groupId>org.drivine</groupId>
    <artifactId>drivine4j</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Code Generation (For GraphObjectManager with Type-Safe DSL)

If you want to use `GraphObjectManager` with the type-safe query DSL, you need to add the code generation processor.

> **Note for Java Projects:** The code generator (KSP) only processes Kotlin source files. For the best experience:
> - Define your `@GraphView` classes in Kotlin to get the generated type-safe DSL
> - Your `@NodeFragment` classes can be in Java or Kotlin
> - At runtime, both Java and Kotlin classes work fully with `GraphObjectManager`
>
> See the [Java Interoperability](#java-interoperability) section for details and examples.

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
    implementation("org.drivine:drivine4j:0.0.1-SNAPSHOT")
    ksp("org.drivine:drivine4j-codegen:0.0.1-SNAPSHOT")
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
                    <version>0.0.1-SNAPSHOT</version>
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

### Loading Data

#### Load All Instances

```kotlin
@Component
class PersonService @Autowired constructor(
    private val graphObjectManager: GraphObjectManager
) {
    fun getAllPeople(): List<PersonCareer> {
        return graphObjectManager.loadAll(PersonCareer::class.java)
    }
}
```

#### Load by ID

```kotlin
fun getPerson(uuid: String): PersonCareer? {
    return graphObjectManager.load(uuid, PersonCareer::class.java)
}
```

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

#### Ordering Nested Collections (Database-Side with APOC)

The DSL supports sorting nested relationship collections directly in the database using APOC Extended's `apoc.coll.sortMaps()` function.

**Direct Relationship Sorting:**

```kotlin
// Sort assignees by name within each issue
val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
    where {
        issue.state eq "open"
    }
    orderBy {
        issue.id.desc()           // Root ordering (uses index)
        assignedTo.name.asc()     // Collection sorting (uses APOC)
    }
}
// Each issue's assignedTo list is sorted by name ascending
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
- Collection sorting (e.g., `assignedTo.name.asc()`) wraps the collection with `apoc.coll.sortMaps()`
- Both can be combined in a single query

**Requirements:**
- Collection sorting requires **APOC Extended** plugin (not APOC Core)
- APOC Extended version must match your Neo4j version (e.g., Neo4j 5.26 → APOC Extended 5.26.x)
- Download from: https://github.com/neo4j-contrib/neo4j-apoc-procedures/releases

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

#### Manual Client-Side Sorting

For more complex sort logic (multiple fields, custom comparators), use manual approaches:

**Simple method (zero annotations):**

```kotlin
@GraphView
data class IssueWithAssignees(
    @Root val issue: Issue,
    @GraphRelationship(type = "ASSIGNED_TO")
    val assignees: List<AssigneeWithContext>
) {
    fun sortedAssignees() = assignees.sortedBy { it.person.name }
}
```

**Cached lazy property:**

```kotlin
@GraphView
data class IssueWithAssignees(
    @Root val issue: Issue,
    @GraphRelationship(type = "ASSIGNED_TO")
    val assignees: List<AssigneeWithContext>
) {
    @get:JsonIgnore
    val sortedAssignees: List<AssigneeWithContext> by lazy {
        assignees.sortedBy { it.person.name }
    }
}
```

Note: Drivine's ObjectMapper auto-ignores Kotlin delegate backing fields (`*$delegate`), so you only need `@get:JsonIgnore` on the lazy property.

**Tip:** If using UUIDv7 for IDs, `sortedBy { it.messageId }` gives chronological order since UUIDv7 strings are lexicographically time-ordered.

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
val person = graphObjectManager.load(uuid, PersonCareer::class.java)!!

// Modify it
val updated = person.copy(
    person = person.person.copy(bio = "Updated bio")
)

// Save - only dirty fields are written!
graphObjectManager.save(updated)
```

#### Save with Relationship Changes

```kotlin
val person = graphObjectManager.load(uuid, PersonCareer::class.java)!!

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
graphObjectManager.loadAll(PersonCareer::class.java)
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
// Consumer's implementation
@NodeFragment(labels = ["SessionUser", "AppUser"])
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
        "AppUser|SessionUser",  // Composite label key (sorted alphabetically, pipe-separated)
        AppUser::class.java
    )
    return pm
}
```

The `registerSubtype()` call configures both:
- Drivine's label-based polymorphism for loading
- Jackson's abstract type mapping for save operations

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
