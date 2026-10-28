
[![CI](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml/badge.svg)](https://github.com/liberation-data/drivine4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**Docs: [drivine.org](https://drivine.org/jvm/)**, for Kotlin & Java, TypeScript, and Python.

A graph database client library for Java and Kotlin supporting **Neo4j**, **FalkorDB**, **Amazon Neptune**, **Memgraph**, **Oracle** (SQL/PGQ), **Postgres** (plain SQL, with pgvector for vector search) and **SQLite** (a file, no server) with two approaches to graph mapping:

1. **PersistenceManager** - Low-level API with manual Cypher queries (classic Drivine approach). Cypher runs on every
   engine, compiled to SQL on Oracle, Postgres and SQLite: see [Cypher on a relational database](05-cypher-on-a-relational-database.md#cypher-on-a-relational-database).
2. **Object manager** (`StatelessGraphObjectManager`) - High-level API with annotated models and type-safe DSL.

Drivine4j is the graph database client library for [Embabel](https://hub.embabel.com) - Agentic AI for the JVM. 

> **On Node.js or Python?** [Drivine](https://github.com/liberation-data/drivine) (`npm install @liberation-data/drivine`)
> and [drivine-python](https://github.com/liberation-data/drivine-python) (`pip install drivine`) are drivine4j's
> TypeScript and Python twins, with the same object manager, query DSL, schema management and engines. The three share
> a storage format: a store written from any of them is read, stamped and checked alike by the others.

