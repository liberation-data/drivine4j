<a href="https://drivine.org/"><img src="https://raw.githubusercontent.com/liberation-data/drivine/master/website/public/images/splash.jpg" alt="Drivine: graph database client for TypeScript, Python and the JVM"></a>

# Drivine4j

[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**Docs: [drivine.org](https://drivine.org/jvm/)**, for Kotlin & Java, TypeScript, and Python.

A graph database client library for Java and Kotlin supporting **Neo4j**, **FalkorDB**, **Amazon Neptune**, **Memgraph**, **Oracle** (SQL/PGQ), **Postgres** (plain SQL, with pgvector for vector search) and **SQLite** (a file, no server) with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach). Cypher runs on every
   engine, compiled to SQL on Oracle, Postgres and SQLite.
2. **Object manager** (`StatelessGraphObjectManager`) - High-level API with annotated models and type-safe DSL.

> **On Node.js or Python?** [Drivine](https://github.com/liberation-data/drivine) (`npm install @liberation-data/drivine`)
> and [drivine-python](https://github.com/liberation-data/drivine-python) (`pip install drivine`) are drivine4j's
> TypeScript and Python twins, with the same object manager, query DSL, schema management and engines.

## Installation

Java 21+. The object manager needs Kotlin 2.2.0+. For a Spring Boot application, add the starter:

```kotlin
dependencies {
    implementation("org.drivine:drivine4j-spring-boot-starter:0.4.1")
}
```

Spring without Boot takes `drivine4j-spring`; the core library, `drivine4j`, runs with another framework or none. The
type-safe query DSL needs the code generator: see [Installation](docs/03-installation.md).

## Quick Start

Name a datasource, and the starter provides the `PersistenceManagerFactory`, `GraphObjectManagerFactory`,
`SchemaManager` and Drivine's transaction manager. Here `manager` is the `PersistenceManager` from
`factory.get("neo")`:

```yaml
database:
  datasources:
    neo:
      type: NEO4J
      host: localhost
      port: 7687
      user-name: neo4j
      password: password
      database-name: neo4j
```

```kotlin
@Transactional
fun findByCity(city: String): List<Person> = manager.query(
    QuerySpecification
        .withStatement("MATCH (p:Person {city: \$city}) RETURN properties(p)")
        .bind(mapOf("city" to city))
        .transform(Person::class.java)
)
```

The [Quick Start](docs/04-quick-start.md) has the rest, and the
[object manager](docs/06-object-manager.md) maps annotated models with no Cypher at all.

## Documentation

- [drivine.org/jvm](https://drivine.org/jvm/): the full documentation.
- [docs/](docs/README.md): the same, one file per topic, on GitHub.
- [RELEASING.md](RELEASING.md): how a release is published.
- [TEST_CONTAINERS.md](TEST_CONTAINERS.md): the databases the tests run against.
- [TCK.md](TCK.md): the openCypher TCK, run on each store, and its nightly gate.
- [AGENTS.md](AGENTS.md): notes for contributors, on keeping the three Drivine libraries alike.
- Issues: https://github.com/liberation-data/drivine4j/issues

## License

Apache License 2.0
