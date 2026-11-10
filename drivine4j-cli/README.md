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
module isn't published.

### The native binary

```
./gradlew :drivine4j-cli:nativeCompile
```

That gives `drivine4j-cli/build/native/nativeCompile/drivine`, one binary that needs no JVM. It needs a local GraalVM,
in `GRAALVM_HOME` or else `JAVA_HOME`: CI builds with GraalVM Community 25.0.2, which builds the module's JDK 21
bytecode. The build applies GraalVM's Gradle plugin, `org.graalvm.buildtools.native`, and enables its metadata
repository: the plugin fetches GraalVM's reachability metadata, for the libraries that carry none of their own such as
Jackson and the Postgres driver, when it builds the image. Its build args are in `build.gradle`, with why each is there.

`.github/workflows/cli-native.yml` builds it on linux x64, records its size and the time `drivine --version` takes to
start in the run's summary, uploads it as the artifact `drivine-linux-x64`, and runs the tests below against it, on each
of the six stores. It runs on a pull request that changes this module, the core's `src/main`, or the build, each night,
and by hand, apart from `ci.yml`, so a GraalVM problem doesn't block a pull request's CI. The binaries for the other
operating systems come in [#707](https://github.com/liberation-data/drivine4j/issues/707).

### Refreshing the reachability metadata

What the binary reaches at run time that native-image can't see, such as a class kotlin-reflect looks up or a Netty
class the Neo4j driver loads by name, is in
`src/main/resources/META-INF/native-image/org.drivine/drivine4j-cli/reachability-metadata.json`. cli-native.yml's
`trace` job records it with GraalVM's tracing agent: on each of the six stores it runs the tests below with
`DRIVINE_CLI_BINARY` naming a script that runs the CLI on GraalVM's JVM under the agent, so the CLI is traced and not the
tests. To refresh it:

1. Add the label `cli-trace` to the pull request, or run the workflow by hand (Actions, "CLI native", "Run workflow").
2. Read each store's metadata from the log of its job, `trace metadata (<store>)`: the step "Print the metadata" prints
   each file the agent wrote between `=== BEGIN <file> ===` and `=== END ===`. A failing test doesn't stop the trace.
3. Merge the six stores' files into that `reachability-metadata.json`, one entry for each class or resource, and keep
   its resource entry for `org/drivine/cli/drivine-cli.properties`, which `--version` reads.

## Run

```
drivine -a sqlite::memory: --format json 'RETURN 1 AS n'
{"columns":["n"],"rows":[[1]]}

DRIVINE_PASSWORD=… drivine -a postgresql://localhost:5432/graphs -u drivine --format plain 'MATCH (n) RETURN count(n) AS c'
```

The address is `-a`'s, else `DRIVINE_ADDRESS`'s; the user `-u`'s, else the address's, else `DRIVINE_USER`'s; the
password `DRIVINE_PASSWORD`'s, never a flag's. `drivine --help` lists the flags. The exit code is 0 when everything ran,
1 when the statement failed, 2 for a bad invocation and 3 when the store can't be reached or refuses the user.

`sqlite::memory:` is a file in a directory of the run's own in the temp dir, as Drivine keeps a SQLite graph in a file
that each connection opens. It's deleted when the run ends, by Ctrl-C or `kill` too; only `kill -9` leaves it there.

## Tests

`./gradlew :drivine4j-cli:test` runs the CLI in process on SQLite in memory, with no container, and the contract's
cases (`src/test/resources/cli/cli.yaml`, copied from drivine-cypher-spec as `cli/VERSION` says) that this build can
run. The tests against the other stores start containers and are opt-in: `CLI_STORE_TESTS=true` runs them on Neo4j,
Memgraph, FalkorDB and Postgres, and `ORACLE_TESTS=true` with it on Oracle. One store's:

```
CLI_STORE_TESTS=true ./gradlew :drivine4j-cli:test --tests 'org.drivine.cli.CliPostgresTest'
```

`DRIVINE_CLI_BINARY`, the path of a built `drivine`, runs the same tests against it: each run is a process of the
binary, with the run's arguments, stdin and environment and nothing of the test's own environment (a contract case's
in an empty working directory of its own), and its stdout, stderr and exit code are checked. That's how CI tests the native binary:

```
DRIVINE_CLI_BINARY=$PWD/drivine4j-cli/build/native/nativeCompile/drivine CLI_STORE_TESTS=true \
  ./gradlew :drivine4j-cli:test --tests 'org.drivine.cli.CliPostgresTest'
```
