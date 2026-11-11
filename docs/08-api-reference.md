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
    fun queryResult(spec: QuerySpecification<*>): QueryResult                 // the columns and rows as they are, nodes with their labels
    fun explain(spec: QuerySpecification<*>): String                          // the SQL a Cypher statement compiles to, nothing run
    val atomicBatches: Boolean                                                // whether a batch of several statements is applied whole
}
```

`executeBatch` and `queryBatch` run their statements in one transaction on the managers Drivine provides, or in the transaction they are called in. FalkorDB has no transactions: there each statement commits by itself, `atomicBatches` is false, a batch of one statement runs with no transaction, and a batch of more runs statement by statement (`WARN`) or is refused with `NonAtomicBatchException` before any of it runs (`STRICT`). The interface's own defaults run each statement on its own, so a `PersistenceManager` you implement must override both to make a batch atomic; `saveAll` calls `queryBatch`.

`queryResult` gives a statement's result for showing rather than mapping, its columns, its rows and, on Neo4j and Memgraph, its `counts` of what it wrote: see [Results as they are](07-core-features.md#results-as-they-are-queryresult). A `PersistenceManager` or `Connection` you implement refuses it with `UnsupportedOperationException` unless it overrides it.

`explain` gives the SQL a Cypher statement compiles to on Postgres, Oracle or SQLite, with nothing run: what the store would run for it, its parameters bound apart. A graph engine, which runs Cypher itself, refuses it, as a `PersistenceManager` or `Connection` you implement does unless it overrides it. The drivine CLI's `--explain` prints it.

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
    val type: DatabaseType,                  // NEO4J, FALKORDB, NEPTUNE, MEMGRAPH, ORACLE, POSTGRES, SQLITE
    val host: String = "",                   // not read on SQLite, a file
    val port: Int? = null,                   // the engine's own when null: 1521 on Oracle, 5432 on Postgres
    val userName: String? = null,
    val password: String? = null,            // printed as *** by toString, so logging the properties doesn't show it
    val idleTimeout: Int? = null,
    val connectionTimeout: Int? = null,
    val poolMax: Int? = null,
    val databaseName: String? = null,        // on Oracle, the service to connect to; on Postgres, the database; on SQLite, the file's path
    val defaultGraphPath: String? = null,    // on Oracle, Postgres and SQLite, the name of the graph and its tables
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

> An object of a class with a [`@NodeStamp`](06-object-manager.md#cypher-you-write-yourself) field is bound with its stamp under the field's own name, not under `__drivine.stamp`. `SET n = $props` removes the node's stamp, and `SET n += $props` writes a stray property and leaves the stamp unmarked, so a checked save does not notice the write.

> This governs the low-level `PersistenceManager` binding only. Whether a null field **clears** a
> property on an object-manager `save`/`saveAll` is governed by
> [`NullPolicy`](06-object-manager.md#null-write-policy-nullpolicy) (default `IGNORE` — nulls are left untouched).

