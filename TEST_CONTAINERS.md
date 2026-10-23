
## Using Test Containers (Default)

By default, tests start a Testcontainer for whichever backend(s) the suite targets — Neo4j, FalkorDB, or Memgraph. No additional setup required beyond Docker being available on the host. Oracle's and Postgres's containers are started only by their own tests, which a plain `./gradlew test` leaves out: see [Oracle](#oracle) and [Postgres](#postgres).

```bash
# Run tests with test containers (default)
./gradlew test
```

## The core on its own classpath

The root module's tests run in Spring contexts, through the starter. `./gradlew standaloneTest` runs the tests in
`src/standaloneTest` on the core's own runtime classpath and JUnit, as a plain `main` or a Ktor server has it. Spring
support is in `drivine4j-spring`, so the task fails first if an `org.springframework` module comes onto that classpath. They start a Neo4j and a
FalkorDB of their own, and read no `USE_LOCAL_*` flag. `./gradlew check` (and so `build`) runs them.

## The coroutines module

`drivine4j-coroutines` tests its suspend transaction block on each engine with transactions, with a `Drivine` and
JUnit and no framework but a `TransactionTemplate` of `drivine4j-spring`. They start a Neo4j, a Memgraph and a FalkorDB
of their own, and read no `USE_LOCAL_*` flag. Its Oracle and Postgres classes run only with `ORACLE_TESTS=true` and
`POSTGRES_TESTS=true`, on the containers described below:

```bash
./gradlew :drivine4j-coroutines:test
ORACLE_TESTS=true ./gradlew :drivine4j-coroutines:test --tests '*Oracle*'
POSTGRES_TESTS=true ./gradlew :drivine4j-coroutines:test --tests '*Postgres*'
```

## Using a Local Database Instance

To bypass testcontainers and point at a locally running instance, set the corresponding env var (or system property) per backend:

| Backend  | Env var / flag                             | Local config env vars                                                          |
|----------|--------------------------------------------|--------------------------------------------------------------------------------|
| Neo4j    | `USE_LOCAL_NEO4J=true` / `-Dtest.neo4j.use-local=true`     | `NEO4J_LOCAL_URL`, `NEO4J_LOCAL_USERNAME`, `NEO4J_LOCAL_PASSWORD`              |
| FalkorDB | `USE_LOCAL_FALKORDB=true` / `-Dtest.falkordb.use-local=true` | `FALKORDB_LOCAL_HOST`, `FALKORDB_LOCAL_PORT`                                   |
| Memgraph | `USE_LOCAL_MEMGRAPH=true` / `-Dtest.memgraph.use-local=true` | `MEMGRAPH_LOCAL_HOST`, `MEMGRAPH_LOCAL_PORT`                                   |
| Oracle   | `USE_LOCAL_ORACLE=true` / `-Dtest.oracle.use-local=true`   | `ORACLE_LOCAL_HOST`, `ORACLE_LOCAL_PORT`                                       |
| Postgres | `USE_LOCAL_POSTGRES=true` / `-Dtest.postgres.use-local=true` | `POSTGRES_LOCAL_HOST`, `POSTGRES_LOCAL_PORT`                                 |

Spring Boot tests resolve the container vs. local choice automatically via `DrivineTestConfiguration` based on each configured datasource's `type`. Local profile settings belong in `application.yml` / `application-test.yml`.

Raw Testcontainers tests (the cross-engine `*Neo4jTest`, `*FalkorDbTest` and `*MemgraphTest` classes, and `org.drivine.connection.*IntegrationTest`) do not read the `USE_LOCAL_*` flags. They share one container per engine for the test JVM — `org.drivine.test.SharedNeo4j`, `SharedFalkorDb` and `SharedMemgraph` — taken from the class's companion with `@JvmField val container = SharedNeo4j.forClass()`. `forClass()` empties the database first, so a class starts with no data, index or constraint another class left: `TestCleanup.resetGraph` on Neo4j and Memgraph, `FLUSHALL` on FalkorDB. A class that needs a container no other can share (APOC, another Neo4j version) declares its own `@Container`.

## Oracle

The Oracle tests run against Oracle Database Free and only when asked for, since the image is large and the database wants two gigabytes of memory:

```bash
ORACLE_TESTS=true ./gradlew :test --tests '*Oracle*'
```

They are in the root module. Without `ORACLE_TESTS=true` every class marked `@OracleTests` is skipped, so `./gradlew test` neither pulls the image nor starts the container. The benchmark, `OracleManagerBenchmarkTest`, wants `DRIVINE_BENCHMARK=true` as well.

- **The image** is `gvenzl/oracle-free:23-faststart`; choose another with `ORACLE_TEST_IMAGE` or `-Dtest.oracle.image`. Use a regular image and not a slim one, which has no Oracle Text: the full-text tests then search without an index.
- **The container** (`org.drivine.test.OracleTestContainer`) is started once for the test JVM, when a test first asks for it. Tests connect as the user `drivine`, password `drivine`, to the service `FREEPDB1`.
- **A local instance** (`USE_LOCAL_ORACLE=true`) must open the service `FREEPDB1` and hold that user with that password: `OracleTestContainer` lets only its host and port be changed, with `ORACLE_LOCAL_HOST` and `ORACLE_LOCAL_PORT` or `-Dtest.oracle.local.host` and `-Dtest.oracle.local.port` (`localhost` and 1521 unless given). A Spring Boot test through `@EnableDrivineTestConfig` takes a local instance's host, port, service and user from its own datasource properties instead.
- **The tests share one database and a few named graphs in it**: `contracts` for the cross-engine contracts, and `smoke`, `schema`, `identity`, `readpath` and `bench` for the rest. A graph is a set of tables under that name, made when a test first speaks to it. The tests of plain SQL (`OracleConnectionIntegrationTest`) and the spikes keep tables of their own beside them.
- **The cross-engine contracts seed and read back in Cypher.** On Oracle (and Postgres) those statements are worked out by `org.drivine.test.SqlCypherFixtures`, a small interpreter for the Cypher such fixtures use; a statement outside what it reads is refused, and is a reason to extend it or to write that fixture in SQL.

## Postgres

The Postgres tests run against Postgres with pgvector, and only when asked for, as the Oracle tests are:

```bash
POSTGRES_TESTS=true ./gradlew :test --tests '*Postgres*'
```

They are in the root module. Without `POSTGRES_TESTS=true` every class marked `@PostgresTests` is skipped, so `./gradlew test` neither pulls the image nor starts the container.

- **The image** is `pgvector/pgvector:pg17`; choose another with `POSTGRES_TEST_IMAGE` or `-Dtest.postgres.image`. One without pgvector runs every test but those of vector search.
- **The container** (`org.drivine.test.PostgresTestContainer`) is started once for the test JVM, when a test first asks for it. Tests connect as the user `drivine`, password `drivine`, to the database `drivine`.
- **A local instance** (`USE_LOCAL_POSTGRES=true`) must hold that database and that user with that password, as the container does: only its host and port can be changed, with `POSTGRES_LOCAL_HOST` and `POSTGRES_LOCAL_PORT` or `-Dtest.postgres.local.host` and `-Dtest.postgres.local.port` (`localhost` and 5432 unless given).
- **The tests share one database and a few named graphs in it**, as on Oracle: `contracts` for the cross-engine contracts, and `smoke`, `schema`, `readpath` and others for the rest. The tests of plain SQL (`PostgresConnectionIntegrationTest`) keep tables of their own beside them.
- **The cross-engine contracts seed and read back in Cypher**, worked out by `org.drivine.test.SqlCypherFixtures`, as on Oracle.
