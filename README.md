# Drivine4j

A Neo4j client library for Java and Kotlin. A typical ORM defines a reusable object model. From this model  
statements are generated to hydrate to and from the model to the database. This is to address the so-called
impedance mismatch between the object model and the database. However, there are some drawbacks: 

* Generated work well for the simple cases, but can get out of hand and performance degrades when it's more complex. Debugging these generated statements can be painful. 
* One model for many use cases is a big ask, the original CRUD cases work well, but more copmlex cases mean the model gets in the way more than helps. 

These trade-offs might be acceptable if the tool addresses the impedance mismatch between an relational database and programming object model, but with a graph DB this mismatch is really there. 

## Drivine Philosophy 

Just as we favor composition over inheritance in software development, we prefer composition when mapping results from a complex query. Consider this type: 

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class HolidayingPerson(
    val person: Person,
    val holidays: List<Holiday>
)
```

A person can play many roles. Sometimes we're here to help them have a great holiday, other times to manage a team, in others they're a person of interest. No worries, we can compose a mapping in Cypher itself: 

```cypher
MATCH (person:Person {firstName: $firstName})
WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.*}] AS holidays
RETURN {
         person:   properties(person),
         holidays: holidays
       }
```
Composition lets us mix and match as needed.  


## Installation

### Gradle (Kotlin DSL)
```kotlin
implementation("org.drivine:drivine4j:0.0.1-SNAPSHOT")
```

### Gradle (Groovy)
```groovy
implementation 'org.drivine:drivine4j:0.0.1-SNAPSHOT'
```

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
    @Qualifier("neo") val manager: PersistenceManager
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
        val props = ObjectUtils.primitiveProps(person)
        return manager.getOne(
            QuerySpecification
                .withStatement<Any>("CREATE (p:Person) SET p = \$props RETURN properties(p)")
                .bind(mapOf("props" to props))
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

## Core Features

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
    @Qualifier("neo") val manager: PersistenceManager,
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

### ObjectUtils

```kotlin
// Convert Kotlin object to Map for Neo4j
val props: Map<String, Any> = ObjectUtils.primitiveProps(
    obj = person,
    includeNulls = false  // Omit null properties
)
```

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

### With TestContainers

```kotlin
@Configuration
class TestConfig {
    @Bean
    @Profile("!local")
    fun neo4jContainer(): Neo4jContainer<*> {
        return Neo4jContainer("neo4j:5.28")
            .apply { start() }
    }

    @Bean
    fun dataSourceMap(container: Neo4jContainer<*>): DataSourceMap {
        val props = ConnectionProperties(
            host = container.host,
            port = container.getMappedPort(7687),
            username = "neo4j",
            password = container.adminPassword
        )
        return DataSourceMap(mapOf("neo" to props))
    }
}
```

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

- GitHub: https://github.com/your-org/drivine4j
- Issues: https://github.com/your-org/drivine4j/issues
