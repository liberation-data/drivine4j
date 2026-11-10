# The Cypher spec

These files say what a statement in Drivine's flavour of Cypher gives on each store Drivine runs it on. The graph engines run Cypher themselves. On Postgres, Oracle and SQLite, Drivine compiles it to SQL. The three Drivine libraries (drivine4j, drivine for TypeScript, drivine-python) run the same files, so a statement gives the same rows from each of them.

- `queries.yaml`: statements that read, run over the graph the file's `graph` writes. The graph is emptied and written again before each case.
- `writes.yaml`: statements that write, run from an empty graph. Each case's steps run in turn, so a step reads what the steps before it wrote.
- `temporal.yaml`: openCypher's temporal values (dates, times, moments and durations), run from an empty graph. A graph engine's driver gives a temporal value as a value of its own, and a SQL store gives its ISO text, so the cases read a value's fields.
- `refusals.yaml`: statements each SQL store's compiler refuses, and its message. They are compiled with no store.
- `schema.yaml`: Neo4j's schema commands (`CREATE INDEX`, `CREATE CONSTRAINT`, `DROP …`, `CALL db.awaitIndexes`), and the schema spec a SQL store applies each as, or its refusal. They are read with no store. See [Schema commands](#schema-commands).
- `store.yaml`: the store layout's DDL: the tables, the probe that finds them made, and each index and constraint as each store makes, drops and lists it, for the graph `drivine` and a set of schema specs, with a read for each index that the compiler must answer from it. Read with no store. See [The store layout](#the-store-layout). It also holds the store's schema: how each store's listings are read back, whether an item is a spec's and in its shape, what each store can do, and what its errors say. See [The store's schema](#the-stores-schema).
- `marker.yaml`: the schema marker (`_DrivineSchema`) and the stamps: the statements each store reads and writes the marker and the store's stamp (`DrivineStore`) with, the marker's inventory as it is written and read back, the version token, and the form of the stamp each node carries. Read with no store. See [The schema marker and the stamps](#the-schema-marker-and-the-stamps).
- `host.yaml`: what each library's own code does with the values compiled SQL hands it: the functions it registers on SQLite, and how it reads a column of a statement's rows. Read with no store. See [Host functions and readers](#host-functions-and-readers).
- `search.yaml`: full-text search: the nodes a search finds on each store, over the graph the file's `graph` writes, and how closely each other store ranks a corpus, `search/docs-corpus.json`, as Neo4j's full-text index ranks it. See [Full-text search](#full-text-search).
- `cli/`: the `drivine` CLI's contract, which runs Cypher on every store and prints its results as cypher-shell does, and `cli.yaml`, the cases each build of it runs. See `cli/README.md`.
- `model/`: the model file each library writes of an application's fragments, views and how they are stored, which Drivine Studio reads. See `model/README.md`.

A runner empties the whole graph before each case, not just the nodes that carry the file's `label`. The statements match plain labels, such as `MATCH (p:Person)`, so a node another test left behind would show up in their rows.

## A case

```yaml
- name: "Optional match"
  description: "OPTIONAL MATCH keeps the row, with null where it finds nothing."
  steps:
    - cypher: "MATCH (p:Person) OPTIONAL MATCH (p)-[:KNOWS]->(f) RETURN p.name, f.name ORDER BY p.name"
      rows: [["Carrie", null], ["Hugo", null], ["Keanu", "Carrie"], ["Laurence", "Keanu"]]
```

A case has a short `name`, unique in its file, that a library's runner shows and skips by, and a one-line `description` of what it checks. Then its steps, run in turn.

A step has:

| Key | What it is |
|---|---|
| `cypher` | The statement. |
| `parameters` | The values bound to its parameters. |
| `rows` | The rows it gives. A row of one column is its value, and a row of several is a list of their values, as `PersistenceManager.query` gives them. |
| `error` | The message it fails with, a list of messages any one of which passes, or `true` for a failure of any message. |
| `only`, `except` | The stores the step runs on, or doesn't. A store is `neo4j`, `memgraph`, `falkordb`, `neptune`, `postgres`, `oracle` or `sqlite`. The group `graph` names the graph engines, and `sql` names the stores Cypher is compiled for. |
| `unordered` | The rows are compared in any order. |
| `unorderedLists` | Each list within the rows is compared in any order. |
| `keyOrder` | Each map's keys are compared in order too. |
| `note` | Why the step is there. It is not read. |

A step with no `rows` and no `error` is run, and what it gives is not checked.

On Neo4j a statement runs in the database's default language, Cypher 25 on Neo4j 2026.10. A step that pins what Cypher 5 gives, where the two differ, starts its statement with `CYPHER 5` and runs `only: [neo4j]`.

## Values

- A whole number and the same number written with a fraction are different values: `2` is not `2.0`.
- `{$repeat: "x", times: 3000}` is the text `x` written 3000 times, in parameters and rows alike.
- `{$float: "NaN"}`, `{$float: "Infinity"}` and `{$float: "-Infinity"}` are NaN and the infinities, in parameters and rows alike: not every YAML parser reads `.nan` and `.inf`. NaN in the rows is the same as NaN there.
- Any other map is the map it is, even one keyed with `$`, such as `{$numberDouble: "1"}`.
- Quote every string. A YAML 1.1 parser, such as SnakeYAML or PyYAML, reads a bare `y`, `yes`, `no` or `on` as a boolean.
- `writes.yaml` binds and expects a whole number beyond 2^53. A library whose language reads YAML numbers as doubles skips those steps, named in its own runner. Otherwise a library skips nothing: a store that differs says so with `except` here.

## Schema commands

A SQL store runs Neo4j's schema DDL through its library's schema machinery: a `CREATE` is ensured as the schema API ensures a spec, under an owner of its own, `ddl`, so the item has the name, the expressions and the inventory entry the API gives it. `writes.yaml` runs such statements on Neo4j and the SQL stores alike. `schema.yaml` pins how each library reads one, with no store:

```yaml
- name: "Range index"
  description: "CREATE [RANGE] INDEX over one property or several is a range index."
  steps:
    - ddl: "CREATE INDEX person_name IF NOT EXISTS FOR (p:Person) ON (p.name)"
      create: {kind: "RANGE_INDEX", label: "Person", properties: ["name"], alias: "person_name", ifNotExists: true}
```

A step has `ddl`, the statement, an optional `note`, and one of these, which says what it reads as:

| Key | What it reads as |
|---|---|
| `create` | A spec to create: its `kind` (`RANGE_INDEX`, `UNIQUENESS_CONSTRAINT`, `FULLTEXT_INDEX`, `VECTOR_INDEX` or `TEXT_INDEX`, as a store's catalog names it), `label` and `properties`; `alias`, the statement's name where it is not the name the schema API gives the spec; `ifNotExists`; and `options`: a full-text index's `analyzer`, and a vector index's `dimensions`, `similarity` (`COSINE` or `EUCLIDEAN`), `hnswM`, `hnswEfConstruction` and `quantizationEnabled`. A key left out is null, false or empty. The spec itself has no name: the schema API's is the one it is made under, and the alias is kept beside it, so that a `DROP` by it finds the item. |
| `drop` | A drop of the item by its name: `kind` (`INDEX` or `CONSTRAINT`), `name`, and `ifExists`. |
| `await` | `true`: a wait for indexes, which does nothing on a SQL store. |
| `reserved` | Drivine's own uniqueness constraint, of the schema marker's `scope` or the store stamp's `key`, by any name: its `label` and `properties`. It is made as the library makes it for itself, and recorded in no owner's inventory. |
| `cypher` | `true`: not a schema command, but Cypher, compiled as any other statement. |
| `error` | The message it is refused with. A SQL store's compiler refuses it with the same message (see `refusals.yaml`). |

## The store layout

Each library makes the same tables, indexes and constraints on a SQL store, and the same schema statements on a graph engine, so a store one library made is read alike by another. `store.yaml` pins them: drivine4j's statements, the reference, as its schema grammars gave them when they were captured, for the graph `drivine`. The compiler's module (`compiler/`, `org.drivine.cypher.store.ddl`) writes them, and its tests check it gives these, byte for byte, on every target.

```yaml
graph: "drivine"
tables:
  postgres: {ddl: [...], ensuring: "DO $ensure$ ..."}
  oracle: {ddl: [...], functions: [...], ensuring: "DECLARE ..."}
  sqlite: {ddl: [...], made: "SELECT CASE WHEN ...", file: "SELECT file FROM pragma_database_list ..."}
catalog: {item: {...}, aliases: [...], stores: {postgres: {indexes: {RANGE_INDEX: "...", ...}, constraints: "...", aliased: "...", unaliased: "..."}, ...}}
names:
  - {store: "postgres", graph: "drivine", kind: "ix", parts: ["Person", "name"], name: "drivine_ix_Personname_44ce5436"}
refusals:
  - {store: "postgres", graph: "1graph", refusal: "'1graph' cannot name a graph on Postgres: ..."}
cases:
  - name: "Range index"
    description: "A range index over one property."
    spec: {kind: "RANGE_INDEX", label: "Person", properties: ["name"]}
    stores:
      postgres:
        create: ["CREATE INDEX IF NOT EXISTS drivine_ix_Personname_44ce5436 ON drivine_node (((props ->> 'name'))) WHERE labels @> '[\"Person\"]'", "INSERT INTO drivine_schema ..."]
        drop: ["DO $forget$ ..."]
      neo4j:
        create: ["CREATE INDEX `Person_name_range` IF NOT EXISTS FOR (n:`Person`) ON (n.`name`)"]
        drop: ["DROP INDEX `Person_name_range` IF EXISTS"]
        dropUnnamed: {error: "Cannot drop index on Person[name]: ..."}
    read:
      cypher: "MATCH (n:Person {name: $x}) RETURN n"
      parameters: {x: "Ada"}
      indexedProperties: [["Person", "name"]]
      holds:
        postgres: ["(props ->> 'name')", "labels @> '[\"Person\"]'"]
```

| Key | What it is |
|---|---|
| `tables` | What makes each SQL store's tables, base indexes and functions (`ddl`, and Oracle's `functions`), each statement safe to run again, and how a library finds them made. On Postgres, `ensuring` is one block: the probe of the node table's comment, which holds the version token (`drivine:7`), the graph's advisory lock, and the `ddl`. On Oracle, `ensuring` probes the property graph's `doc` property and each function, and makes what is missing. On SQLite, `made` counts what of the `ddl` is there, or is -1 for tables made without `AUTOINCREMENT`, and `file` names the database's file. |
| `catalog` | Each store's queries that list its indexes of each kind and its constraints; on a SQL store, the statements that set and take away the `aliases` of the record of `item`; and Oracle's `labelled`, the labelled indexes a library tells the compiler of as `indexedProperties`. |
| `names` | The name a SQL store gives an object of `kind` made for `parts`, a label and properties, for a `graph`: `<graph>_<kind>_<readable>_<hash>`. `readable` is the parts' ASCII letters and digits run together, cut to at most 40 and to what leaves room, within the store's longest name (63 on Postgres and SQLite, 128 on Oracle), for the rest and a `_t`. `hash` is Java's `String.hashCode` of the parts joined by U+0000, over their UTF-16 code units, as `Integer.toHexString` writes it: unsigned, in lower case, with no leading zeros (`drivine_ix_name_337a8b`). |
| `refusals` | Why a store refuses a graph's name, or null where it takes it. |
| `cases` | An index or a constraint: its `spec`, as `schema.yaml` writes one, with `ignoreCase` and the engines' vector options (`quantizationEnabled`, Neo4j's; `efRuntime`, FalkorDB's) among its `options`. A spec that ignores case is made over the lowercased copies of its properties, under a name of its own, as the schema API makes it. `existing` is what the store has of the same kind on the label already, which only FalkorDB reads. |

Each case's `stores` hold, for `postgres`, `oracle`, `sqlite`, `neo4j`, `memgraph` and `falkordb`, what makes the item (`create`), what drops it by its name (`drop`), and, where it differs, what drops it with no name known (`dropUnnamed`). Each is a list of statements, or `{error}`, the message the store refuses it with. A statement is its text, or on FalkorDB `{native: [...]}`, a command of its driver (`GRAPH.CONSTRAINT`), where `{graphName}` stands for the graph. A constraint's `falkordb` has `constrainable`, `{error}`, where FalkorDB refuses it before its `create`: `GRAPH.CONSTRAINT` takes only a plain name for a property. On a SQL store, a `create` follows the statements that make the store, which the case leaves out: Postgres's and Oracle's `ensuring`, and SQLite's `ddl`. On Oracle, each index a `create` builds over the node table, the range index over every node's property, the labelled index and the unique index, is run within a PL/SQL block that runs it again a tenth of a second after another session's lock turns it away (`ORA-00054`), for up to 300 tries, and then raises, as `ensuring` runs its statements: Oracle's DDL does not wait for a lock, and libraries starting together each make the store stamp's key constraint (liberation-data/drivine4j#517). An index another session made after the block's `IF NOT EXISTS` found none (`ORA-00955`) is taken as made, as `ensuring` takes it.

**Reads.** A range index and a uniqueness constraint are only worth their DDL if a statement is read through them, so each such case has a `read`: a statement, its `parameters`, and the `indexedProperties` a library gives the compiler on Oracle. Compiled on each SQL store, its SQL holds each of `holds`, the expressions the index is made over as its `create` writes them, with each column a statement reads of the node, `props`, `labels` or `stamp`, as its alias's (Oracle's index names them as `n`'s). A runner checks each expression is in the case's `create`, as it is or within a PL/SQL literal, quotes doubled, and that the compiled SQL tests it where a planner reads an index by it: as a conjunct of its `WHERE`, a label's test, or as the left side of a conjunct's `=`, and not only within a `CASE` or beside an `OR`. A vector and a full-text index have none: their searches are each library's, not compiled Cypher.

## The store's schema

A schema manager reads what a store has before it makes anything, and decides from what the store says: whether an item it found is the one a spec declares, whether it has the shape the spec declares, and what an error means. Each library decides alike, so a store one library made is ensured alike by another. `store.yaml`'s sections `schema`, `listings`, `matching`, `errors` and `declarations` pin drivine4j's decisions, the reference, as its schema grammars made them when they were captured, with no store. The compiler's module (`org.drivine.cypher.store.schema`, and `SchemaItemInfo`, `EnsureResult`, `SchemaPolicy`, `SchemaMode` and `SchemaDeclarations` in `org.drivine.schema`) makes them, and its tests check it gives these on every target. The listings' statements are the `catalog`'s, pinned once; what this pins is how their rows are read.

They are in `store.yaml`, and not a file of their own, because they read what `store.yaml`'s statements make and list: a listing's rows are the rows of its `catalog` queries, and an item is matched against the spec its `cases` make. `marker.yaml` is a file of its own because the marker is a contract apart from the DDL; this is not.

```yaml
schema:
  stores:
    neo4j: {engine: "Neo4j", supportsIfNotExists: true, supportsNamedItems: true, supportsTextIndex: true, constraintsRequireBackingIndex: false, constraintCreationIsAsync: false, indexOperationsAreAsync: false, awaitIndex: "CALL db.awaitIndex($name, $timeout)", indexState: "SHOW INDEXES ..."}
  tuning:
    - {spec: {...}, stores: {neo4j: [], memgraph: ["hnswM"], ...}}
  samples:
    - {spec: {kind: "UNIQUENESS_CONSTRAINT", label: "Person", properties: ["email"]}, limit: 10, stores: {neo4j: "MATCH (n:Person) ...", neo4j4: "...", postgres: "SELECT jsonb_build_object(...", ...}}
listings:
  - name: "SHOW INDEX INFO: range, composite, label-only, a vector's backing, and text indexes"
    listing: "indexes"
    rows: [["label+property", "Proposition", "contextId", 0], ...]
    stores: {memgraph: {items: [{kind: "RANGE_INDEX", label: "Proposition", properties: ["contextId"]}, ...], aliases: [[], ...]}}
matching:
  pairs:
    - {name: "...", existing: {kind: "RANGE_INDEX", label: "Person", properties: ["born", "name"]}, spec: {...}, stores: {neo4j: {identity: true, shape: true}, ...}}
  incomplete: [{item: {...}, stores: {oracle: true, ...}}]
  builds: [{status: "UNDER CONSTRUCTION", buildFailed: false, buildInProgress: true}]
errors:
  - name: "Oracle: ORA-01452, a unique index over rows that repeat a key"
    causes: [{sqlState: "72000", vendorCode: 1452, message: "ORA-01452: cannot CREATE UNIQUE INDEX; duplicate keys found"}]
    stores: {oracle: {violation: true, blockedByIndex: false, buildTimeout: false}, ...}
declarations:
  - {name: "...", specs: [...], items: [...]}
  - {name: "...", specs: [...], conflict: "Conflicting schema declarations for ..."}
```

A spec is written as `cases` write one, with a constraint's `nodeIdProperty`, the property a violation's sample names each node by, beside. A schema manager hands every rule a spec as it is made, as drivine4j's do: one that ignores case over the lowercased copies of its properties, so that its item is found, matched and sampled over them, not over the properties declared. An item, what a store has, is its `kind`, `label` and `properties`, and what the store gives of its `name`, `dimensions`, `similarity`, `analyzer`, `status`, `quantizationEnabled`, `hnswM` and `hnswEfConstruction`; a key left out is null, a value the store doesn't give.

| Key | What it is |
|---|---|
| `schema.stores` | What each store can do. `supportsIfNotExists`: its DDL takes `IF NOT EXISTS`, and where it doesn't, an item is found before it is made or dropped. `supportsNamedItems`: it keeps an item's name. `supportsTextIndex`: it has Neo4j's TEXT index, which is refused elsewhere. `constraintsRequireBackingIndex`: a uniqueness constraint needs a range index over its properties made first. `constraintCreationIsAsync` and `indexOperationsAreAsync`: a create or drop returns before it is done, and the listing is polled until it is (a constraint `OPERATIONAL` or `FAILED`, an index no longer being built, or gone). `awaitIndex`: what waits for an index named `$name` to be built, for `$timeout` whole seconds, after it is made; and `indexState`: what gives its `state`, read when that wait fails for another reason than its timeout. |
| `schema.tuning` | What a vector spec pins that each store can't make, named as the spec names it, for a library to warn of. |
| `schema.samples` | What samples the keys that repeat for a uniqueness constraint, `limit` of them at most, with `limit` of the nodes of each: each row `{properties, count, nodes}`, each node its `elementId` and, where the spec has one that isn't a key, its `nodeIdProperty`. `neo4j4` is Neo4j 4's, which names a node by `id(n)`, not `elementId(n)`. |
| `listings` | A listing's `rows`, as a driver gives them, and for each store that reads them the `items` they read as, in order, and the `aliases` each item's record keeps, the other names it goes by; or `invalid: true`, where the store refuses them. `listing` says which of the `catalog`'s queries gave them: `indexes` or `constraints`. |
| `matching.pairs` | An item a store has and a spec, and on each store whether the item is the spec's (`identity`), and if so whether it has the spec's shape (`shape`); an item that is the spec's but not in its shape is drift. |
| `matching.incomplete` | Whether each store makes an item it listed again, as one that lacks part of what it makes. |
| `matching.builds` | What a listed item's `status` says of its build, on a store that builds after the statement returns: `buildFailed`, and `buildInProgress`. |
| `errors` | A failure's `causes`, the chain of errors from the outermost in, and on each store whether it says the data violates a uniqueness constraint being made (`violation`), that a range index is in the way of the constraint (`blockedByIndex`), or that a wait for an index ran out (`buildTimeout`). |
| `declarations` | An owner's `specs`, in the order declared, and the `items` they make, or the `conflict` they are refused with. |

**A cause.** Each error of the chain is written as a library's driver gives it. A SQL driver's error gives its `sqlState`, and its `vendorCode`, the driver's own number, `0` where it gives none; a Neo4j driver's error, which Memgraph's are too, gives its `code`, `N/A` where it gives none, as drivine4j's driver does, and its `gqlStatus`; every error gives its `message`, and any other error only that. A `vendorCode` or a `sqlState` is what says an error is a SQL driver's, and a `code` that it's a Neo4j driver's. drivine4j's Neo4j driver gives the status `50N42` to an error a server sent none for, and a library whose driver gives none reads it as `50N42`.

**Identity.** An item is a spec's when its kind and label are the spec's, and its properties are the spec's in any order. On FalkorDB, which keeps one range index and one full-text index for a label over all its properties, a range or full-text item is the spec's when it holds all the spec's properties, with or without others.

**Shape.** A vector index is in its spec's shape when its `dimensions` are the spec's, always compared, so an index whose size isn't given is drift; and when its `similarity`, `hnswM` and `hnswEfConstruction` are the spec's, each where the store gives one and the spec pins one (the store's own options for these win on that store); and on Neo4j, its `quantizationEnabled`, by the same rule. FalkorDB's `efRuntime` is never compared. A full-text index is in its shape when the spec asks for no analyzer, the store gives none, or they are the same. Every other kind is always in its shape.

**Listings.** A row is what the listing's statement gives as one: the value of its one column, a map (a SQL store's JSON, decoded), or, where it gives several columns (Memgraph's `SHOW INDEX INFO`), the list of them. A number is handed over as a number, whatever type the driver gives it as (a JavaScript Neo4j driver's `Integer` too), and read as its whole part, `8.0` as `8`. Neo4j's listing gives a map a row: a relationship's index or constraint is passed over, and a type it doesn't manage (`LOOKUP`, `POINT`); `BTREE` is a range index; a uniqueness constraint is any type that holds `UNIQUENESS`; its similarity is read in any case, and one it doesn't know is null; quantization is `vector.quantization.type` other than `NONE`, in any case, where it is given, and `vector.quantization.enabled` where it is not. Memgraph's gives a vector index as a map and the rest as lists of columns, and a label with or without a leading `:`. FalkorDB's gives a row a label, and its range properties are one item, its full-text properties another, and each vector property an item of its own, each with the row's `status`; a vector's options are the options of its property where they hold the `dimension`, or else the row's options where those hold it, or else the options of its property. A SQL store's gives a record a row; Oracle's alone gives a `status`, `INCOMPLETE` for a range index that lacks its labelled index, which is made again.

**Errors.** On Neo4j, a violation is a driver's error of the chain with the code `Neo.ClientError.Schema.ConstraintValidationFailed` or the status `22N79` or `22N80`; or a chain of driver's errors whose every status is `50N11` or `50N42`, one of them with the code `Neo.DatabaseError.Schema.ConstraintCreationFailed`. An index in the way is the code `Neo.ClientError.Schema.IndexAlreadyExists`, and a wait run out `Neo.ClientError.Procedure.ProcedureTimedOut`. On Memgraph and FalkorDB, a violation is a chain whose messages, joined by ` | `, hold `constraint` and `violat`, in any case. On Postgres, it is a SQL error of the SQL state `23505`; on Oracle, of the vendor code `1` or `1452`; on SQLite, a SQL error whose own message holds `UNIQUE constraint failed`. No store but Neo4j has an index in the way or a wait. A violation is a suspicion only: a schema manager reports one where the sample finds a key that repeats, and fails with the error where it finds none.

**Declarations.** An owner's declarations of one kind, label and set of properties, as each is made (one that ignores case over the lowercased copies), are one item, kept in the order first declared. They must agree, as declared and not as made, on all but a vector index's options for a store, and a constraint's `nodeIdProperty`: the one declaration that pins a store's options wins, and two that pin different ones are a conflict; otherwise the first constraint with a `nodeIdProperty` wins, and otherwise the first. The item kept is a declaration as declared. The conflict's message names the kind, label and properties (as made, the copies where it ignores case), the reason, and each distinct declaration it is over, once, as drivine4j writes it: where they disagree on the rest, without a vector index's options for a store or a constraint's `nodeIdProperty`. The owner's version token is `marker.yaml`'s `versions`.

## The schema marker and the stamps

Each library keeps three things of its own in a store, and reads what another library kept: the schema marker, a node `(:_DrivineSchema {scope, version, items, appliedAt})` for each owner of a schema catalog, holding the version token it last applied and the inventory of the items it applied; the store's stamp, one node `(:DrivineStore {key: "singleton", storeId, assignedAt, engine})`; and the stamp on each node a save writes. `marker.yaml` pins them: drivine4j's, the reference, as it gave them when they were captured, for the graph `drivine`. The compiler's module (`org.drivine.cypher.store.marker`, and `org.drivine.cypher.store.Stamps`) writes them, and its tests check it gives these, byte for byte, on every target. Running the statements and reading their rows stay each library's.

They are a file of their own, not a part of `store.yaml`, because they are a contract of their own: `store.yaml` is the DDL a store is made with, and this is what the libraries keep in it once it is made, which changes apart from the DDL and is read on every enforce. The constraint each node is kept single by is made as `store.yaml` makes any, so its statements are pinned here too, as the module's DDL gives them.

```yaml
graph: "drivine"
marker:
  label: "_DrivineSchema"
  scopeProperty: "scope"
  defaultScope: "schema"
  separator: "\n"
  runtimePrefix: "runtime:"
  staleRuntimePrefix: "runtime-stale:"
  constraint: {kind: "UNIQUENESS_CONSTRAINT", label: "_DrivineSchema", properties: ["scope"]}
  scopesCypher: "MATCH (m:`_DrivineSchema`) RETURN DISTINCT m.scope"
  stores:
    postgres: {prepare: "ensuring", read: {version: "...", items: "..."}, readParameters: ["scope"], merge: "...", mergeParameters: ["scope", "version", "items", "now"], deleteAllButNewest: "...", delete: "...", scopes: null, itemsMark: "", constraint: [...]}
keys:
  - {spec: {kind: "RANGE_INDEX", label: "Person", properties: ["name"]}, key: "RANGE_INDEX:Person:name:Person_name_range"}
inventory:
  - name: "Stale items"
    record: {keys: [...], runtime: [...], stale: [...]}
    items: "RANGE_INDEX:Person:name:Person_name_range\nruntime-stale:UNIQUENESS_CONSTRAINT:Person:id:Person_id_unique"
    read: {keys: [...], runtime: [...], stale: [...]}
scopeFor:
  - {owner: null, scope: "schema"}
reads:
  - {stored: "items:", read: {keys: ["items:"], runtime: [], stale: []}, oracle: {keys: [], runtime: [], stale: []}}
versions:
  - {versions: ["v2", null, "v1"], token: "v1|v2"}
storeStamp: {label: "DrivineStore", keyProperty: "key", key: "singleton", constraint: {...}, parameters: [...], stores: {...}}
nodeStamps: {property: "__drivine.stamp", token: 16, separator: ":", neverStamped: "-", tokens: [...], stamps: [...]}
```

| Key | What it is |
|---|---|
| `marker` | The marker's label, the property that keys it to its owner, the scope of the owner with no name, and the constraint that keeps one marker for each scope. Each store's statements: `read` gives the `version` or `items` of the scope's newest marker; `merge` writes it, and makes it where there is none; `deleteAllButNewest` removes every other marker of the scope, and `constraint` makes the constraint that keeps one (when a library runs them is its schema manager's, today as drivine4j's `SchemaManager` decides, and #135's once the manager is in the module); `delete` removes it; and `scopes` reads the scope of every marker in SQL, on SQLite, where `scopesCypher` is read on the others. `prepare` names the statement of `store.yaml`'s `tables` a library runs first: Postgres's and Oracle's `ensuring`, SQLite's first `ddl`, and none on a graph engine. Every statement binds the scope as `$scope`; `mergeParameters` are what `merge` binds. `itemsMark` is what the inventory is kept behind on the store. `constraint` is what makes the marker's constraint, without the statements that make the store first, as `store.yaml` writes a case's `create`. |
| `keys` | Each spec's inventory key: `<kind>:<label>:<properties joined by ",">:<name>`, the name the spec's own, or the one derived from its label and properties where it has none or an empty one. A spec that ignores case is keyed as it is made, over the lowercased copies. |
| `scopeFor` | An owner's name, null for the owner with none, and the scope its marker is kept under. |
| `inventory` | A `record` of keys, the runtime ones and the stale ones; its `items`, as every store but Oracle keeps them; and what is `read` back from them. |
| `reads` | A stored text as a store gives it back, or null where no marker or no `items` came back, and what is `read` from it: null for no inventory known, which is not an empty one. `oracle` is what Oracle reads, where it differs, its mark taken off first. |
| `versions` | The version tokens of an owner's catalogs, null for one that sets none, and the token the owner records: null where none sets one. |
| `storeStamp` | The stamp's label, the property it is merged on and that property's value, its constraint, and what `assignIfAbsent` binds: `storeId`, a new UUID's text, `assignedAt`, the moment as ISO-8601 text in UTC, as Java's `Instant.toString` writes it (`2026-11-10T05:00:00.123456Z`: the fraction of a second in three, six or nine digits, the fewest that hold it, and none for a whole second), and not a number, as `readStamps` orders by it, and `engine`, the store's name as the stamp records it. Each store's `engine`, as the stamp records it, its `prepare`, run first as the marker's is, and statements: `assignIfAbsent` makes the stamp where there is none, and `readStamps` reads every stamp as a map `{id, assignedAt}`, the oldest first, tie-broken by id, and a library takes the first, so every reader takes the same one. |
| `nodeStamps` | The node stamp's property and form, two tokens of `token` hexadecimal digits joined by `separator`. `tokens` are the text of a token of 64 `bits`, as Java's `String.format("%016x")` writes it. Each of `stamps` is a stored stamp, its `nodeToken` (what comes before its first colon, or all of it), its `linksToken` (what comes after, or null for nothing), whether it is `twoTokens` (33 long, with its colon at 16), whether it `hasNodeToken` (longer than 16, with its colon at 16), and the stamp it is `withoutLinks`. `neverStamped` is what a save reports as the stamp of a node it found with none. |

**The inventory.** The `items` are the inventory keys, in the order they were recorded, joined by a newline. A key to be recreated when next ensured is written behind `runtime-stale:`, whether or not it is marked runtime too; any other key ensured at runtime behind `runtime:`; and any other as it is. A stale key is read back as a runtime one too, and a key marked runtime or stale that isn't among the keys is not written. An empty inventory is an empty text, so an inventory of one empty key is read back as an empty one. A key holding a newline, which a label can hold, is read back as two keys: the separator is not escaped, as drivine4j writes it. Oracle keeps an empty text as null, so it keeps the items behind `items:`, and takes that off as it reads them; a text with no mark is read as it is.

**The version token** is each catalog's token once, sorted by its UTF-16 code units, as Java sorts strings, and joined by `|`.

**The moment.** On Postgres, Oracle and SQLite, `merge` binds `appliedAt` as `$now`, milliseconds since the epoch by the caller's clock. The graph engines write their own clock's, with `timestamp()`, as drivine4j always has, and bind none. That split is kept on purpose: one clock for every store would be a change of its own, made in every library at once. On a SQL store the newest marker of a scope is the one with the largest `appliedAt`, a marker with none or with one that isn't a number coming last (Oracle reads a text of a number as the number), and of two with the same, the one made last (`nid`). A graph engine orders by `appliedAt` as it orders any value, which every marker it writes has.

## Host functions and readers

Compiled SQL calls functions each library registers on SQLite (`drivine_equal`, `drivine_add` …, and `regexp`, which SQLite calls for its `REGEXP` operator), and each library reads the rows itself, where a store's JSON holds no NaN or infinity: SQLite writes NaN as the tag `{"$numberDouble":"NaN"}` and an infinity as `9e999`, and Postgres, in the rows of a statement given NaN or an infinity, writes stand-in numbers past any double's. That code is each library's, not the compiler's, so `host.yaml` pins it, run by each library's unit suite with no container: on an in-memory SQLite with its functions registered, and on its row reader.

```yaml
- name: "drivine_add"
  description: "drivine_add(a, b) is Cypher's a + b of two JSON values, as JSON text: NaN adds as a number …"
  pairs:
    writes.yaml: ["NaN worked out from stored infinities", "NaN and the infinities written"]
  steps:
    - function: "drivine_add"
      args: ['9e999', '-9e999']
      result: '{"$numberDouble":"NaN"}'
```

A case has a `name`, unique in the file, a `description`, `pairs`, and its steps. A step has an optional `note` and is one of these:

| Keys | What it checks |
|---|---|
| `function`, `args`, `result` | The SQLite function, called with `args`, gives `result`. Each argument and the result is a SQL value: a text (the JSON text a function of JSON values takes, a rounding mode, or a regular expression), a whole number, a float, or null. A text result is compared exactly. `regexp` is called as SQLite calls it for `text REGEXP pattern`: `regexp(pattern, text)`, the pattern first. |
| `function`, `ascending` | The function of one argument, called on each of `ascending` in turn, gives values that ascend as SQLite orders them, each before the next. For `drivine_sort_key` of a list, whose text is each library's own: only its order is pinned. |
| `read`, `value` | A column of a compiled statement's rows reads as `value`, as `PersistenceManager.query` gives it. `read` has the `store` (`postgres` or `sqlite`), `nonFinite` (whether the statement was given NaN or an infinity, which the compiled statement says), and the `column`'s raw JSON text: a text, or a list of texts and `$repeat`s written one after the other. |

Values are written as in the other files: `{$float: "NaN"}` is NaN, `{$repeat: …}` a text repeated, and any other map, `{$numberDouble: "2.0"}` among them, is the map it is.

**Pairs.** A host function and the compiled statements that call it are one contract: a change to one alone changes what a statement gives. So each case's `pairs` names, by file, the store cases in `queries.yaml`, `writes.yaml` and `temporal.yaml` that rely on its steps, and a runner checks, before the case passes:

- that each case named is in its file;
- for each function its steps call, that a step of a paired case, run on SQLite, compiles to SQL that calls it: for `regexp`, SQL that holds the operator `REGEXP`;
- for each `store` and `nonFinite` its steps read, that a step of a paired case, run on that store, compiles with that `nonFinite`.

A change to a host vector names the store cases it moves, and a change to a paired store case shows which host vectors it leans on. A host case with no pairs, or whose pairs no longer rely on it, fails.

**Regular expressions.** On SQLite a regular expression the compiler writes, given to `REGEXP`, `drivine_regex_group` or `drivine_regexp_replace`, is read by each library's own language: Java's, JavaScript's or Python's, each rewritten from Java's as its library rewrites it. So the compiler writes them only with what `host.yaml`'s `regexp` case pins every library reads alike: `^` for the text's start and `\z` for its end; each class written out, with `\t`, `\n`, `\f`, `\r`, `\xNN` and `\uNNNN` and ranges of them, and `[0-9]` for a digit; a backslash before a character of the syntax (`^$\.*+?()[]{}|/`) for the character; groups, `(?:…)`, alternatives, and `?`, `*`, `+` and `{m,n}`. It writes none of what the libraries read otherwise: `\s`, `\d`, `\w`, `\b` or any other class escape, which are Unicode's on drivine4j and partly ASCII's on Drivine for TypeScript; `$`, which in Java matches before a line break that ends the text too; `\Z`; `.`, which in Java matches no U+0085; a class within a class, or `&&` in one, which Java reads as their union and intersection; a POSIX class (`[:alnum:]`) or `\p{…}`; or a flag group. A pattern a statement gives `=~` is its own, read as Java reads it: the compiler's group around it, `^(?:…)\z`, is written as above, and a leading `(?i)` in it is pinned. The compiler's tests check every pattern it writes on SQLite over the whole spec.

## Full-text search

A full-text search (a library's `loadMatching`) is given a label, the properties of its full-text index, a query as a user writes it, and `topK`, and gives the best `topK` nodes that match, each with a score. On a graph engine the store's own index answers it: Lucene's on Neo4j, Tantivy's on Memgraph, RediSearch's on FalkorDB. On a SQL store Drivine parses the query and scores the nodes itself, by BM25, or through Oracle Text where Oracle has the index. `search.yaml` pins what a search finds, the same on every store, and how close each other store's ranking must come to Neo4j's. Neo4j's is the one the others are held to.

```yaml
graph: |
  CREATE (:Doc {id: 'graphs', body: 'graph databases store graph data'}), ...
indexes:
  - {kind: "FULLTEXT_INDEX", label: "Doc", properties: ["body"]}
cases:
  - name: "Words are ORed"
    description: "A node that holds any of a query's words is a hit."
    steps:
      - search: {label: "Doc", properties: ["body"], query: "soup kiwi", topK: 50}
        ids: ["kiwi", "soup"]
        today: [{stores: ["falkordb"], ids: [], issue: "liberation-data/drivine4j#666"}]
ranking:
  corpus: "search/docs-corpus.json"
  write: "UNWIND $rows AS r CREATE (:Article {id: r.id, title: r.title, body: r.body})"
  index: {kind: "FULLTEXT_INDEX", label: "Article", properties: ["body"]}
  search: {label: "Article", properties: ["body"]}
  top: 5
  recorded: {image: "neo4j:2026.10.1", digest: "neo4j@sha256:…", statements: [...]}
  bars:
    - {stores: ["postgres", "oracle", "sqlite"], sameTop: 17, overlap: 4.3, target: {sameTop: 21, overlap: 4.5}, measured: {...}}
    - {stores: ["memgraph"], sameTop: 16, overlap: 4.09, measured: {...}}
    - {stores: ["oracle-text"], sameTop: 5, overlap: 2.57, note: "...", measured: {...}}
    - {stores: ["falkordb"], sameTop: 9, overlap: 2.04, note: "...", measured: {...}}
  queries:
    - {query: "GraphObjectManager", neo4j: ["legacy-graph-object-manager#1", ...]}
```

**The stores.** A search runs on `neo4j`, `memgraph`, `falkordb`, `postgres`, `oracle`, `oracle-text` and `sqlite`. Oracle is two stores here: `oracle` has no full-text index made, and Drivine scores by BM25; `oracle-text` has the index made, which is Oracle Text, and Oracle Text scores. The group `graph` is the three graph engines (Neptune has no full-text search, and runs none of the file), and `sql` is `postgres`, `oracle`, `oracle-text` and `sqlite`. A step's `only` and `except` name them as the other files' steps do.

**The exact cases.** For each store, a runner empties the graph, makes each of `indexes` (as the schema API ensures a spec, `store.yaml`'s DDL; on `oracle`, none), writes `graph`, and waits for the indexes to hold it (`CALL db.awaitIndexes()` on Neo4j; Oracle Text's index holds what is committed, so the write is committed first). On Postgres it runs every case twice, with the indexes made and without them: the index only narrows the nodes read, and both give the same hits. Each step is then one search, with no threshold, and its hits named by their `id` property:

| Key | What it is |
|---|---|
| `search` | The search: its `label`, the `properties` of its index, its `query`, and `topK`. |
| `ids` | The hits, in any order. |
| `ranked` | The hits, best first. |
| `error` | The message the search is refused with. |
| `only`, `except` | The stores the step runs on, or doesn't. |
| `today` | Not read. What a store gives today where it differs, and the issue that makes it pass: `stores` (stores or groups), and `ids`, `ranked` or `error`, or none of them where it gives anything but the step's. A library holds such a step in its own HELD list, so the step says what every store is to give. `unfiled` marks a difference with no issue yet. |
| `note` | Why the step is there. It is not read. |

No step rests on how a store breaks a tie. On Neo4j no two hits that a `ranked` step orders, or that `topK` keeps or leaves out, score alike; where another store scores them alike, the step leaves that store out (`except`), and where the tie is only of order and not of which hits `topK` keeps, a step of `ids` pins its hits there.

A case says what Neo4j's index gives, with Neo4j's default analyzer, `standard-no-stop-words`: words ORed, a phrase, `+` and `-`, `AND`, `OR` and `NOT` (and `&&`, `||`), `word*`, `field:word`, escapes, case and Unicode. A refusal is Drivine's own, not Neo4j's: a query with no word to look for (operators, punctuation or excluded words alone) and an empty one are refused with `A full-text query needs a word to look for: '<query>' has none.`, on every store, where Neo4j gives no hits, every node, or a parse error.

**The ranking.** Scores differ between stores in scale, so the ranking pins ids and order, not scores. A runner writes the corpus (`search/docs-corpus.json`, a section of Drivine's docs to a node, `{id, title, body}`) with `write`, bound as `$rows`, makes `index` and waits for it as the exact cases do (on `oracle`, none; on Postgres, with it and without it), and asks each of `queries` for its best `top`, by `search`. Over the queries whose `neo4j` is not empty, it counts those whose top hit is Neo4j's, and the mean of how many of the top five are in Neo4j's top five, as sets. On each store of a bar, the count must be at least `sameTop` and the mean at least `overlap`. `target` is where the BM25 stores' bar goes once BM25 measures a text's length in words (liberation-data/drivine4j#665), and the other bars have none yet; `measured` is what each store gave when the file was recorded, and is not read, and so is a bar's `note`. Each store's bar is what it gave when the file was recorded, rounded down, so a change can't make any store rank further from Neo4j, and no higher than the store gives however it breaks a tie between hits that score alike, at the top or at the fifth, so a runner needn't break ties of its own (Oracle Text's and FalkorDB's bars are lowered so, as their notes say; on Neo4j no two of a query's first six hits scored alike); Oracle Text's is its own, as it isn't BM25, and Memgraph's and FalkorDB's are their search engines'.

**The corpus** is drivine4j's `src/test/resources/fulltext/docs-corpus.json` at dd4a66f0, byte for byte (SHA-256 `865f814ceaba8ade6ba604dcb3614aa344529c0f701991c40b6c169ee4c9c0e5`), which drivine4j's `FullTextRankingAgreementTest` reads: the sections of Drivine's own docs at 3a8b0b12, as that test says, each a node. Its `queries` are that test's.

**Recording Neo4j's ranking again.** `recorded` names the image the `neo4j` lists were recorded on, by digest, the `version` it reported and the `date`, and the statements that record them: the index made and filled, and each query asked of it. When Neo4j's pin moves, run them on the new image, through the test lock, and write the lists again with the new image's name and digest; a change of a list is Neo4j's and is said so in the pull request. drivine4j's `Neo4jRanking` asks Neo4j the same way.

## Changing the spec

These files are a contract between the three libraries. They live in [drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec), and each library copies a tagged version into its tests, unchanged, with a script of its own (`scripts/sync-cypher-spec.sh` in drivine4j). A change of what the compiler gives or refuses lands there first, in a tagged release, and each library then moves to that tag.

Some tests stay in each library's own suite: values that change on every run (a UUID, the time), parameters of a language's own temporal types, statements built in code (hundreds of `CREATE`s), and whatever needs a connection of its own (a rolled-back transaction).
