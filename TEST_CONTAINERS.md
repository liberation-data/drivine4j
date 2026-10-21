
## Using Test Containers (Default)

By default, tests start a Testcontainer for whichever backend(s) the suite targets — Neo4j, FalkorDB, or Memgraph. No additional setup required beyond Docker being available on the host. Oracle's container is started only by the Oracle tests, which a plain `./gradlew test` leaves out: see [Oracle](#oracle).

```bash
# Run tests with test containers (default)
./gradlew test
```

## Using a Local Database Instance

To bypass testcontainers and point at a locally running instance, set the corresponding env var (or system property) per backend:

| Backend  | Env var / flag                             | Local config env vars                                                          |
|----------|--------------------------------------------|--------------------------------------------------------------------------------|
| Neo4j    | `USE_LOCAL_NEO4J=true` / `-Dtest.neo4j.use-local=true`     | `NEO4J_LOCAL_URL`, `NEO4J_LOCAL_USERNAME`, `NEO4J_LOCAL_PASSWORD`              |
| FalkorDB | `USE_LOCAL_FALKORDB=true` / `-Dtest.falkordb.use-local=true` | `FALKORDB_LOCAL_HOST`, `FALKORDB_LOCAL_PORT`                                   |
| Memgraph | `USE_LOCAL_MEMGRAPH=true` / `-Dtest.memgraph.use-local=true` | `MEMGRAPH_LOCAL_HOST`, `MEMGRAPH_LOCAL_PORT`                                   |
| Oracle   | `USE_LOCAL_ORACLE=true` / `-Dtest.oracle.use-local=true`   | `ORACLE_LOCAL_HOST`, `ORACLE_LOCAL_PORT`                                       |

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
- **The cross-engine contracts seed and read back in Cypher.** On Oracle those statements are worked out by `org.drivine.test.OracleCypherFixtures`, a small interpreter for the Cypher such fixtures use; a statement outside what it reads is refused, and is a reason to extend it or to write that fixture in SQL.
