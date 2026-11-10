# The schema-statement golden

What each test that exercises schema management sends to each store, statement by statement, recorded from today's
code on real stores (Neo4j, Memgraph, FalkorDB, Postgres, Oracle, SQLite). It pins the schema statements while schema
management becomes a driver over the compiler module's planner (liberation-data/drivine-cypher-spec#135): after the
switch, the same tests must send the same sequence.

## Recording and checking

```bash
# Record into a directory (relative to the project), then copy it here:
./gradlew :test --tests '<the classes>' -Pdrivine.schema.golden.record=build/schema-golden/run1
# Check against this directory; each test fails with the first difference. The live run is written to build/schema-golden/live.
./gradlew :test --tests '<the classes>' -Pdrivine.schema.golden.check=true
```

Run the classes in `classes.txt`, all of them and only them, with `ORACLE_TESTS=true` and FalkorDB 6.0.3
(`FALKORDB_TEST_IMAGE=falkordb/falkordb:6.0.3`): what a test sends depends on what the classes before it left on the
shared stores, so a run of other classes, or of a subset, gives another sequence. With either property set, classes run
in the order of their names (`ClassOrderer.ClassName`), whatever the file system lists them in. Neither property set,
no observer is set and nothing is kept.

`org.drivine.schema.SchemaStatementTrace` is where every statement is told: `SchemaExecutor` (the schema connection,
and FalkorDB's native commands), `SchemaVersionStore`'s marker statements and `LowerCopyCatalog`'s fill and record (on
the manager they are given), and the loops that poll a build or a drop. `org.drivine.schema.golden.SchemaStatementGolden`
records (a launcher `TestExecutionListener` keys what runs by the test running) and checks (a Jupiter extension, which
the build has the platform find only with the check property on).

## Format

One file per store, `<store>.jsonl`, named by `DatabaseType` in lower case. One line per entry, the tests sorted by key,
each test's entries in the order it sent them:

```json
{"test":"[class:org.drivine.schema.SchemaManagerIntegrationTest]/[method:enforce creates declared items and is idempotent()]",
 "n":3,"path":"schema-connection","kind":"statement","statement":"CREATE RANGE INDEX …","parameters":{},"outcome":"ran"}
```

- `test`: the JUnit unique id without its engine segment. What runs in a class but in none of its tests (`@BeforeAll`,
  `@AfterAll`, a fixture that ensures schema once for the class) is keyed by the class, `[class:…]`; a test template's
  invocations and dynamic tests by their own ids.
- `n`: the entry's place in what the test sent to the store, from 1. Absent (`null`) for an entry from another thread.
- `path`: `schema-connection` (a connection of its own, through `SchemaExecutor`), `auto-commit` (the auto-commit
  manager, `NonTransactionalPersistenceManager`), or `manager` (any other manager a caller gave).
- `kind`: `statement`, `native` (FalkorDB's `GRAPH.CONSTRAINT …`, its arguments joined by spaces, the graph as
  `{graphName}`), or `poll` (a polling loop, with `statement` naming what it waited for).
- `statement`: the text sent. One longer than 300 characters (the SQL stores' graph DDL, Oracle's PL/SQL blocks) is
  `sha256:<first 16 hex digits>` of its normalised text, and the text is in `<store>.statements.jsonl`, once, as
  `{"hash": …, "statement": …}`.
- `parameters`: the statement's parameters, keys sorted.
- `outcome`: `ran`, `failed: <exception class>` (a statement whose failure Drivine handles, such as an introspection it
  treats as none, is recorded so), or for a poll how it ended.

A test that sent nothing has no line, and is checked as sending nothing.

## Normalisations

- **UUIDs** in statements and parameters are `<uuid-1>`, `<uuid-2>`, … by first appearance in the test.
- **The clock.** A parameter named `now`, `appliedAt`, `applied_at`, `at` or `assignedAt` is `<now>`, as is an integer
  parameter between 1.5e12 and 4.1e12 (epoch milliseconds: the marker's moment on the SQL stores), and an ISO-8601
  instant in a string is `<instant>`. Neo4j's `db.awaitIndex` timeout is the configured one, not the clock's, and is kept.
- **Random graph names.** `SchemaDdlCrossEngineTest` gives each test a graph named `ddl` and a random number in base 36:
  each such name, in any case, is `<graph-1>`, `<graph-2>`, … by first appearance in the test (the names Oracle and
  Postgres derive from it keep their suffixes, which hash the label and properties, not the graph).
- **Temporary paths.** The JVM's temporary directory (an SQLite file's) is `<tmp>`.
- **Polls.** A loop that polls until a build or a drop ends (FalkorDB's index build and drop, a constraint's build on
  FalkorDB) is one entry, `kind: poll`, whatever the number of rounds: the statements it ran are left out, and
  `outcome` says how it ended (`built`, `failed`, `still building`, `absent`, `dropped`, or `failed: <exception class>`).
- **Other threads.** What threads other than the test's own sent (a test that applies schema from several threads at
  once) is kept after the test's own entries, each distinct entry once, sorted, with no `n`: their order and number
  are the scheduler's. Their `outcome` is `null`: which of them is turned away and tries again is the scheduler's too.
- **Background catalog reads** (`drivine-catalog-*` threads: `LowerCopyCatalog`'s and Oracle's labelled-index reads
  again, which a save or a load starts on a timer) are left out: they are no part of schema management.
- **Fake stores.** A `RecordingStore` has no store behind it, and is left out.
