# The openCypher TCK

The [openCypher TCK](https://github.com/opencypher/openCypher/tree/main/tck) is openCypher's own set of
scenarios: a graph, a query, and the rows, error or side effects the query must give. This build runs all
3,896 of them through Drivine on each store, so it says how much of Cypher each store answers as Neo4j does.
On Postgres, Oracle and SQLite each query goes through Drivine's Cypher compiler; on Neo4j it goes to Neo4j.

It runs openCypher's 2024.2 features (2024.3, the latest release, has the same features) with
`org.opencypher:tck-api_2.13:1.0.0-M23`. The harness is `src/tck/kotlin`.

## Running it

```bash
./gradlew tck -Ptck.store=postgres          # neo4j, postgres, oracle or sqlite; sqlite by default
./gradlew tck -Ptck.store=oracle -Ptck.features=Match1,Return6   # only the feature files whose path holds one of these
```

The other properties: `-Ptck.timeout=30`, the seconds a scenario has before it counts as a stall;
`-Ptck.limit=100`, the first hundred scenarios only; `-Ptck.debug=true`, each query and its rows printed.

The TCK is opt-in. The `tck` source set, its `tck` configuration (which alone holds the TCK's dependencies)
and the `tck` task are left out of `build`, `check` and `test`; `check` compiles the harness and runs
`tckTest` (below), which needs no database. The stores start as the tests' do, from the
images pinned in `gradle/test-images.txt`. A whole run takes about 15 s on SQLite, 50 s on Postgres, 70 s on
Neo4j and 95 s on Oracle, once the store is up.

A run writes, under `build/tck/`:

- `<store>.jsonl`: one line per scenario, with its outcome, the query, and the failure;
- `report.md` and `report.json`: totals and the pass rate of each area of the TCK, for the latest run on each store.

## What it measures

Each scenario comes to one of these:

| outcome | code | means |
|---|---|---|
| passed | `pass` | the rows, error and side effects the TCK expects |
| refused on purpose | `a` | Drivine says it doesn't compile the statement to SQL (yet) |
| wrong result | `b` | rows or side effects other than the expected ones |
| wrong or missing error | `c` | no error where one is expected, or Drivine's own error where the query should run |
| crash or stall | `d` | the database failed on what Drivine sent it, or no answer in time |
| harness limitation | `e` | a step the harness can't run through Drivine: a test procedure, a CSV file |

The comparison is lenient in three ways, as Drivine's results are shaped differently from the TCK's:

- **A node or a relationship is compared by its properties.** Drivine gives a node as the map of its
  properties, so the TCK's expected `(:Label {name: 'a'})` is read as `{name: 'a'}`; labels, types and
  identity aren't compared. A path is compared as its nodes' and relationships' properties.
- **Column names aren't compared.** A row's values are compared in the order of the expected columns.
- **Any error passes where the TCK expects an error.** Drivine has no typed errors, so a `SyntaxError` the TCK
  expects is met by any error at all, at compile time or at run time.

Because of the last one, each run keeps a second, **strict** score beside the lenient one. Where Drivine
compiles to SQL, an expected error counts as a strict pass only when Drivine raised it, before the database;
one the database raised over the SQL Drivine sent is likely a different failure that happens to look right.
On Neo4j the database is the Cypher engine, so its errors count. The report gives both scores, and how many
expected errors came from Drivine and how many from the database. The gate doesn't use the strict score.

## The baseline, the gate and the ratchet

`src/tck/resources/tck-baseline-<store>.json` lists, for each feature file, the scenarios that pass on that
store: each by its place in the feature, `3`, or with its example's place for a scenario outline, `4:0`.

**The gate**, `-Ptck.gate=true`, holds a run to its store's baseline scenario by scenario, never by count, so a
fix in one place can't hide a regression in another. It fails on each scenario that passes in the baseline
and not in the run, and names it with its feature, scenario, query and the first line of its failure:

```
REGRESSED clauses/match/Match4.feature #8: Matching relationships into a list and matching variable length using the list
  query:   MATCH ()-[r1]->()-[r2]->() WITH [r1, r2] AS rs LIMIT 1 MATCH (first)-[rs*]->(second) RETURN first, second
  failure: wrong result (rows): expected List(Map(first -> {}, second -> {})) got List(Map(first -> {}, second -> {}), ...)
Gate on sqlite: 1 regressed, 0 missing, 0 newly passing (baseline 2312).
```

It fails, too, on a scenario of the baseline that the TCK no longer has. What a run gains never fails it. A
run filtered by `tck.features` or `tck.limit` is gated on the scenarios it ran. A baseline records the TCK
version it was written on, features and `tck-api`, and the gate refuses a run of another version: a new
version may add or drop scenarios and so move the places that key them, which would read as regressions.
Moving the TCK to a new version means rewriting each store's baseline with the ratchet alone.

**The ratchet**, `-Ptck.baseline.update=true`, rewrites the baseline of the store that ran from the run, and
says which scenarios of the old baseline the new one drops. It refuses a filtered run, which would forget
every scenario outside the filter. A change that makes scenarios pass commits its store's new baseline, so
the gate holds them from then on:

```bash
./gradlew tck -Ptck.store=postgres -Ptck.baseline.update=true
git add src/tck/resources/tck-baseline-postgres.json
```

With both properties a run is gated first, and the baseline is rewritten only if the gate passes.

`./gradlew tckTest` tests the gate and the ratchet themselves, with no database; `tck` and `check` run it.

**Nightly**, the `tck` job of `.github/workflows/nightly-latest.yml` runs the gate on each of the four stores,
on the pinned images, and a failure opens the nightly run's issue as the other jobs' do. It doesn't run on
pull requests. Each job writes the report to its summary and keeps `build/tck/` as an artifact.

## Pass rates

On `main` when the baselines were written, of 3,896 scenarios. The baselines hold the lenient passes.

| store | lenient pass | strict pass | refused on purpose | wrong result | wrong or missing error | crash or stall | harness limitation |
|---|---:|---:|---:|---:|---:|---:|---:|
| Neo4j | 3,821 (98.1%) | 3,821 (98.1%) | 0 | 2 | 9 | 5 | 59 |
| Postgres | 2,357 (60.5%) | 2,307 (59.2%) | 635 | 183 | 654 | 8 | 59 |
| Oracle | 2,341 (60.1%) | 2,294 (58.9%) | 635 | 204 | 656 | 1 | 59 |
| SQLite | 2,311 (59.3%) | 2,304 (59.1%) | 635 | 171 | 697 | 23 | 59 |

Neo4j is the ceiling: the 16 scenarios it fails beside the harness's 59 are where Neo4j itself differs from
the TCK, an error it doesn't raise or one it raises where the TCK expects rows. On the SQL stores most of the
gap is what Drivine's compiler refuses on purpose (635), its own errors on statements the TCK runs (403 on
Postgres), and statements it runs where the TCK expects an error (251 on Postgres).
`build/tck/report.md` has the pass rate of each area.
