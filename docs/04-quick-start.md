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

### 4. Other frameworks, or none

The core library depends only on what it uses, so it also runs outside Spring: in a plain `main`, a Ktor or Micronaut
server, a batch job or a CLI. Make one `Drivine`, which builds what the starter builds as beans (the registry, the
factories and the transactions), and hand its managers to your classes:

```kotlin
fun main() {
    Drivine(
        DataSourceMap(
            mapOf(
                "neo" to ConnectionProperties(
                    type = DatabaseType.NEO4J,
                    host = "localhost",
                    port = 7687,
                    userName = "neo4j",
                    password = "password",
                    databaseName = "neo4j"
                )
            )
        )
    ).use { drivine ->                                   // close() ends every database's connections
        val people = PersonService(drivine.graphObjectManager("neo"), drivine)
        people.hire(Person(id = "ada", name = "Ada"))
        println(people.find("ada"))
    }
}

class PersonService(private val gom: StatelessGraphObjectManager, private val drivine: Drivine) {

    fun find(id: String): Person? = gom.load<Person>(id)  // outside a transaction each statement commits as it runs

    fun hire(person: Person) = drivine.transaction {
        gom.save(person)
        gom.save(Hire("hire-${person.id}", person.id, LocalDate.now()))
    } // both commit here; a throw out of the block rolls both back
}
```

`drivine.transaction { }` takes the propagations `@Transactional` does: see [Transaction Management](07-core-features.md#transaction-management).
`drivine.persistenceManager("neo")` gives the `PersistenceManager`, and `drivine.persistenceManagerFactory`,
`drivine.graphObjectManagerFactory` and `drivine.transactions` are there to hand on to code that wants them.

With Ktor, a block is blocking work, so run it off the event loop. For a transaction that suspends, see
[Transactions in coroutines](07-core-features.md#transactions-in-coroutines-drivine4j-coroutines).

```kotlin
fun main() {
    val drivine = Drivine(DataSourceMap(mapOf("neo" to ConnectionProperties(/* … */))))
    val people = PersonService(drivine.graphObjectManager("neo"), drivine)

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { jackson() }
        monitor.subscribe(ApplicationStopped) { drivine.close() }
        routing {
            get("/people/{id}") {
                val person = withContext(Dispatchers.IO) { people.find(call.parameters["id"]!!) }
                if (person == null) call.respond(HttpStatusCode.NotFound) else call.respond(person)
            }
            post("/people") {
                val person = call.receive<Person>()
                withContext(Dispatchers.IO) { people.hire(person) }
                call.respond(HttpStatusCode.Created)
            }
        }
    }.start(wait = true)
}
```

Here `Person` and `Hire` are fragments:

```kotlin
@NodeFragment(labels = ["Person"])
data class Person(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["Hire"])
data class Hire(@NodeId val id: String, val personId: String, val on: LocalDate)
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

