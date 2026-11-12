# The schema-statement golden

What each test that exercises schema management sends to each store, statement by statement, recorded from today's
code on real stores (Neo4j, Memgraph, FalkorDB, Postgres, Oracle, SQLite). It pins the schema statements while schema
management becomes a driver over the compiler module's planner (liberation-data/drivine-cypher-spec#135): after the
switch, the same tests must send the same writes. The files are as recorded from the managers before the switch, and are
not recorded again but for a change that is meant to change what is written: Postgres's, Oracle's and SQLite's were
recorded again for the forms (below), and the graph engines' are still the managers' own.

## Recording and checking

```bash
# Record into a directory (relative to the project), then copy it here:
./gradlew :test --tests '<the classes>' -Pdrivine.schema.golden.record=build/schema-golden/run1
# Check the writes against this directory; each test fails with the first difference. The live run is written to build/schema-golden/live.
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

## What is checked: the writes

Everything is recorded, and the check compares only the writes: what each test sent that changes the store, in order
(liberation-data/drivine-cypher-spec#135: "C's golden pins every write"). The planner reads less than the managers did:
a listing is read once until its own DDL runs, the `prepare` block runs once for a plan rather than once for each owner,
FalkorDB's range-index listing is read ahead for a constraint call, and a poll after an index already built is gone. So
the reads, the `prepare` block and the polls are left out of both sides, and every write is pinned.
`SchemaStatementGolden.writes` is the rule:

- **Left out:**
  - every `poll` entry;
  - the reads: a statement that begins `SHOW`, `SELECT`, or a `CALL` other than Neo4j's `db.awaitIndex` (the listings,
    an index's state, the SQL stores' made test and record reads), and a `MATCH`, `OPTIONAL MATCH`, `UNWIND` or `WITH`
    with no `CREATE`, `MERGE`, `SET`, `DELETE`, `DETACH`, `REMOVE`, `INSERT`, `UPDATE` or `DROP` in it (the samples, the
    marker's and the scopes' reads, the lowercased copies' record read);
  - the `prepare` block: the statement the module's marker needs made first for the graph it names, word for word
    (`MarkerStatements.prepare`: the graph's `ensuring` on Postgres and Oracle, its table of nodes on SQLite). On SQLite
    that table's `CREATE TABLE IF NOT EXISTS` is left out wherever it is sent, as the text can't tell where it came from;
    the graph's other tables are kept.
- **Kept:** everything else, each entry whole but for its place (`n`, which the reads left out would move): the DDL,
  FalkorDB's native commands, Neo4j's `db.awaitIndex`, the marker's merge and deletes, the lowercased copies' fill
  and record (SQLite's fill is a `SELECT` of its `drivine_lower_copies` function, and is kept), the SQL stores' blocks
  (`DECLARE`, `BEGIN`, `DO`), the records and aliases they write, and what other threads sent.

A write left out, added, changed (its text, parameters, path or outcome) or moved fails the test.

## Recorded again: a uniqueness constraint made again in its place (drivine-cypher-spec#189)

`postgres.jsonl`, `oracle.jsonl` and `sqlite.jsonl`, with their `statements.jsonl`, were recorded again on 2026-11-12 with
the compiler at 0.44.0, the classes of `classes.txt`, `ORACLE_TESTS=true` and FalkorDB 6.0.3, through the lock. #189
changes what is written on the SQL stores where a uniqueness constraint is made, dropped or made again:

- before it is made or dropped, an index of a recreate cut off part-way, `<name>_r`, is dropped (`leftover`);
- a recreate builds the constraint's unique index beside the old one under `<name>_r`, then swaps it in (`build`,
  `swap`), and writes the record again (`remade`), where it dropped the old index, forgot its record and made it anew;
- on Oracle, where the build is refused as the old index's own (ORA-01408), the old index is dropped first, then made.

The graph engines' files were not recorded again: nothing they are sent changed. The writes of the old files and the new
were compared test by test (`SchemaStatementGolden.writes`' rule):

| Store | Tests | Same | Differ | New | What differs |
|---|---|---|---|---|---|
| Postgres | 211 | 192 | 16 | 3 | the leftover's drop alone in 14; the build, swap and record of a recreate in the other 2 |
| Oracle | 216 | 196 | 17 | 3 | the leftover's drop alone in 14; a recreate in the other 3, a refused build followed by the drop and the make where the old index is the build's own |
| SQLite | 182 | 166 | 11 | 5 | the leftover's drop alone in 9; a recreate in the other 2, the swap being SQLite's drop, make again and drop of `<name>_r` |

The new tests are the three safe-recreate tests on each store (a recreate that succeeds, one the data refuses at its
build, and one whose refill of the lowercased copies the old index refuses) and, on SQLite, #770's two tests, which now
have lines of their own. No test lost a write that is not a recreate's old drop, forget or make.

## Recorded again: the forms (drivine-cypher-spec#186)

`postgres.jsonl`, `oracle.jsonl` and `sqlite.jsonl`, with their `statements.jsonl`, were recorded again on 2026-11-11 with
the compiler at 9631bd9 (#135's PR D), the classes of `classes.txt`, `ORACLE_TESTS=true` and FalkorDB 6.0.3, through the
lock. PR D changes what is written on the SQL stores: every record the module writes starts its `details` with
`"form": 1`, in Oracle's `MERGE` (its insert and its `WHEN MATCHED` update), in the update of a range index a uniqueness
constraint's index serves, and in Postgres's and SQLite's inserts; and an item of the current form whose record says
none has its form recorded, once (`formRecorded`). The graph engines' files were not recorded again: nothing they are
sent changed.

The writes of the old files and the new were compared test by test (`SchemaStatementGolden.writes`' rule), with every
`"form": 1` taken out of the new and the one-off form updates left aside:

| Store | Tests | Same | Differ only by the form | Otherwise |
|---|---|---|---|---|
| Postgres | 208 | 135 | 73 | none |
| Oracle | 210 | 134 | 75 | one: what other threads sent in `StatelessStoreIdentityOracleTest`'s concurrent first boots, the same entries in another order, as they are sorted by their text, which the form changes |
| SQLite | 177 | 125 | 52 | none |

No test leaves a record with no form for an item of the current form, so there is no one-off form update in any of them.
Three Oracle tests are new: `OracleSchemaManagementIntegrationTest`'s two #770 tests, which were `@Disabled` and now pass
(another index under Drivine's name is drift, and a recreate drops it by its name and makes the item), and the
`StatelessStoreIdentityOracleTest` test below, still not checked. The reads the new files hold are the planner's, which
reads less than the managers did; the check leaves the reads out, as before.

## Not checked

Only the classes in `classes.txt` are checked: a run of the whole suite with the check on records every class, and
checks those the golden was recorded in, so a class outside them (the Cypher spec's write cases, which make indexes
through compiled DDL, among them) fails nothing. Within them, the tests below are not checked
(`SchemaStatementGolden.UNCHECKED` names each, with the reason), and their lines, where they have any, are kept as
recorded.

Added after the golden was first recorded, so the managers' record has nothing of them:

- `StatelessStoreIdentityOracleTest`, "concurrent first boots without the constraint agree on one identity over a full
  node table on Oracle": it came with the merge of main (#755, for drivine4j#517). The forms' recording has its lines,
  which are kept as recorded and not checked.
- `SqliteSchemaManagementTest`, "an index over something else under Drivine's name is drift, not made, and a recreate
  replaces it", and "a table under Drivine's name for an index is drift, which a recreate leaves, and says so": they came
  with the forms (drivine4j#770), after the SQL stores' files were recorded again.

Three tests were rewritten when schema management became a driver over the planner, because they used the internal
`IndexBuild`, which is gone. What they run now is not what was recorded:

- `Neo4jEnsureAwaitsIndexesTest`, "a wait that fails for a reason other than the build is thrown as it is, not taken for
  a failed build": it ensured with an `IndexBuild` of 5 seconds; it now ensures with the manager's own wait, so
  `db.awaitIndex` is given a timeout of 60, not 5.
- `Neo4jEnsureAwaitsIndexesTest`, "a wait that fails for a reason other than its timeout, with the index still POPULATING,
  is an index still being built": it ensured through an `IndexManager` with `IndexBuild(ZERO)` and a handler for the
  index not built; it now applies an owner under `WARN`, which writes the owner's marker and clears it after.
- `FalkorDbEnsureAwaitsIndexesTest`, "an index FalkorDB lists as FAILED is an error naming it, not an index taken as
  built": its second ensure went through an `IndexManager` with an `IndexBuild` and a handler; it now applies an owner
  under `WARN`, which writes the owner's marker and clears it after.

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

A test that sent nothing has no line, and is checked as sending no writes.

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
