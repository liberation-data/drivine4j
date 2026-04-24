
## Using Test Containers (Default)

By default, tests start a Testcontainer for whichever backend(s) the suite targets — Neo4j, FalkorDB, or Memgraph. No additional setup required beyond Docker being available on the host.

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

Spring Boot tests resolve the container vs. local choice automatically via `DrivineTestConfiguration` based on each configured datasource's `type`. Local profile settings belong in `application.yml` / `application-test.yml`.

Raw Testcontainers tests (see `org.drivine.connection.*IntegrationTest`) declare their own `@Container` and always use a fresh container — they do not read the `USE_LOCAL_*` flags.