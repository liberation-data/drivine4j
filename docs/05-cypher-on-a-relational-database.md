## Cypher on a relational database

Write a statement in Cypher once, and run it on any store Drivine supports. Neo4j, FalkorDB, Memgraph and Neptune run
it as written. On Postgres, Oracle and SQLite, Drivine compiles it to SQL over the node and edge tables it keeps the
graph in, and it gives the rows a graph engine gives.

### Configuration

SQLite is the quickest to try: the graph is a file, with no server to run. Add `org.xerial:sqlite-jdbc` and name the
file:

```yaml
database:
  datasources:
    graph:
      type: SQLITE
      database-name: /var/data/app.db  # the file, made if it is not there
```

On Postgres, add `org.postgresql:postgresql`:

```yaml
database:
  datasources:
    graph:
      type: POSTGRES
      host: localhost
      port: 5432
      database-name: app
      user-name: app
      password: secret
```

Oracle takes `type: ORACLE` and the properties in [Oracle](09-supported-engines.md#oracle). Without Spring, give `Drivine` the same
properties:

```kotlin
val drivine = Drivine(DataSourceMap(mapOf(
    "graph" to ConnectionProperties(type = DatabaseType.SQLITE, databaseName = "/var/data/app.db")
)))
val manager = drivine.persistenceManager("graph")
```

The graph's tables are made, where they are not there yet, before the datasource's first connection, so the first
statement sent to a new store finds them, with no object manager made first. A user who may read the tables but not
make them works with a store where they are there. The tables are made committing each statement, also on a datasource
whose connections start with `autoCommit = false`. A graph name that can't name tables, such as `my-graph`, makes no
tables: plain SQL still works, a Cypher statement fails with a `DrivineException`, naming the graph and saying why, and
an object manager over the graph is refused with an `IllegalArgumentException` that says the same. A schema catalog for
every database skips the datasource, and one that names it fails.

### A write and a read

Wrap the text in a `CypherStatement`. A plain string is Cypher to a graph engine but SQL to Postgres, Oracle and
SQLite, so the `CypherStatement` is what lets one statement run on every store.

```kotlin
data class Person(val name: String, val city: String)

manager.execute(
    QuerySpecification
        .withStatement(CypherStatement(
            "MERGE (p:Person {name: \$name}) SET p.city = \$city " +
            "MERGE (c:City {name: \$city}) " +
            "MERGE (p)-[:LIVES_IN]->(c)"
        ))
        .bind(mapOf("name" to "Ada", "city" to "London"))
)

val londoners: List<Person> = manager.query(
    QuerySpecification
        .withStatement(CypherStatement(
            "MATCH (p:Person)-[:LIVES_IN]->(:City {name: \$city}) " +
            "RETURN properties(p) AS person ORDER BY person.name"
        ))
        .bind(mapOf("city" to "London"))
        .transform(Person::class.java)
)   // [Person(name=Ada, city=London)]
```

The write is applied whole or not at all on each SQL store. The same read from Java:

```java
List<Person> londoners = manager.query(
    QuerySpecification
        .withStatement(new CypherStatement(
            "MATCH (p:Person)-[:LIVES_IN]->(:City {name: $city}) " +
            "RETURN properties(p) AS person ORDER BY person.name"))
        .bind(Map.of("city", "London"))
        .transform(Person.class));
```

Return one map or one value, as [RETURN Clause Best Practices](04-quick-start.md#important-return-clause-best-practices) says: a
`RETURN` of several items gives a list of them for each row.

### What is compiled

What each statement gives on each store is set out in
[drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec), which all three Drivine libraries run in
their tests. Each release of drivine4j passes the version of it named in that release's
`src/test/resources/cypher/VERSION`, as does each checkout. It covers reads and writes (`CREATE`, `MERGE`, `SET`,
`REMOVE`, `DELETE`), `OPTIONAL MATCH`, aggregation, `UNION`, `CALL { … }`, `EXISTS { … }`, `FOREACH`,
variable-length relationships, quantified path patterns, `shortestPath`, list comprehensions and openCypher's temporal
values.

Cypher the compiler doesn't take is refused by name before anything is sent, with a `DrivineException` such as
`SKIP or LIMIT of an expression is Cypher, and is not compiled to SQL yet.` Text that isn't Cypher is refused with the
place in the statement it stands at.

### Where the stores differ

- A temporal value comes back as its ISO 8601 text, where a graph engine's driver gives a value of its own.
- [Postgres](09-supported-engines.md#postgres) works float arithmetic, `sum()` and `avg()` out in exact decimal, so it is more accurate:
  `0.1 + 0.2` is `0.3` there, where doubles give `0.30000000000000004`. A result differs from Neo4j's only around its
  16th significant digit, and still comes back as a float.
- [SQLite](09-supported-engines.md#sqlite) adds floats in `sum()` and `avg()` as doubles, as Neo4j does, but corrects for rounding as it
  adds, so a long sum of floats can be more accurate than Neo4j's in its last digit.
- [Oracle](09-supported-engines.md#oracle) reads an empty text as null.
- Where Neo4j gives NaN or an infinity from a function (`log(0)`, `sqrt(-1)`), the statement fails on each SQL store.
  A float divided by zero fails on Postgres, gives null on SQLite, and on Oracle is the NaN or
  infinity Neo4j gives.
- NaN and the infinities given as parameters are the floats they are on each SQL store, as on Neo4j: NaN is equal to
  nothing and ordered with nothing, and `+`, `-`, `*` and `/` keep it. Where Neo4j gives NaN or an infinity from `%`,
  `^` or a function of numbers (`abs`, `sqrt` …) given one, Oracle gives null. So does SQLite for NaN, and for an
  infinity it gives null from `%`, the infinity from `^`, `abs`, `round` and `floor`, and fails from `sqrt`, `log` and
  `exp`. Oracle stores them too. SQLite stores an infinity, and
  refuses a statement that writes and is given NaN; Postgres refuses one given either, as its `jsonb` holds neither.
- `<`, `<=`, `>` and `>=` order two lists item by item where the statement makes one of them a list (`n.tags < ['b']`).
  Two values whose kind it leaves open, such as two parameters or two properties, compare as null where they hold
  lists. Two maps compare as null, and two that differ do in a list too, where Neo4j orders them.
- Text is ordered by the database's collation on [Postgres](09-supported-engines.md#postgres), and by `BINARY` on [SQLite](09-supported-engines.md#sqlite).
- `CALL { … } IN TRANSACTIONS` is one statement, applied whole, where a graph engine commits each batch as it goes.
- On Oracle, a quantified path pattern's `WHERE` reads no variable from outside it.

The store sections ([Oracle](09-supported-engines.md#oracle), [Postgres](09-supported-engines.md#postgres), [SQLite](09-supported-engines.md#sqlite)) give the rest.

### Or no Cypher at all

The object manager, `StatelessGraphObjectManager`, loads, saves and queries annotated models on every store with no
Cypher to write. Use it where it does what you need, and keep Cypher for the queries it doesn't: see
[Object Manager](06-object-manager.md#object-manager---type-safe-graph-mapping).

On Postgres and Oracle it is also the faster way. It runs SQL of its own, written for the store's
tables, where a Cypher statement is compiled to SQL first; and on Oracle its statements stay prepared,
where a compiled one is prepared again each time it runs (see [Oracle](09-supported-engines.md#oracle)).

