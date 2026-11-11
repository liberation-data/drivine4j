# drivine4j-cli

The `drivine` command line: Cypher statements, from the argument, a file or stdin, against any store Drivine reaches,
Neo4j, Memgraph, FalkorDB, Postgres, Oracle or SQLite, printed as `table`, `plain`, `json` or `csv`. On Postgres, Oracle and SQLite,
Drivine compiles the Cypher to SQL, as it does in an application. Its contract is drivine-cypher-spec's
[`cli/README.md`](https://github.com/liberation-data/drivine-cypher-spec/tree/main/cli) (liberation-data/drivine-cypher-spec#99).
This build has part of it, for liberation-data/drivine4j#706 and #709: the REPL, profiles, `:use`,
and `table`'s `--sample-rows` and `--wrap` come later.

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
`main` sets `io.netty.allocator.type=pooled` where nothing else set it: the Neo4j driver turns off Netty's use of
`Unsafe` in a native image, and Netty 4.2's default allocator then fails on Neo4j and Memgraph. `-Dio.netty.allocator.type`
on the JVM's command line, or `JAVA_OPTS`, sets another.

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

1. Add the label `cli-trace` to the pull request, put `[cli-trace]` in its title and push, or run the workflow by hand (Actions, "CLI native", "Run workflow").
2. Read each store's metadata from the log of its job, `trace metadata (<store>)`: the step "Print the metadata" prints
   each file the agent wrote between `=== BEGIN <file> ===` and `=== END ===`. A failing test doesn't stop the trace.
3. Merge the six stores' files into that `reachability-metadata.json`, one entry for each class or resource, and keep
   its resource entry for `org/drivine/cli/drivine-cli.properties`, which `--version` reads. Leave out what the agent
   recorded of the JVM it ran on and not of the CLI: sqlite-jdbc's native library, as
   `org/sqlite/native/Linux/x86_64/libsqlitejdbc.so`, which sqlite-jdbc's own feature adds for the platform the binary
   is built on, and JFR's `jdk.jfr` and `jdk.management.jfr` entries. `MetadataTest` fails on them.

## Run

```
drivine -a sqlite::memory: --format json 'RETURN 1 AS n'
{"columns":["n"],"rows":[[1]]}

DRIVINE_PASSWORD=… drivine -a postgresql://localhost:5432/graphs -u drivine --format plain 'MATCH (n) RETURN count(n) AS c'

drivine -a sqlite:graph.db -f export.cypher
drivine -a sqlite:graph.db --continue < statements.cypher
```

The statements are the argument's, `-c`'s, a `-f` file's, or, with none of those, stdin's. Stdin is read only where it
isn't a terminal or `--non-interactive` is given: on a terminal, that's the REPL, which comes later. Each is split into
statements on `;`, but not on one inside a string, a backticked name or a comment, as the contract's "Files" says, and
each statement runs in a transaction of its own as it's read, its result printed as it comes. So a file larger than
memory, as an export may be, runs as it's read. The first statement that fails stops the run, with the results before
it printed; `--continue` runs the rest, reports each failure on stderr, and exits with 1. `:exit` and `:quit` stop
reading.

**Commands and parameters.** `:begin` opens a transaction that the statements after it run in, until `:commit` commits
it or `:rollback` rolls it back; an input that ends inside one rolls it back, and doesn't fail for it. Under
`--continue`, a statement that fails inside one, or a `:param` whose expression does, rolls it back too, and the
statements after it run on their own, so a `:commit` after them fails, as no transaction is open; a command that fails,
as `:nope`, leaves it open. On FalkorDB, which has no multi-statement transactions, `:begin`
warns on stderr and each statement commits as it runs; `:commit` does nothing, and `:rollback` fails. `-P` (or
`--param`) and `:param` take `NAME => EXPRESSION`, `NAME=EXPRESSION` or a map, `{NAME: EXPRESSION, …}`: the store
evaluates the expression, as `RETURN expression`, and every statement after is given it. A `-P` whose expression fails
ends the run with 1 before any statement runs. `:params` prints them, a text in `'…'`, and `:params clear` clears them.
`:source FILE` runs a file's statements and commands in place, as though its text stood there, so a `:begin` or a
`:commit` in it opens or ends the run's transaction; a file that is being read already, as one that sources itself,
fails. One byte-order mark at the very start of a `-f` file, stdin or a `:source` file isn't part of its text. `:help`
lists the commands. `:use` comes later, with `--database`. A command the contract doesn't have, as `:nope`, fails as a
statement does too, with cypher-shell's line, `Could not find command :nope, use :help to see available commands`. A command is a line that starts with `:`, so a `:` after a
statement's `;` on the same line is no command.

**`--explain`**, on Postgres, Oracle and SQLite, prints for each statement the SQL it compiles to
(`PersistenceManager.explain`), then `-- parameters: ` and the parameters it's given, by their names, as `json` writes
them, and runs none. `-P` and `:param` still run, as the store evaluates their expressions, and `:begin`, `:commit` and
`:rollback` open and close nothing. On a graph engine, which runs Cypher itself, it's refused as a bad invocation.

**cypher-shell's flags that `drivine` takes:** `-a`/`--address`, `-u`/`--username`, `-f`/`--file`, `--format` (`auto`,
`table`, `plain`, `json` and `csv`), `-P`/`--param`, `--non-interactive`, `-v`/`--version` and `-h`/`--help`. `-c`, `--continue` (cypher-shell's
`--fail-at-end`), `--type` and `--explain` are drivine's own. cypher-shell's `-p`/`--password` is refused: the password is
`DRIVINE_PASSWORD`'s.
The rest of the contract's flags, `-d`, profiles and `table`'s `--sample-rows` and `--wrap` among them, come
later, and are refused, said so, as are cypher-shell's that the contract doesn't take, as unknown flags.

**Formats.** `table` prints each result as cypher-shell's `verbose` does: the table, its count of rows, the timing line,
and the store's counts of what the statement wrote, as `Created 1 node, added 1 label`, on Neo4j and Memgraph; FalkorDB
and the SQL stores give no counts, so none print there. `csv` is RFC 4180, with one empty line between results. `auto`,
the default, is `table` where stdout is a terminal and `plain` where it isn't; Java can't ask that of stdout alone, so a
run whose stdin isn't a terminal, as `cat x.cypher | drivine` at a terminal, prints `plain` too.

The address is `-a`'s, else `DRIVINE_ADDRESS`'s; the user `-u`'s, else the address's, else `DRIVINE_USER`'s; the
password `DRIVINE_PASSWORD`'s, never a flag's. `drivine --help` lists the flags. The exit code is 0 when everything ran,
1 when a statement or a command failed, 2 for a bad invocation, a `-f` file that can't be read among them, and 3 when the store
can't be reached or refuses the user. A
result that can't be written, as on a full disk under `> file`, exits with 1 and says so on stderr; a reader that
closes the pipe, as `| head` does, isn't a failure, and the run exits with 0.

`DRIVINE_TRACE_ERRORS=true` prints the stack trace of a statement's or a connection's failure, its causes' too, on
stderr after the message: to see why a store or the native binary fails where the message doesn't say. It's not part of
the contract, and `--log` takes its place when it comes. The password isn't in it, but the statement and the store's
messages are.

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
