# drivine4j-cli

The `drivine` command line: one Cypher statement against any store Drivine reaches, Neo4j, Memgraph, FalkorDB,
Postgres, Oracle or SQLite, printed as `plain` or `json`. On Postgres, Oracle and SQLite, Drivine compiles the Cypher to
SQL, as it does in an application. Its contract is drivine-cypher-spec's
[`cli/README.md`](https://github.com/liberation-data/drivine-cypher-spec/tree/main/cli) (liberation-data/drivine-cypher-spec#99).
This is the first skeleton of it, for liberation-data/drivine4j#706: files, stdin, the REPL, profiles, `:commands`,
`-P`, `--explain`, `table` and `csv` come later.

## Build

```
./gradlew :drivine4j-cli:installDist
```

That gives `drivine4j-cli/build/install/drivine/bin/drivine`, a script that runs the CLI on a JDK 21 or later. The
native-image build, one binary per OS, and the CI that tests it on every store come in
[#706](https://github.com/liberation-data/drivine4j/issues/706) and [#707](https://github.com/liberation-data/drivine4j/issues/707).
The module isn't published.

## Run

```
drivine -a sqlite::memory: --format json 'RETURN 1 AS n'
{"columns":["n"],"rows":[[1]]}

DRIVINE_PASSWORD=… drivine -a postgresql://localhost:5432/graphs -u drivine --format plain 'MATCH (n) RETURN count(n) AS c'
```

The address is `-a`'s, else `DRIVINE_ADDRESS`'s; the user `-u`'s, else the address's, else `DRIVINE_USER`'s; the
password `DRIVINE_PASSWORD`'s, never a flag's. `drivine --help` lists the flags. The exit code is 0 when everything ran,
1 when the statement failed, 2 for a bad invocation and 3 when the store can't be reached or refuses the user.

## Tests

`./gradlew :drivine4j-cli:test` runs the CLI in process on SQLite in memory, with no container, and the contract's
cases (`src/test/resources/cli/cli.yaml`, copied from drivine-cypher-spec as `cli/VERSION` says) that this build can
run. The tests against the other stores start containers and are opt-in: `CLI_STORE_TESTS=true` runs them on Neo4j,
Memgraph, FalkorDB and Postgres, and `ORACLE_TESTS=true` with it on Oracle. One store's:

```
CLI_STORE_TESTS=true ./gradlew :drivine4j-cli:test --tests 'org.drivine.cli.CliPostgresTest'
```
