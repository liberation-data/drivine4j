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

A value whose kind the statement fixes (a literal, a list or a map written, or a variable a `WITH` binds to one) is
refused where an operator or a function takes another kind, as Neo4j refuses it when it plans the statement:
`RETURN 123 AND true` fails with `AND takes a truth value, not an integer, as in Neo4j.`, and so do
`WITH 123 AS x RETURN x.num` and `RETURN 1 IN 'foo'`. A step of 0 in `range()`, written or given, a parameter given as
the index of a list or a map written that isn't indexed by its kind, as in `WITH [1] AS l RETURN l[$k]` given a text,
and a map, or a list of maps or of lists, written in `SET`, are refused too, though Neo4j fails on them only when a row
reaches them: such a statement is refused even where it would match nothing. A statement is compiled for each call,
with the parameters it is given, so a value given refuses that call alone. A value whose kind is known only when the
row is read, such as a property or a parameter, is compiled: where it is of the wrong kind, the statement may give null
where Neo4j fails.

A variable used as two kinds is refused too, as Neo4j refuses it: `MATCH (a)-[a]->()` or `WITH true AS n MATCH (n)`
fails with `a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.` So is one a pattern
makes where it is bound already: `MATCH (a) CREATE (a)`, a path variable bound before its `MATCH`, or a relationship
variable bound as something else.

### How complete is it?

The [openCypher TCK](https://github.com/opencypher/openCypher/tree/main/tck) is openCypher's own test suite: 3,896
scenarios, each a graph, a query, and the rows or the error the query must give.
drivine4j runs all of them through its compiler on each store; the TypeScript and Python compilers follow it through
the shared spec, though `truncate()` with a map of fields, which 221 of these scenarios call, is still to be ported to
them ([drivine#442](https://github.com/liberation-data/drivine/issues/442),
[drivine-python#265](https://github.com/liberation-data/drivine-python/issues/265)). The scores below are `main` on
31 October 2026.

| store | scenarios passed | of all 3,896 | leaving out the 583 refused on purpose |
|---|---:|---:|---:|
| Postgres | 2,905 | 74.6% | 87.7% |
| Oracle | 2,887 | 74.1% | 87.1% |
| SQLite | 2,869 | 73.6% | 86.6% |
| Neo4j | 3,821 | 98.1% | (none refused) |

The 583 refused on purpose are Cypher the compiler doesn't take yet, refused by name with a clear error: temporal values
built from a week, a quarter or another date, a truncation that sets nanoseconds or a named time zone, `rand()`, a path
as a value, and a few more.

Most of the rest are not wrong answers. Of the 408 left on Postgres:

- 172 are in openCypher's temporal features, which are being compiled now: 130 give another result, and 42 write a date
  or a time in a form Drivine doesn't read yet, such as `2015-W30-2`, `20150721` or a named zone, and fail with an error
  of Drivine's own.
- 116 are about errors: 77 statements that run where the TCK expects an error, and 39 that Drivine fails where the TCK
  expects rows.
- 59 are steps the harness can't run through Drivine, such as a test procedure or a CSV file.
- 53 give another result outside the temporal features, and 8 make the database fail.

Oracle's and SQLite's are split alike. The comparison is lenient where Drivine's results are shaped differently from
the TCK's: a node is compared by its properties, and any error meets an expected one. Counting an expected error only where Drivine
raised it, before the database, Postgres passes 2,865, Oracle 2,852 and SQLite 2,862.

Neo4j runs the same scenarios through the same harness and misses only the 59 it can't run and 16 where Neo4j itself
differs from the TCK, so the harness is sound and the gap on the SQL stores is the compiler's. Every night the build
holds each store to the scenarios it passed, one by one, so none can slip back unnoticed.
[TCK.md](../TCK.md) has the method and how to run it.

### Where the stores differ

- A temporal value comes back as its ISO 8601 text, where a graph engine's driver gives a value of its own.
- [Postgres](09-supported-engines.md#postgres) works float arithmetic, `sum()` and `avg()` out in exact decimal, so it is more accurate:
  `0.1 + 0.2` is `0.3` there, where doubles give `0.30000000000000004`. A result differs from Neo4j's only around its
  16th significant digit, and still comes back as a float.
- [SQLite](09-supported-engines.md#sqlite) adds floats in `sum()` and `avg()` as doubles, as Neo4j does, but corrects for rounding as it
  adds, so a long sum of floats can be more accurate than Neo4j's in its last digit.
- [Oracle](09-supported-engines.md#oracle) reads an empty text as null.
- A `sum()` of integers past a long's range is a float on Oracle and Postgres, as on Neo4j. Neo4j goes on as a
  double from the row where a long would overflow, so a sum that passes the range on the way and comes back into it,
  `[9223372036854775807, 1, -1]`, is a float there and a long on Oracle and Postgres, which keep no order and tell the
  total alone. SQLite fails with `integer overflow` wherever a running total passes the range.
- Where Neo4j gives NaN or an infinity from a function (`log(0)`, `sqrt(-1)`), the statement fails on each SQL store,
  as JSON holds neither. A float divided by zero fails on Postgres, and gives null on SQLite.
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

