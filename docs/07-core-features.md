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

`DrivineTransactionManager` is a Spring `PlatformTransactionManager`, so everything Spring does with transactions
works with Drivine: `@Transactional` with any propagation but `NESTED`, `TransactionTemplate`, `@Rollback` in tests,
and other transaction managers in the same application (name Drivine's with `@Transactional("drivineTransactionManager")`
where there are several). The starter registers it, and so does `@EnableDrivine`.

Object-manager saves join these transactions: see [Saves and transactions](06-object-manager.md#saves-and-transactions) for what a refused save does to one.

#### Transactions in code (`Transactions`)

To run a block in a transaction without an annotated method, inject `Transactions` (the starter makes the bean; outside
Spring, `drivine.transactions`). Its blocks and `@Transactional` methods share one transaction, either way round: a
`@Transactional` method called from a block joins the block's transaction, and a block run in a `@Transactional`
method joins Spring's.

```kotlin
@Component
class Onboarding(private val transactions: Transactions, private val gom: StatelessGraphObjectManager) {

    fun onboard(person: Person, hire: Hire) = transactions.transaction {
        gom.save(person)
        gom.save(hire)
    } // both commit here; a throw out of the block rolls both back

    fun audit(hire: Hire) = transactions.transaction(Propagation.REQUIRES_NEW) { gom.save(hire) } // commits whatever the caller does
}
```

`transaction { }` returns what the block returns, and takes a propagation, with the meaning Spring gives it:

| `propagation` | With a transaction in progress | Without one |
|---|---|---|
| `REQUIRED` (the default) | joins it | begins one |
| `REQUIRES_NEW` | sets it aside, and begins one | begins one |
| `SUPPORTS` | joins it | runs without one |
| `MANDATORY` | joins it | refuses: `IllegalTransactionStateException` |
| `NEVER` | refuses: `IllegalTransactionStateException` | runs without one |
| `NOT_SUPPORTED` | sets it aside, and runs without one | runs without one |

`NESTED` is refused, with a transaction or without one (`DrivineTransactionManager` refuses it in a transaction, and
without one Spring runs it as `REQUIRED`). Without a transaction, each statement commits as it runs.
`transaction(rollback = true) { }` rolls back though nothing failed: for a test.

- The block that began the transaction commits it when it returns, and rolls it back when anything is thrown out of
  it, a checked exception too.
- A block that joins a transaction leaves its end to the code that began it. When the joining block throws and the
  caller catches it and carries on, the transaction still commits, as it does for a `@Transactional` method.
- The transaction is the thread's, as Spring's is. A block can't suspend, so in a coroutine it stays on one thread. For
  a transaction across suspension points, see [Transactions in coroutines](#transactions-in-coroutines-drivine4j-coroutines).
- Begin the transaction with Spring where you rely on Spring's transaction callbacks (`@TransactionalEventListener`,
  `TransactionSynchronization`): Spring doesn't see a block's commit, so a `@Transactional` method that joins a block's
  transaction runs them when the method returns.
- On FalkorDB, which commits each statement as it runs, the `falkorDbTransactionMode` says what a block does: in
  `WARN` it runs and a rollback undoes nothing, in `STRICT` its first statement is refused.

From Java, the block is a `Callable`, and `transactionWithoutResult` takes a `Runnable`:

```java
Person saved = transactions.transaction(() -> gom.save(person));
transactions.transactionWithoutResult(Propagation.REQUIRES_NEW, false, () -> gom.save(hire));
```

#### Transactions in coroutines (`drivine4j-coroutines`)

A transaction is the thread's, and a coroutine can resume on another thread after any suspension point. For Kotlin
coroutines, add the small `drivine4j-coroutines` module, which brings `kotlinx-coroutines-core` (the core library and
Java applications don't need it):

```kotlin
dependencies {
    implementation("org.drivine:drivine4j-coroutines:0.3.0")
}
```

It gives `Transactions` (and `Drivine`) a suspend form of the block, `transactionSuspending { }`. Its transaction is
kept in the coroutine's context and put on whichever thread the coroutine resumes on, so the block can suspend or
switch dispatcher and stay in it, and the blocking code underneath, the managers, `@Transactional` methods and
`transaction { }` blocks, finds it as it always has. With Spring, inject `Transactions` as above and use both side by
side:

```kotlin
@Service
class Onboarding(
    private val transactions: Transactions,
    private val gom: StatelessGraphObjectManager,
    private val audit: AuditService,                 // its methods are @Transactional
) {
    suspend fun onboard(person: Person, hire: Hire) = withContext(Dispatchers.IO) {
        transactions.transactionSuspending {
            gom.save(person)
            audit.record(hire)                       // @Transactional: joins the block's transaction
            notifyPayroll(hire)                      // a suspend call: the block resumes on any thread, still in it
            gom.save(hire)
        } // all of it commits here; a throw out of the block rolls it all back
    }

    @Transactional
    fun rehire(person: Person, hire: Hire) = runBlocking {
        transactions.transactionSuspending {         // on this thread: joins the transaction @Transactional began
            gom.save(person)
            gom.save(hire)
        }
    }
}
```

Spring's `@Transactional` on a `suspend fun` is not Drivine's to run: Spring hands those to a reactive transaction
manager, and `DrivineTransactionManager` is the imperative one. Under Spring, `transactionSuspending { }` is the way to
run a transaction across suspension points, and it nests with `@Transactional` and `TransactionTemplate` either way
round: a `@Transactional` method called in the block joins the block's transaction, and a block run on a thread where
`@Transactional` or `TransactionTemplate` began one (through `runBlocking`) joins that. It finds that transaction only on
that thread: called after a `withContext(Dispatchers.IO)`, as `onboard` does, it begins one of its own.

With Ktor, or no framework, it is `drivine.transactionSuspending { }`:

```kotlin
fun main() {
    val drivine = Drivine(DataSourceMap(mapOf("neo" to ConnectionProperties(/* … */))))
    val gom = drivine.graphObjectManager("neo")

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { jackson() }
        monitor.subscribe(ApplicationStopped) { drivine.close() }
        routing {
            post("/people") {
                val person = call.receive<Person>()
                withContext(Dispatchers.IO) {        // off the event loop: the saves and the commit block
                    drivine.transactionSuspending {
                        gom.save(person)
                        delay(10)                    // may resume on another thread, still in the transaction
                        gom.save(Hire("hire-${person.id}", person.id, LocalDate.now()))
                    }                                // both commit, or neither
                }
                call.respond(HttpStatusCode.Created)
            }
        }
    }.start(wait = true)
}
```

- It takes the propagations and `rollback` that `transaction { }` does, and commits and rolls back as it does. Drivine's
  calls are blocking calls, and so are the commit and the rollback, which run where `transactionSuspending` was
  called: call it on a dispatcher for blocking work, such as `Dispatchers.IO`, as above. The block can switch
  dispatcher with `withContext`, and stays in the transaction.
- A coroutine that runs on a thread the block has left doesn't find the block's transaction there: it finds what the
  thread was in before the block, usually none.
- **Coroutines launched in the block (`launch`, `async`, and what is built on them, such as `flowOn`) don't share its
  transaction**: two of them using one transaction's connection at once isn't safe on every driver. A statement such a
  coroutine runs, or a block it runs that would join the transaction, is refused with
  `IllegalTransactionStateException`. Run that work in the block itself, one step after another, or give each
  coroutine a transaction of its own with `transactionSuspending(Propagation.REQUIRES_NEW) { }` (or
  `@Transactional(propagation = REQUIRES_NEW)`). A launched coroutine that doesn't reach Drivine runs as it would
  anywhere. The one way round this is to hand `launch` the block's whole context, as in `launch(coroutineContext) { }`:
  kotlinx then treats it like `withContext`, and the coroutine shares the transaction. Don't.
- Likewise two coroutines that both find a transaction on the thread, launched by `runBlocking` in a `@Transactional`
  method say, can't run blocks in it at once: the second is refused.
- On FalkorDB, `falkorDbTransactionMode` says what a block does, as for `transaction { }`.

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

