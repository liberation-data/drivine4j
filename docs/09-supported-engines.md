## Supported Engines

Drivine4j supports multiple graph database engines from the same codebase. Switch engines with a one-line YAML change — your models, queries, and DSL code stay the same.

| Engine | Type | Transactions | Collection Sort | Auth |
|--------|------|-------------|----------------|------|
| Neo4j 5.x | `NEO4J` | Full ACID | APOC (default) or CALL subquery | Basic (user/pass) |
| Neo4j 4.x | `NEO4J` | Full ACID | APOC (required) | Basic (user/pass) |
| FalkorDB | `FALKORDB` | Passthrough (no multi-statement) | CALL subquery | None |
| Amazon Neptune | `NEPTUNE` | Full ACID | CALL subquery | IAM SigV4 or None (tunnel) |
| Memgraph | `MEMGRAPH` | Full ACID | CALL subquery | Basic (user/pass) or None |
| Oracle 23.9+ | `ORACLE` | Full ACID | In the statement | User and password |
| Postgres 14+ | `POSTGRES` | Full ACID | In the statement | User and password |
| SQLite 3.45+ | `SQLITE` | Full ACID, one writer at a time | In the statement | None (a file) |

### Neo4j

The default engine. Works out of the box with Testcontainers or a local instance:

```yaml
database:
  datasources:
    graph:
      type: NEO4J
      host: localhost
      port: 7687
      user-name: neo4j
      password: your-password
      database-name: neo4j
```

### FalkorDB

FalkorDB is an in-memory graph database built on Redis. It offers extremely fast query execution but does not support multi-statement transactions.

```yaml
database:
  datasources:
    graph:
      type: FALKORDB
      host: localhost
      port: 6379
      database-name: mygraph
```

**Transactions:** FalkorDB does not support multi-statement transactions. `@Transactional` methods work but each query executes and commits independently. An object-manager `save` is one statement and so still atomic, and so is a `saveAll` of fragments alone that fits in one; an `update` is two and a `saveAll` that holds a view several, and what ran before a failure or a refused save stays applied. By default, `startTransaction()` logs a debug message, `rollbackTransaction()` logs a warning, and the first batch of more than one statement logs a warning that it is not applied whole. To enforce strict no-transaction usage (throw on `@Transactional`, and refuse a batch of more than one statement with `NonAtomicBatchException` before any of it runs), set:

```yaml
      falkor-db-transaction-mode: STRICT   # default: WARN
```

**Jedis:** the FalkorDB client needs Jedis 8. Spring Boot's dependency management sets an older one (6.0.0 on Boot 3.5, 7.4.1 on Boot 4.1), and a managed version in your build wins over the one Drivine declares, so a FalkorDB datasource fails at start-up with `NoSuchMethodError` on `DefaultJedisClientConfig.Builder.autoNegotiateProtocol`. Set it yourself:

```xml
<!-- Maven, with the Spring Boot parent -->
<properties>
    <jedis.version>8.0.1</jedis.version>
</properties>

<!-- Maven, importing a BOM: declare it BEFORE the import -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
            <version>8.0.1</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```groovy
// Gradle, with the Spring Boot plugin
ext['jedis.version'] = '8.0.1'
```

**Known limitations:**
- Nested pattern comprehensions returned NULL before [FalkorDB#1888](https://github.com/FalkorDB/FalkorDB/issues/1888) was fixed — Drivine projects a nested view with a CALL subquery prolog, one level of its relationships deep. A nested view that holds views of its own, itself among them, is projected inline with nested comprehensions, which current FalkorDB reads
- FalkorDB 6 empties a pattern comprehension that follows one nesting another in the same projection ([FalkorDB#3004](https://github.com/FalkorDB/FalkorDB/issues/3004); 4.x reads it right) — a recursive view whose levels hold a nested view loads its first level only there, until the fix is released
- `collect()` on null includes null maps ([FalkorDB#1889](https://github.com/FalkorDB/FalkorDB/issues/1889)) — Drivine filters with `CASE WHEN IS NOT NULL`
- FalkorDB 6 plans an `OPTIONAL MATCH` of several hops from the labelled node it ends at, and reads every relationship into that label ([FalkorDB#3332](https://github.com/FalkorDB/FalkorDB/issues/3332)) — a `@GraphPath` field leaves the label out of its pattern there and tests it on the node reached
- FalkorDB 6 scans a label for a `WHERE` in parentheses, where it would have used an index ([FalkorDB#3331](https://github.com/FalkorDB/FalkorDB/issues/3331)) — a load writes none around its conditions or around a keyset cursor (`seek`), and a load or a count of a fragment writes none around an `anyOf` that is its whole filter. An `anyOf` keeps its parentheses everywhere else, and FalkorDB 6 scans for it where nothing else in the filter has an index: beside another condition, nested in another `anyOf`, in a view, and in a `deleteAll`

CASCADE `DELETE_ORPHAN` is supported on current FalkorDB ([FalkorDB#1890](https://github.com/FalkorDB/FalkorDB/issues/1890) is fixed in the graph module Drivine tracks); older builds lacking that fix are not supported for orphan delete.

### Amazon Neptune

Neptune is AWS's managed graph database. Drivine connects via the Bolt protocol with two authentication modes.

`StatelessGraphObjectManager`, `@NodeStamp` and the stamps `edges` writes have not been run against Neptune as of 0.1.0, and neither have [grouped reads](06-object-manager.md#grouped-reads-groups). Loading and querying are as before.

**IAM SigV4 authentication (recommended for production):**

```yaml
database:
  datasources:
    graph:
      type: NEPTUNE
      host: your-cluster.us-east-1.neptune.amazonaws.com
      port: 8182
      region: us-east-1
      neptune-auth: IAM
```

Requires AWS credentials available via the standard AWS credential chain (`~/.aws/credentials`, environment variables, IAM role, etc.) and the AWS SDK on the classpath:

```gradle
implementation 'software.amazon.awssdk:auth:2.31.3'
implementation 'software.amazon.awssdk:regions:2.31.3'
implementation 'software.amazon.awssdk:http-client-spi:2.31.3'
```

Tokens are automatically refreshed before expiry.

**No authentication (SSH tunnel for development):**

```yaml
database:
  datasources:
    graph:
      type: NEPTUNE
      host: localhost
      port: 8182
      neptune-auth: NONE
```

Use with an SSH tunnel to a Neptune cluster that has IAM auth disabled:
```bash
ssh -N -o ServerAliveInterval=60 -L 8182:your-cluster.neptune.amazonaws.com:8182 ec2-user@bastion-ip
```

**Known limitations:**
- No `date()` function — use string dates or `datetime()` for temporal properties
- No list/array property values — annotate collection fields with `@JsonPacked` to store as JSON strings
- `collSortMaps` uses `{key: 'prop', order: 'asc'}` syntax (differs from APOC)

### Memgraph

Memgraph is an in-memory, Bolt-compatible graph database with Neo4j-compatible Cypher. Drivine reuses the Neo4j driver stack — only the Cypher dialect differs (no APOC; collection sorting via `CALL` subqueries).

```yaml
database:
  datasources:
    graph:
      type: MEMGRAPH
      host: localhost
      port: 7687
      user-name: ""        # Memgraph accepts empty credentials by default
      password: ""
```

**Notes:**
- Full ACID transactions (`startTransaction` / `commit` / `rollback` all work as expected)
- `EXISTS { pattern }` and nested pattern comprehensions are supported, so `@GraphView` queries use the same inline projector as Neo4j
- No APOC — use MAGE for procedures; collection sorting uses CALL subqueries by default
- No `CascadeType.DELETE_ORPHAN`: Memgraph cannot use `EXISTS` inside `WITH`, so a save or delete that asks for it throws `UnsupportedOperationException`. On `StatelessGraphObjectManager`, `Replace(field, removedTargets = DELETE_UNREFERENCED)` works on Memgraph
- For MAGE algorithms or Memgraph Lab, switch the image to `memgraph/memgraph-platform`

### Oracle

Oracle Database 23.9 or later, over JDBC. Oracle does not speak Cypher: Drivine reads a graph with
SQL/PGQ (`GRAPH_TABLE`) and writes it as rows, since Oracle refuses data manipulation through a
property graph. Models, the query DSL and `StatelessGraphObjectManager` are the same as on the graph
engines. Add the driver yourself: `com.oracle.database.jdbc:ojdbc11`.

Drivine needs 23.9 because Cypher's `randomUUID()` is Oracle's `UUID()`, a random version 4 UUID,
which Oracle added in 23.9. A provider reads the version from its first connection's metadata, which
needs no grant. On an older Oracle, plain SQL still runs, and a Cypher statement is refused with the
version found.

```yaml
database:
  datasources:
    graph:
      type: ORACLE
      host: localhost
      port: 1521
      database-name: FREEPDB1     # the service to connect to
      user-name: app
      password: secret
      default-graph-path: drivine # names the property graph and its tables; this is the default
```

`user-name` and `database-name` are required, and `port` is 1521 unless given. A `cypher-dialect` or a
`protocol` means nothing here and is ignored with a warning. The graph's name names tables, so it is
letters, digits and underscores, begins with a letter, and is at most 114 characters.

Drivine makes a connection pool (HikariCP) for the datasource: `pool-max` connections at most (40
unless given), `connection-timeout` milliseconds to wait for one (5,000 unless given), and
`idle-timeout` milliseconds before an idle one is closed (the pool's own default unless given). The
pool asks the database for a connection as it is made, so a service that cannot be reached or a user
that is turned away fails at startup. Each connection keeps 64 statements prepared
(`oracle.jdbc.implicitStatementCacheSize`): a save is the same block each time, and is neither sent
nor parsed again. A Cypher statement compiled to SQL is not kept: Oracle holds a kept statement's memory
for as long as the driver keeps it, from about 4 MB for a compiled statement to over 20 MB for one that
reads a long text, and a few dozen of them ran the instance out of memory (ORA-04036, #313). So it is
prepared again each time it runs: about 0.3 ms more for a short statement (Oracle reuses its plan; it
doesn't parse it again from scratch). The object manager's statements are kept, so where the object
manager does what you need, it is the faster way to Oracle.

To use a `DataSource` the application already has, register a provider in code:
`registry.register(JdbcConnectionProvider("graph", DatabaseType.ORACLE, dataSource, graphName = "drivine"))`.
Drivine does not close a `DataSource` it was given, and does not configure it: turn the driver's
statement cache on there yourself. A Cypher statement compiled to SQL is kept out of that cache too. It does set each of its sessions, the first time it connects with one, to make no
adaptive plan, unless you ask it not to (see the notes below). For a pool of Drivine's own (HikariCP) without the YAML:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.ORACLE, JdbcConnectionProvider.oracleUrl("localhost", 1521, "FREEPDB1"), "app", "secret",
    graphName = "drivine",
)
```

**How a graph is stored.** Two tables and the property graph over them, made, where they are not
there yet, before the datasource's first connection: `<graph>_node (nid, labels, props, stamp)` and
`<graph>_edge (eid, src, dst, type, props)`, with `labels` and `props` as JSON. Labels, property
names and relationship types are data, so `@NodeLabels`, `@PropertyBag` and `edges.relate` work as
they do elsewhere. To SQL written by hand the graph has one vertex label, `node`, and one edge label,
`edge`; a Cypher label is a test on `labels` and a relationship type a test on `type`. A node's stamp is
the `stamp` column, and a load returns it as `__drivine.stamp`. Deleting a node's row deletes its
relationships with it. A third table, `<graph>_schema`, records the indexes and constraints Drivine
made: see [Schema Management](10-schema-management.md#schema-management-indexes--constraints). Beside the tables the graph is made with
one function, `<graph>_nulls_equal`, which a compiled Cypher statement compares two lists or maps that may hold a null
by (see the notes below). Making it takes `CREATE PROCEDURE`, which the `RESOURCE` role grants, as well as the
`CREATE TABLE` the tables take. A store an earlier version made gains it before the datasource's first connection, as a new one gains its tables. Where the
user that makes the graph may not make a function, the graph is made without it, and Drivine logs a warning once
for the graph when a statement would call it, and looks for it again a minute later: a statement that writes, or the step of a `reduce`, then compares two
lists or maps that hold a null and differ as JSON as false, where Cypher may give null (`[null] = [1]`), and a read
declares the function itself.

**Notes:**
- Each Oracle session Drivine uses makes no adaptive plan (`ALTER SESSION SET OPTIMIZER_ADAPTIVE_PLANS = FALSE`).
  Drivine's pool runs it as it opens a session, and drops a session it fails in; a session of a `DataSource` you gave
  Drivine is set when Drivine first connects with it. Once a graph's tables had grown and shrunk without statistics,
  Oracle's adaptive join plan gave no rows for a read that filters a node by a label and a property, where the same
  plan, fixed, gave them all ([#289](https://github.com/liberation-data/drivine4j/issues/289)). A statement of yours
  that relies on an adaptive plan can ask for one with the hint `/*+ OPT_PARAM('optimizer_adaptive_plans' 'true') */`.
  To have Drivine leave the sessions of your `DataSource` as you set them, pass `setsNoAdaptivePlans = false` to
  `JdbcConnectionProvider`. It is on by default because, without it, a read can come back with no rows and no error;
  if you turn it off, run that statement in each session yourself (HikariCP's `connectionInitSql`, say).
  Drivine's own pool sets its sessions either way.
- A statement written by hand is SQL. Parameters are named `$name` as in Cypher. A collection is
  bound as JSON, for `JSON_TABLE` to read. A column of the JSON type comes back as a map, so
  `SELECT JSON_OBJECT(… RETURNING JSON)` hydrates with `.transform(MyView::class.java)`.
- A `CypherStatement` is compiled to SQL over the graph's tables (see
  [Cypher on a relational database](05-cypher-on-a-relational-database.md#cypher-on-a-relational-database)) and gives the rows a graph engine
  gives: a read is a query, and a write (`CREATE`, `MERGE`, `SET`, `REMOVE`, `DELETE`) an anonymous
  PL/SQL block, applied whole or not at all. A `RETURN` of several items gives a list of them for
  each row. A text read out of JSON past 4,000 bytes is read whole where it is measured, cut, changed, searched or
  reversed; an empty text is null. `reduce` is a function the
  query declares, and does not run in a statement that writes, in the step of a `reduce`, or where its step reads a path.
  Two lists ordered with `<`, `<=`, `>` or `>=` are walked item by item by a function the query declares too, so such a
  comparison is refused in a statement that writes and in the step of a `reduce`.
  Two lists or maps that may hold a null are compared item by item, as Cypher compares them: where one holds a null, two
  written as the same JSON are null at once, and any other two are walked by the graph's function `<graph>_nulls_equal` (see above), in a statement that
  writes and the step of a `reduce` too. `list[i]` reads an item where an expression gives
  the index, and `map[key]` reads null where an expression gives the key: Oracle's paths read no key so.
  What is not compiled yet (a path returned whole, `SKIP` of an expression…) is refused by
  name before anything is sent. What the grammar does not read is refused as not Cypher, with the
  place in the statement it stands at. `UNION`, `CALL { … }` (one that writes too), `EXISTS { … }`, `COUNT { … }`,
  `FOREACH`, `=~`, `shortestPath` and `allShortestPaths` compile, as do quantified relationships (`-[:R]->{1,3}`) and
  quantified path patterns (`(a) ((x)-[r:R]->(y) WHERE x.v < y.v){1,3} (b)`), whose variables are lists of what each
  repetition matched. A quantified path pattern's `WHERE` and properties read no variable from outside it on Oracle,
  whose recursive queries read nothing of the row they are in, and hold a subquery only where no variable of its
  `MATCH` or before it is named, and that walks no variable-length relationship or quantified path pattern of its own;
  on Postgres they may. `CALL { … } IN TRANSACTIONS` is one statement here, applied whole, where a graph
  engine commits each batch as it goes. A list of nodes or relationships (`collect(n)`, `nodes(p)`, `[(a)-->(m) | m]`) is given as
  their properties, and unwound to the nodes themselves, so `FOREACH (n IN nodes(p) | SET …)` writes to them; compared,
  indexed, searched with `IN` or iterated, its items are the nodes themselves too, so two with equal properties differ.
  `CALL db.labels()` and `CALL db.relationshipTypes()` compile, alone or within a query with `YIELD`, and give each
  label and type something has now, read from the nodes and relationships; any other procedure is refused. A parameter
  may be named by a keyword (`$limit`), as in Neo4j. Functions of numbers (`exp`, `log`, `sin`, `atan2`, `round` by a
  mode …) give the floats Neo4j gives, but where Neo4j gives NaN or an infinity (`log(0)`, `sqrt(-1)`, `exp(1000)`) the
  statement fails: JSON holds neither.
- A property that a `RETURN` or a `WITH` returns or passes on (`RETURN n.name`) is read through its text, which
  costs about 2.4 to 3.8 times a plain read: 100,000 rows of `RETURN n.name` take about 490 ms, against 150 ms.
  This works around an Oracle bug, seen on 23.26.3: a string past about 32 KB taken from JSON as JSON, returned or fed
  on into more SQL, crashes the session (ORA-03113, with ORA-00600 [17114] or ORA-07445 in the alert log) over two or more rows. Only
  an item that is the property itself is read so: one inside an item (`RETURN [n.name]`, `collect(n.name)`) is read as
  JSON, as before, and isn't worked around. It goes once an Oracle release no longer crashes on the repro in
  [docs/oracle-long-json-string-crash.md](../docs/oracle-long-json-string-crash.md). Such a text still can't be sorted
  by, grouped by or made `DISTINCT`: Oracle sorts and groups JSON only up to about 32 KB, and refuses the statement
  (ORA-40180), leaving the session open.
- Writes are worked around for the same bug: a text that a write takes from an unwound parameter's item, or from
  another value of its rows, to write it (`UNWIND $rows AS r MATCH (n {id: r.id}) SET n.a = r.a`, `CREATE (:X {a:
  r.a})`, `MERGE … SET n.a = r.a`) is read from the row's text, at no cost measured: 1,000 rows set by one statement
  take as long as before. Any other value, a map set whole by `SET n += r` among them, is read as before. So is a
  value that a `UNION` in a `CALL { … }` takes from different values of the row in its queries, or that an `OPTIONAL
  CALL` returns, which may be null.
- So are reads of an unwound item's value: a text that a `RETURN` or a `WITH` gives from an unwound parameter's item,
  or from another unwound list's (`UNWIND $rows AS r RETURN r.a`, `WITH r.a AS a`, `SET n.a = r.a RETURN r.a`), is
  read from the item's text, at about 1.3 times a plain read: 100,000 rows of `RETURN r.a` take about 870 ms, against
  660 ms. Any other value is read as before, and so is a value of a map made of a node's properties and not unwound
  (`WITH n {.a} AS m RETURN m.a`); after a write, which carries its rows on, such a value is read from the item's text too.
  A pattern that matches on such a text (`MERGE (n:X {a: r.a})`) compares it as JSON, which Oracle refuses past about
  32 KB (ORA-40180), leaving the session open.
- So are reads of a list's item by its place ([#388](https://github.com/liberation-data/drivine4j/issues/388)): an item
  read by index, by `head()` or `last()`, or after a slice, of a list `split` makes, a stored list, or one a `WITH`
  passes on (`split(n.bio, 'x')[0]`, `head(n.tags)`), is read from the list's text where it is a text, at about 1.4 to
  2.5 times a plain read, and the list is written out whole for it. Any other item is read as before, and so is an
  unwound item. A list made of a long property (`[n.bio, 'y']`) still takes the property out as JSON, as above.
- An `OPTIONAL CALL` or an `OPTIONAL MATCH` is joined `ON 1 = 1`, where Postgres joins `ON TRUE`. This works around
  an Oracle bug, seen on 23.26.3: in a scalar subquery, as a `COUNT { … }` is compiled, a join `ON TRUE` is refused
  (ORA-00907) or crashes the session (ORA-03113, with ORA-07445 in the alert log)
  ([#338](https://github.com/liberation-data/drivine4j/issues/338)). It goes once an Oracle release no longer fails
  the repro in [docs/oracle-on-true-in-scalar-subquery-crash.md](../docs/oracle-on-true-in-scalar-subquery-crash.md).
- A compiled query that reads the graph's node or relationship table twice or more, and each query of a write that
  does, carries the hint `NO_JSON_TABLE_TRANSFORM`. This works around an Oracle bug, seen on 23.26.3: Oracle's JSON
  table transformation reads several JSON values of one node's row by one `JSON_TABLE`, and so rewritten beside
  another node's table the query lost that node's label and property tests, giving rows its pattern doesn't have and
  values of the wrong node, or crashing the session (ORA-03113)
  ([#415](https://github.com/liberation-data/drivine4j/issues/415)). A query of one table carries no hint.
- A list worked out on each row by a subquery (`keys(n)`, `[i IN range(1, k) | i]`, `[(p)-->(q) | q.name]`) is read
  from a derived table inside a lateral view. This works around an Oracle bug, seen on 23.26.3: a subquery that reads
  the row, in the select list of a lateral view that begins a subquery's `FROM`, was worked out once for the
  statement and not for each row, so `WHERE 'b' IN keys(n)` found nothing
  ([#433](https://github.com/liberation-data/drivine4j/issues/433)).
- openCypher's temporal values are their ISO 8601 text, as the store keeps a moment. `date`, `localtime`,
  `time`, `localdatetime`, `datetime` and `duration` make one of a text, of a map of its fields (a calendar
  date's and a clock's, not a week's or an ordinal day's), or of the moment the statement runs. Their fields
  read (`d.year`, `t.hour`, `dur.days`), a duration is added to a moment or to another duration,
  `duration.between` and `truncate` work them out, and two moments compare as the moments they are. A zoned
  value the statement makes sorts as its moment (`ORDER BY datetime(n.at)`); one read as a property sorts as
  its text, and so does a map that holds one. A list of them, or a list of lists of them, sorts item by item
  so in an `ORDER BY`, `min` and `max`. Oracle reads by their places, not with `JSON_TABLE`, the items of a
  list that holds temporal values the statement makes, at any depth: a list of them, a list of lists or of
  maps of them (`collect(DISTINCT [l, l])`), one a `CASE`, `coalesce` or `reduce` may give, or one taken back out
  of a map; and the keys of any map, which `keys` reads from a list Oracle makes of them. Oracle 23
  takes many seconds, or minutes, to parse a `JSON_TABLE` over a list worked out item by item with temporal
  arithmetic (`[i IN range(1, 10) | t + duration({minutes: i})]`), and neither a query timeout nor a kill ends
  the parse ([#331](https://github.com/liberation-data/drivine4j/issues/331), [#347](https://github.com/liberation-data/drivine4j/issues/347)).
  Other lists keep `JSON_TABLE`, which is about ten times as fast for a short list; read by their places,
  the keys are faster than `JSON_TABLE` gave them. A zoned value keeps the offset it was written
  with; a named zone, such as
  `Europe/Paris`, is not read. A graph engine's driver gives a temporal value as one of its own, and Oracle
  and Postgres give its text. A value read from a property is of the kind its text tells, so
  `n.day + duration('P1D')` adds a day to a date stored as `2024-03-15`. Two values the statement makes
  neither of temporal (by a constructor, or a parameter of a `java.time` type) are not added, taken away,
  scaled or compared as moments.
- A statement that writes and returns rows is a PL/SQL block that hands them back with
  `DBMS_SQL.RETURN_RESULT`. A block is one statement: one that fails writes nothing.
- A raw `whereClause` is a SQL predicate. A property of the root may be written as Cypher writes it
  (`issue.state = 'open'`); anything more is SQL over `issue.props`, `issue.labels` and `issue.stamp`.
- A save is one statement and checks the stamp, as on the graph engines. Another writer that changes a
  node in SQL should give it a new stamp too: the first sixteen characters of `stamp` for its
  properties or labels, the last sixteen for its relationships. `Stamps.setClause` and
  `Stamps.linksClause` are Cypher and do not apply.
- A save the database turns away because another writer held a row it wanted, a deadlock (`ORA-00060`)
  or a serialization failure (`ORA-08177`), is run again, as on Neo4j and Memgraph.
- `saveAll` saves fragments 500 to a statement, and each view by a statement of its own. The batch is
  one transaction.
- `patchAll` writes 500 nodes to a statement, and 100 where a `@VectorIndex` field is written, in one transaction.
- `patch` is one block. Its predicate is run inside the block as a statement of its own, since Oracle Free 23.26
  takes no parameter inside a `GRAPH_TABLE` of a block's own SQL, which is how a predicate on a relationship is written.
- A delete can cascade with `DELETE_ALL` and with `DELETE_ORPHAN`.
- Index the id of each node type you save: `constraints.ensure(UniquenessConstraintSpec("Person", "id"))`
  makes a unique index and the index a node is found by. Without one a save reads the table.
- A range index reads a property as text. A comparison or an order by a number or a moment is correct
  and is not served by that index, so an ordered or keyset-paged load is given no index advice.
- A range index is two indexes on Oracle: one over the property whatever the label, which a save and
  a load by id find a node by, and one over the property of the label's nodes alone. A `loadAll`, a
  `count`, a `deleteAll` or a `groups` whose `where` compares an indexed property with a text (`eq`,
  `gt`, `gte`, `lt`, `lte`) reads the second, and does not test the label on its own: the one index
  answers both. What else the `where` holds is tested on the nodes it gives. `IN`, `neq`, a test of
  part of a text, a comparison inside an `or`, and one with a number are read as before. A
  uniqueness constraint over one property already has an index over the label's nodes, its unique
  index, which reads the same value: a filter on that property is read through it, and no second
  one is made. A range index made by an earlier version is found, listed and dropped as it is, and
  the next `ensure` of it adds the index over the label's nodes. The backend reads which there are
  from `<graph>_schema` on a thread of its own, at most once a minute and again once this process
  has made or dropped an index or a constraint. A read never waits for it: until the first is done,
  and while another is under way, statements are written with what was read last.
- Oracle reads an empty string as null, so a stamp of `''` is no stamp. A comparison with `""` in the
  query DSL is written to find a property stored empty all the same; SQL of yours that compares
  `JSON_VALUE(…)` with `''` finds nothing.
- `matches` takes a regular expression in Oracle's POSIX dialect, matched against the whole value. A
  leading `(?i)` ignores case; what Java adds, such as `(?:…)`, a look-around or `\b`, is not read. A
  backreference names the group the expression numbers it by, `\1` to `\8`; `\9` is refused, as the
  group that anchors the expression would make it the tenth, which POSIX does not have.
- A vector index (`indexes.ensure(VectorIndexSpec(...))`, or `@VectorIndex` through schema management)
  is a table of embeddings beside the nodes, kept in step by a trigger, with an Oracle vector index
  over it by neighbour partitions. The table holds the embeddings of the index's size; a node written
  with another size is left out, not refused. `loadNearest` goes through it and is approximate, as on
  the graph engines. With no index made, or one of another size than the vector's, it measures every
  node of the label whose embedding is of the vector's size, and is exact.
- A full-text index is a table of the indexed text beside the nodes, with an Oracle Text index over
  it. `loadMatching` reads words, a `"phrase"`, `+must`, `-not` and a `prefix*`. A backslash before
  punctuation or a space makes that character text: `prefix\*` is the word and `\-not` is not taken
  away. A backslash before a letter or a digit is a gap between two words, where Lucene drops it:
  `foo\bar` is the phrase `foo bar` here and the word `foobar` on Neo4j. The index holds what has
  been committed, so a search does not see its own transaction's writes. Oracle Text is in the
  regular Free image and not the slim one: without it, or with no index made, a search counts the
  words in the text of every node of the label, and does see them. An `analyzer` is recorded with
  the index and not applied.
- A property over 4,000 characters is stored and loaded whole, and compares as absent in a predicate.
- The deprecated `GraphObjectManager.save` and `saveAll` throw `UnsupportedOperationException` on Oracle;
  that manager's loads, queries and deletes work. Cypher statements written by hand do not run, and
  neither do the migration tools `RelationshipDirectionRepair` and `PathRelationshipReport`, which are
  Cypher: they repair what saves before 0.1.0 wrote, and no graph on Oracle is that old.

**What a read looks like.** A load is one SQL/PGQ statement, written to be read beside the Cypher it
stands for. A row is called what the model calls it, with an underscore so that a field may be called
`user` or `order`, and the node of a `MATCH` has its row's name. `IS node` and `IS edge` stand where
Cypher has a label and a type, since both are data: a label is a test of `labels`, a type of `type`.
`doc` is the node as the map a load returns. Where Cypher loads a view with

```cypher
MATCH (claim:Claim) WHERE claim.id = $id
RETURN { claim: claim {.*}, people: [(claim)-[:MENTIONS]->(people:Human) | people {.*}] } AS result
```

Oracle is sent

```sql
SELECT JSON_OBJECT(
    'claim' VALUE claim_.doc,
    'people' VALUE COALESCE((
        SELECT JSON_ARRAYAGG(people_.doc ORDER BY people_.eid RETURNING JSON)
        FROM GRAPH_TABLE (drivine
            MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(people_.labels), ']'), '[') || ',"', ',"Human","') > 0)
            COLUMNS (claim_.nid AS src, e.eid, people_.doc)) people_
        WHERE people_.src = claim_.nid), JSON('[]'))
    RETURNING JSON) AS result
FROM GRAPH_TABLE (drivine
    MATCH (claim_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(claim_.labels), ']'), '[') || ',"', ',"Claim","') > 0)
    COLUMNS (claim_.nid, claim_.props, claim_.doc)) claim_
WHERE JSON_VALUE(claim_.props, '$.id') = $id
```

A relationship field is a query of its own, under the field it fills: Oracle does not let a
`GRAPH_TABLE` name the row it is nested in, so each returns the node it starts from as `src` and is
joined to that row outside. `COLUMNS` names only what the statement reads of a row, and every column
of the root once a `whereClause` written by hand is in it. A second row for
one field is numbered (`people2_`), as when a condition on `people` is tested beside the field itself.
A label is tested with `JSON_EXISTS(… '$[*]?(@ == "Claim")')` where every node of the label is read,
which the index over labels answers, and with `INSTR` where the node is found by its id or at the end
of a relationship: the label, escaped as `JSON_SERIALIZE` escapes it, is looked for as one whole label
among the labels as `JSON_SERIALIZE` writes them, its brackets made commas. Where a range index on the label answers the `where`, the property is read as that
index reads it and the label is not tested on its own:

```sql
SELECT n_.doc AS result
FROM GRAPH_TABLE (drivine
    MATCH (n_ IS node)
    COLUMNS (n_.labels, n_.props, n_.doc)) n_
WHERE CASE WHEN JSON_EXISTS(n_.labels, '$[*]?(@ == "Issue")') THEN JSON_VALUE(n_.props, '$.repoKey') END = $_p0
  AND JSON_VALUE(n_.props, '$.state') = $_p1
```

A `@GraphPath` field, and a list read to a depth, is the path's own rows grouped by the node reached,
so each node is there once however many ways lead to it:

```sql
'employers' VALUE COALESCE((
    SELECT JSON_ARRAYAGG(employers_.doc ORDER BY employers_.nid RETURNING JSON)
    FROM (
        SELECT reached_.src, reached_.nid, ANY_VALUE(reached_.doc) AS doc
        FROM GRAPH_TABLE (drivine
            MATCH (claim_ IS node)
                -[e1 IS edge WHERE e1.type = 'MENTIONS']-> (m1 IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(m1.labels), ']'), '[') || ',"', ',"Human","') > 0)
                -[e2 IS edge WHERE e2.type = 'WORKS_AT']-> (reached_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(reached_.labels), ']'), '[') || ',"', ',"Company","') > 0)
            COLUMNS (claim_.nid AS src, reached_.nid, reached_.doc)) reached_
        GROUP BY reached_.src, reached_.nid
    ) employers_
    WHERE employers_.src = claim_.nid), JSON('[]'))
```

**Testing.** `OracleTestContainer` starts Oracle Free (`gvenzl/oracle-free:23-faststart`; another with
`ORACLE_TEST_IMAGE` or `-Dtest.oracle.image`) and gives a user, `drivine`, in the service `FREEPDB1`.
`@EnableDrivineTestConfig` uses it for a datasource of type `ORACLE`, or a local instance with
`USE_LOCAL_ORACLE=true` (see [TEST_CONTAINERS.md](../TEST_CONTAINERS.md)). Add the JDBC driver to the test
classpath. Without Spring:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
    OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
)
```

A test written for the graph engines usually seeds its graph and reads it back in Cypher, in
statements of no language, which Oracle takes for SQL. `SqlCypherFixtures` sends such a statement as
Cypher, which is compiled as any `CypherStatement` is, so the test's fixtures stay as they are (it was
`OracleCypherFixtures`, which still works, before Postgres kept a graph the same way):

```kotlin
val fixtures = SqlCypherFixtures.over(persistenceManager)   // Cypher fixtures here, everything else to the store
fixtures.execute(QuerySpecification.withStatement("CREATE (:Person {id: 'ada'})-[:KNOWS]->(:Person {id: 'bob'})"))

SqlCypherFixtures.run(persistenceManager, "MATCH (p:Person) RETURN p.id ORDER BY p.id")   // ["ada", "bob"]
SqlCypherFixtures.clear(persistenceManager)                                               // every node and relationship
```

- A statement is taken for Cypher when it opens with `MATCH (`, `OPTIONAL MATCH (`, `CREATE (`,
  `MERGE (` (or one of them naming a path, as `MATCH p = (`), `UNWIND`, `RETURN` or a `//` comment; any
  other, one that opens with `WITH` included, goes to the store as SQL.
- The manager must name its graph (`graphName`), as one over a `JdbcConnectionProvider` does.
- `TestCleanup.beforeEach(persistenceManager, "my-test")` deletes the nodes whose `createdBy` is the tag,
  in SQL on Oracle, as it does in Cypher elsewhere.
- `SqlCypherFixtures.forStore(persistenceManager)` is the manager itself on a graph engine and the
  fixtures over it on Oracle, Postgres or SQLite, so one test, or one `PersistenceManager` bean of a
  test's Spring context, serves every store. Drivine's sample app and its own Spring tests use it so.

Drivine's own Oracle tests are left out of a plain `./gradlew test`: the image is large and the
database wants two gigabytes. Run them with

```bash
ORACLE_TESTS=true ./gradlew :test --tests '*Oracle*'
```

Without it, each test task prints `Oracle tests skipped: set ORACLE_TESTS=true to run them`. CI runs
the same command, in a job of its own (`test (Oracle)`).

### Postgres

Postgres 14 or later (tested on 17), over JDBC: plain SQL, with no extension beyond pgvector, which
vector search uses where the server has it. Drivine keeps a graph in the same tables as on Oracle, so
a node carries any number of labels (a label is data, not a table), and models, the query DSL and
`StatelessGraphObjectManager` are the same as on the graph engines. Add the driver yourself:
`org.postgresql:postgresql`.

```yaml
database:
  datasources:
    graph:
      type: POSTGRES
      host: localhost
      port: 5432
      database-name: app          # the database to connect to
      user-name: app
      password: secret
      default-graph-path: drivine # names the graph's tables; this is the default
```

`user-name` and `database-name` are required, and `port` is 5432 unless given. A `cypher-dialect` or a
`protocol` means nothing here and is ignored with a warning. The graph's name names tables, so it is
letters, digits and underscores, begins with a letter, and is at most 49 characters; Postgres folds
it to lower case, so `Contracts` and `contracts` are one graph. The pool is as on Oracle (HikariCP,
`pool-max`, `connection-timeout`, `idle-timeout`), and sends a string without a type, so a statement
casts it as it needs (`stringtype=unspecified`). To use a `DataSource` the application already has:
`registry.register(JdbcConnectionProvider("graph", DatabaseType.POSTGRES, dataSource, graphName = "drivine"))`.
Drivine does not configure a `DataSource` it is given: set `stringtype=unspecified` on it yourself, as
statements written by hand and the test kit's fixtures bind JSON text where a column is `jsonb`. For a
pool of Drivine's own without the YAML:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.POSTGRES, JdbcConnectionProvider.postgresUrl("localhost", 5432, "app"), "app", "secret",
    graphName = "drivine",
)
```

**How a graph is stored.** The tables Oracle keeps, made, where they are not there yet, before the
datasource's first connection:
`<graph>_node (nid, labels, props, stamp)` and `<graph>_edge (eid, src, dst, type, props)`, with
`labels` (a JSON array) and `props` as `jsonb`, and `<graph>_schema` for what schema management made.
A GIN index over `labels` answers whether a node carries a label. Beside them are three functions,
`<graph>_write`, `<graph>_find` and `<graph>_owns`, which a save is carried out by. A store that an
earlier version of Drivine made is brought up to date on first use, and keeps its rows. A node's stamp
is the `stamp` column, returned by a load as `__drivine.stamp`. Deleting a node's row deletes its
relationships with it.

**Notes:**
- A statement written by hand is SQL. Parameters are named `$name`; a collection is bound as JSON, for
  `CAST($list AS jsonb)` to read. A `json` or `jsonb` column comes back as a map, so
  `SELECT jsonb_build_object(…)` hydrates with `.transform(MyView::class.java)`. A function body in
  dollar quotes, `$fn$ … $fn$`, is left as it is.
- A `CypherStatement` is compiled to SQL over the graph's tables, as on Oracle: a read is a query,
  and a write a function made for the statement in `pg_temp`, applied whole or not at all. See
  [Cypher on a relational database](05-cypher-on-a-relational-database.md#cypher-on-a-relational-database).
- Temporal values are their ISO 8601 text, as on Oracle.
- Compiled Cypher works float arithmetic, `sum()` and `avg()` out in exact decimal (`numeric`), not as doubles,
  so it is more accurate: `0.1 + 0.2` is `0.3`, where Neo4j gives `0.30000000000000004`. A result differs
  from a graph engine's only around its 16th significant digit, and still comes back as a float. It shows only
  where a result is compared for exact equality with one worked out in doubles, which is fragile with floats
  anyway. A float's text carries no trailing zeros: `toString(4.0 / 2)` is `"2.0"`.
- A statement that writes and returns rows is one with `RETURNING`, or a data-modifying `WITH`: one
  statement, which writes nothing when it fails.
- A raw `whereClause` is a SQL predicate. A property of the root may be written as Cypher writes it
  (`issue.state = 'open'`); anything more is SQL over `issue.props`, `issue.labels` and `issue.stamp`
  (`issue.props ->> 'state'`). Postgres's `?` operators cannot be used: JDBC takes a `?` for a parameter.
- A save is a plan of steps, the same plan Oracle's block carries out, which `<graph>_write` carries
  out in one statement: applied whole or not at all, and stamped and checked as on the graph engines.
  A node is found by its id and its labels, then locked by its key and looked at again. Another writer
  that changes a node in SQL should give it a new stamp, as on Oracle.
- A save the database turns away for a serialization failure (`40001`) or a deadlock (`40P01`) is run
  again, as on Neo4j, Memgraph and Oracle.
- `saveAll` saves fragments 500 to a statement, and each view by a statement of its own, in one
  transaction; `patchAll` writes 500 nodes to a statement.
- A delete is one statement, and can cascade with `DELETE_ALL` and `DELETE_ORPHAN`.
- A number is compared and ordered as a number only where one is stored: the text `'7'` is not `7`,
  as on the graph engines. Postgres keeps an empty string apart from null, so `""` is a value.
- Text is ordered by the database's collation, so a page of mixed-case text may not be in the order
  Neo4j gives (`a, b, B` rather than `B, a, b`). The range indexes are made in that collation, so an
  order and a seek are read through them.
- Compiled Cypher compares and sorts text by its code points, as Cypher does (`B, a, b`), not by the
  database's collation: in the `C` collation, whose byte order is code point order in a UTF-8 database.
  In a list the statement makes, such as `ORDER BY [n.last, n.first]`, a text is ordered by its code
  points over its first 500 characters, and past them by the database's collation. A list read from
  a property, and a map, still order the texts in them by the database's collation (`ORDER BY n.tags`).
- Index the id of each node type you save, `constraints.ensure(UniquenessConstraintSpec("Person", "id"))`,
  or with a range index: without one a save reads the label's nodes to find the one it writes.
- `matches` takes a regular expression in Postgres's dialect, matched against the whole value. A
  leading `(?i)` ignores case.
- A vector index is a partial HNSW index of pgvector over the embeddings of the label's nodes that
  are of its size; a node written with another size is left out, not refused. `loadNearest` goes
  through it and is approximate; with no index made, it measures every node of the label whose
  embedding is of the vector's size, and is exact. pgvector is made with the graph where the server
  has it and the user may: without it, everything else works.
- `loadMatching` reads the full-text syntax Oracle reads, and counts each term in the text as Oracle
  does without Oracle Text, so the two score alike. A full-text index is a GIN index of `pg_trgm`
  trigrams over the text, which narrows a search to the nodes that hold a term's words; the same
  counting then scores them, and a search sees its own transaction's writes. An `analyzer` is
  recorded with the index and not applied.
- The deprecated `GraphObjectManager.save` and `saveAll`, and the Cypher migration tools, do not run
  on Postgres, as on Oracle; that manager's loads, queries and deletes work.

**What a read looks like.** A load is one statement, written to be read beside the Cypher it stands
for. A row is called what the model calls it, with an underscore so that a field may be called `user`
or `order`. A relationship field is a subquery over the nodes reached from the root's row: a path is
joins over the edge table, a step to a depth is a recursive query anchored at the node it starts from,
and each label is tested on its own, so that a partial index over the nodes of one is used. Where
Cypher loads a view with

```cypher
MATCH (claim:Claim) WHERE claim.id = $id
RETURN { claim: claim {.*},
         companies: [(claim)-[:MENTIONS]->(companies:Company) | companies {.*}],
         people: [(claim)-[:MENTIONS]->(people:Human) | people {.*}] } AS result
```

Postgres is sent

```sql
SELECT jsonb_build_object(
    'claim', (claim_.props || jsonb_build_object('labels', claim_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', claim_.stamp))),
    'companies', COALESCE((
        SELECT jsonb_agg((companies_.props || jsonb_build_object('labels', companies_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', companies_.stamp))) ORDER BY companies_.eid)
        FROM (
            SELECT e.src AS src, e.eid, e.props AS eprops, companies_.nid, companies_.labels, companies_.props, companies_.stamp
            FROM drivine_edge e
                JOIN drivine_node companies_ ON companies_.nid = e.dst
            WHERE e.src = claim_.nid
              AND e.type = 'MENTIONS'
              AND companies_.labels @> '["Company"]'
        ) companies_), '[]'::jsonb),
    'people', COALESCE((
        SELECT jsonb_agg((people_.props || jsonb_build_object('labels', people_.labels) || jsonb_strip_nulls(jsonb_build_object('__drivine.stamp', people_.stamp))) ORDER BY people_.eid)
        FROM (
            SELECT e.src AS src, e.eid, e.props AS eprops, people_.nid, people_.labels, people_.props, people_.stamp
            FROM drivine_edge e
                JOIN drivine_node people_ ON people_.nid = e.dst
            WHERE e.src = claim_.nid
              AND e.type = 'MENTIONS'
              AND people_.labels @> '["Human"]'
        ) people_), '[]'::jsonb)) AS result
FROM drivine_node claim_
WHERE claim_.labels @> '["Claim"]'
  AND (claim_.props ->> 'id') = CAST($id AS text)
```

**Testing.** `PostgresTestContainer` starts Postgres with pgvector (`pgvector/pgvector:pg17`; another
with `POSTGRES_TEST_IMAGE` or `-Dtest.postgres.image`) and gives a user, `drivine`, and a database,
`drivine`. `@EnableDrivineTestConfig` uses it for a datasource of type `POSTGRES`, or a local
instance with `USE_LOCAL_POSTGRES=true` (see [TEST_CONTAINERS.md](../TEST_CONTAINERS.md)). Add the JDBC
driver to the test classpath. A test that seeds its graph in Cypher keeps its fixtures with
`SqlCypherFixtures`, as on Oracle, and `TestCleanup.beforeEach` works on Postgres too. Without Spring:

```kotlin
val provider = JdbcConnectionProvider.pooled(
    "graph", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
    PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
)
```

Drivine's own Postgres tests run with

```bash
./gradlew :test --tests '*Postgres*'
```

They run by default, in a plain `./gradlew test`. `POSTGRES_TESTS=false` skips them. CI runs them in the
main job. A job of its own (`test (Postgres)`) runs the sample app's stateless tests and the root
module's stateless Spring tests on a Postgres datasource
(`DATABASE_DATASOURCES_GRAPH_TYPE=POSTGRES`, `DATABASE_DATASOURCES_NEO_TYPE=POSTGRES`).

### SQLite

SQLite 3.45 or later, over JDBC with xerial's driver: a graph in a file, with no server to run. Drivine
keeps it in the same two tables as on Oracle and Postgres, so models, the query DSL and
`StatelessGraphObjectManager` are the same as on the other stores. Add the driver yourself:
`org.xerial:sqlite-jdbc`.

```yaml
database:
  datasources:
    graph:
      type: SQLITE
      database-name: /var/data/app.db  # the file, made if it is not there
      default-graph-path: drivine      # names the graph's tables; this is the default
```

`database-name` is required, and names a file: an in-memory database is refused, as each pooled
connection would open one of its own. A `host`, a `protocol` or a `cypher-dialect` means nothing here and is
ignored with a warning; a port, a user and a password are not read. The pool is HikariCP with
`pool-max`. Each connection is opened with a write-ahead log, foreign keys enforced, a wait of 30
seconds for a locked file, and the functions Drivine's statements call registered on it, so make the
provider with `JdbcConnectionProvider.sqlite`, not over a `DataSource` of your own:

```kotlin
val provider = JdbcConnectionProvider.sqlite("graph", "/var/data/app.db", graphName = "drivine")
```

**How a graph is stored.** `<graph>_node (nid, labels, props, stamp)` and
`<graph>_edge (eid, src, dst, type, props)`, made, where they are not there yet, before the datasource's
first connection, with `labels` and `props` as JSON text (not SQLite's binary
`jsonb`, so Drivine for TypeScript and for Python read the same file), and `<graph>_schema` for what
schema management made. A save is the plan of steps the other stores carry out, carried out by the
function `drivine_write` that Drivine registers on each connection: applied whole or not at all, and
stamped and checked as elsewhere. Deleting a node's row deletes its relationships with it.

**Notes:**
- A statement written by hand is SQL. SQLite has no JSON type, so JSON a statement of your own returns is
  text; one that opens with `/* drivine:json */` has each of its text values read as the JSON it is.
- A `CypherStatement` is compiled to SQL over the graph's tables, as on Oracle and Postgres (see
  [Cypher on a relational database](05-cypher-on-a-relational-database.md#cypher-on-a-relational-database)), and gives the rows a graph engine gives. A read is a query; a write is a procedure of steps, as on the other stores,
  which the function `drivine_cypher` carries out on the statement's connection under a savepoint, so it is
  applied whole or not at all. A value is JSON text: two compare as Cypher compares them (`1` equal to `1.0`,
  a map to a map of the same entries in any order) by functions Drivine registers, which also add lists and
  texts and work out temporal values with `java.time`. A query that reads the row it is in (`OPTIONAL MATCH`,
  `CALL { … }`, a variable-length walk, a shortest path) is gathered as JSON and joined with `json_each`, as
  SQLite joins no lateral query. Text compares and sorts by SQLite's `BINARY` collation; `toLower` and
  `toUpper` change the case of any letter, where SQLite's own `lower` and `upper` change ASCII alone.
- `sum()` and `avg()` add floats as doubles, as Neo4j does, so `0.1 + 0.2` sums to `0.30000000000000004` here
  too. SQLite also corrects for rounding as it adds (Kahan–Babuška summation), so a long sum of floats, or one that
  mixes an integer past 2^53 with a float, can be more accurate than Neo4j's in its last digit: `9007199254740993`
  and `0.5` sum to `9.007199254740994E15`, where Neo4j gives `9.007199254740992E15`. A result still comes back as a
  float. A `sum()` of integers that passes a long's range fails with `integer overflow`, where Neo4j gives a float.
- A raw `whereClause` is a SQL predicate, as on Postgres: `n.note = 'unverified'`, or SQL over
  `n.props`, `n.labels` and `n.stamp`. SQLite's `->>` gives a JSON value as the SQL value it is (`true`
  as 1), where Postgres gives its text.
- Temporal values are their ISO 8601 text, and a moment is compared and ordered as nanoseconds since
  the epoch, whatever offset it was written with.
- SQLite has one writer at a time, and transactions are serializable:
  - A transaction takes the file's write lock as it begins (`BEGIN IMMEDIATE`), so no write in it is
    turned away part of the way through. Another writer, and another transaction even if it only reads,
    waits for it to end: a Drivine connection for up to `busyTimeoutMillis` (30 seconds unless you set
    it), and then fails as busy. Keep transactions short: one that holds the lock for more than 5 seconds
    is logged as a warning when it ends.
  - A read outside a transaction does not wait for a writer. The file is in WAL mode: a read sees what was committed
    when it started, and nothing of a transaction still open.
  - Outside a transaction, each statement is a transaction of its own. A save or a delete the file stays
    locked against for longer than the wait, or that read before another connection wrote, is run again,
    as a contended save is on the other stores.
  - Each statement Drivine writes applies whole or not at all, in a transaction or outside one: a
    statement that fails part of the way undoes what it wrote.
  - A `DataSource` of your own is opened with none of this: `JdbcConnectionProvider.sqlite(…)` makes
    the connections, with their write lock, WAL mode and the functions Drivine's statements call.
- Index the id of each node type you save, with a uniqueness constraint or a range index: each is a
  partial index over the property for the nodes of one label.
- `matches` takes a regular expression in Java's dialect, matched against the whole value. A leading
  `(?i)` ignores case.
- A vector index and a full-text index are recorded and nothing is made for them: `loadNearest` and
  `loadMatching` measure every node of the label, and are exact.
- The test kit's Cypher fixtures (`SqlCypherFixtures`) work here as on Oracle and Postgres, and so does
  `TestCleanup.beforeEach`. SQLite's tests need no container.

### @JsonPacked Annotation

For engines that don't support list property values (Neptune), annotate collection fields to transparently serialize as JSON strings:

```kotlin
@RelationshipFragment
data class WorkHistory(
    val role: String,
    @JsonPacked val tags: List<String>? = null,
    val target: Organization
)
```

On write: `["backend", "senior"]` is stored as the string `'["backend","senior"]'`. On read: the JSON string is deserialized back to `List<String>`. Works across all engines.

### @GraphProperty Annotation

Overrides the **on-disk property name** for a fragment field, decoupling the stored graph property from
the Kotlin/Java field name:

```kotlin
@NodeFragment(labels = ["Chunk"])
data class ChunkNode(
    @NodeId val id: String,
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
)
```

The field is `containerSectionId` everywhere in your code and the DSL (`query.containerSectionId`), but
it's stored/matched as `container_section_id` in the graph. The mapping is applied on save, load, the
generated query DSL, and index creation. It also feeds model-aware key resolution — `field("containerSectionId")`
and `field("container_section_id")` both resolve to the on-disk name.

### @PropertyBag Annotation

Maps an open `Map<String, *>` field to a set of **flat, prefixed node properties** — round-tripped on
save and load. Unlike `@JsonPacked` (one opaque JSON string), each entry becomes a real property, so
it's visible, filterable, and indexable. Mirrors Spring Data Neo4j's `@CompositeProperty` (available
as an alias).

```kotlin
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId val id: String,
    val text: String,
    @PropertyBag val metadata: Map<String, Any?> = emptyMap(),   // -> metadata.<key> properties
)
```

`metadata = {"source": "wiki", "score": 3}` persists as `metadata.source = "wiki"`,
`metadata.score = 3` alongside `id`/`text`. Use `prefix` to decouple the graph namespace from the
field name and `delimiter` to change the separator; a fragment may carry several bags.

- **Values** must be storable Neo4j primitives or homogeneous arrays (String, Number, Boolean,
  temporal, or arrays/lists thereof) — a nested map/object throws an `IllegalArgumentException`
  naming the key.
- **Stale keys** (removing an entry then saving) are removed under `NullPolicy.CLEAR`: the keys the
  node holds are read, and those the map no longer has are removed. The default `IGNORE` is a
  merge-patch and leaves them.
- **Read asymmetry**: `Map<String, Any?>` reads back driver-mapped types (an `Int` written returns as
  `Long`).
- **Filter by key** in the type-safe DSL — composes on the load path and inside `loadNearest` /
  `loadMatching`:

```kotlin
loadAll<PropositionView> { where { proposition.metadata.key("source") eq "wiki" } }
// -> WHERE proposition.`metadata.source` = $p
```

For a **runtime** key (not known at compile time), use the dynamic
[`property(path)` / `field(key)`](06-object-manager.md#dynamic--runtime-key-predicates) predicates instead of `.key(...)`.

Works across Neo4j, FalkorDB, and Memgraph.

**A flat bag** has no prefix: each entry is stored under its bare key, and the bag reads back every
property that no declared field and no other bag accounts for. It fits a node whose properties are
open-ended and were never namespaced. A fragment may have one, and an entry whose key is a declared
field's property, or a declared field's name, is rejected at save: a load hands those to their fields,
the `@NodeStamp` field among them.

```kotlin
@NodeFragment(labels = ["Thing"])
data class ThingNode(
    @NodeId val id: String,
    val name: String,
    @PropertyBag(flat = true) val properties: Map<String, Any?> = emptyMap(),   // -> <key> properties
)
```

### @NodeLabels Annotation

Maps a set-valued field to the node's **labels**, beyond the fixed ones the fragment declares. The
field is filled from the node's labels on load and written as real labels on save, so
`MATCH (n:Admin)` finds a node whose field holds `Admin`.

```kotlin
enum class Role { Admin, Reviewer, Author }

@NodeFragment(labels = ["Person"])
data class PersonNode(
    @NodeId val id: String,
    @NodeLabels val roles: Set<Role>,       // a closed set: only these labels are the field's
)

@NodeFragment(labels = ["Thing"])
data class ThingNode(
    @NodeId val id: String,
    @NodeLabels val labels: Set<String>,    // an open set: any label
)
```

Saving follows `NullPolicy`, as every field does:

| | `IGNORE` (default) | `CLEAR` |
|---|---|---|
| Labels in the field | added | added |
| Labels that are the field's and not in it | left | removed |

Which labels are the field's depends on its element type:

- **An enum**: its members. It reads only those, and a label outside the enum is ignored, never an
  error. An enum may not name one of the fragment's own labels.
- **`String`**: the labels it has itself written, which it records on the node in the list property
  `__drivine.labels.<fieldName>`. Because the record is on the node, a `CLEAR` save removes the same
  labels whichever process loaded the object, or if none did. It reads every label the node carries,
  so an object loaded and then saved under `CLEAR` takes on all the labels it read.

A label that is not the field's — the fragment's own, another fragment's, one written by other code —
is never removed. Names beginning `__drivine.` are reserved: a field or bag may not use them.

A fragment may carry one `@NodeLabels` field. `saveAll` saves such a fragment one statement per
object. On a relationship target in a `@GraphView`, a `String` field adds and does not remove.

### @CaseInsensitive: lookups that ignore case

`eqIgnoreCase` and `containsIgnoreCase` lowercase the property in the statement (`toLower(n.email) = $p`), and an engine cannot read a property wrapped in a function from an index: a lookup by email alone reads every node of the label. Declare the field `@CaseInsensitive`, and an index that ignores case over it, and the lookup is read from the index:

```kotlin
@NodeFragment(labels = ["Account"])
data class Account(
    @NodeId val id: String,
    @CaseInsensitive @Unique(ignoreCase = true) val email: String,
)

accounts.loadAll(Account::class.java, AccountQueryDsl.INSTANCE) { where { query.email eqIgnoreCase "Ann@Example.com" } }
```

- **The copy.** Every save of the node also writes a lowercased copy of the property, under `__drivine.lower.<property>` (the property's stored name, so a `@GraphProperty` name is the one copied), beside the stamp. The database lowercases it, from the property as the save left it: `SET n.`__drivine.lower.email` = toLower(n.email)` on the graph engines, `LOWER` on Oracle, `lower` on Postgres, and on SQLite Drivine's own `drivine_lower`, which lowercases as Unicode does where SQLite's `lower` lowers only ASCII. A property with no value has no copy, and on Oracle neither has an empty text. `save`, `saveAll` (with `only` or `except` too), `create`, `patch`, `patchAll` and the deprecated `GraphObjectManager` all keep it in step. The copy is no part of whether a save changed the node: a save that leaves the property as it was gives no new stamp, and the Neo4j check of `update` and `save(edited, from)` does not compare it.
- **Hidden.** A load does not read the copy, and a flat `@PropertyBag` never holds it: names beginning `__drivine.` are Drivine's.
- **The index.** `@RangeIndex(ignoreCase = true)`, `@Unique(ignoreCase = true)` and Neo4j's `@TextIndex(ignoreCase = true)`, or `RangeIndexSpec(…, ignoreCase = true)`, `UniquenessConstraintSpec(…, ignoreCase = true)` and `TextIndexSpec(…, ignoreCase = true)`, are made over the copy and named `<Label>_<property>_ci_range` and so on, apart from an item over the property itself. A uniqueness constraint that ignores case refuses `A@x.com` beside `a@x.com`. The annotations are refused on a field that is not `@CaseInsensitive`, since no save would keep its copy. A spec of your own that ignores case should be over a `@CaseInsensitive` field for the same reason.
- **Filling the copy in.** A node saved before the field was `@CaseInsensitive` has no copy. `ensure` of an item that ignores case first fills the copy in on every node of the label, ten thousand at a time, then records that it has: on the store's `DrivineStore` node on the graph engines (the list `lowered`, each entry `["<Label>","<property>"]`), and as a row of kind `LOWER_COPY` in `<graph>_schema` on Oracle, Postgres and SQLite. A recorded copy is not filled in again by an `ensure` that finds the item there; `recreate`, or an `ensure` that makes the item anew, fills it in again.
- **The lookup.** Once the copy of a property is recorded for a label of the fragment, `eqIgnoreCase` and `containsIgnoreCase` on it compare with the copy, the value lowercased by the database too: `n.`__drivine.lower.email` = toLower($p)` (`= LOWER(…)` on Oracle, `= lower(…)` on Postgres, `= drivine_lower(…)` on SQLite). Until then they lowercase the property itself, as for any other field, and find the nodes saved before there were copies. A process learns of a copy another process recorded within a minute. This is a load's, a count's, a `deleteAll`'s, a grouped read's and a patch's condition on the root; a condition on a related node and the filter of a vector or full-text search lowercase the property itself, as does an empty value.
- **Which index serves what.** On Neo4j a RANGE index over the copy serves equality and a prefix, and a TEXT index `CONTAINS`. On Memgraph and FalkorDB the index serves equality. On Oracle and Postgres the copy is in the range index over the label's nodes, as any property is, and on SQLite in a partial index of its own, as any property's range index is; an equality is read from it.
- **FalkorDB makes no uniqueness constraint over the copy.** `GRAPH.CONSTRAINT` takes a property only by a plain name, and refuses one with a dot in it ([FalkorDB/FalkorDB#551](https://github.com/FalkorDB/FalkorDB/issues/551)). `ensure` of a constraint that ignores case throws a `DrivineException` there, before anything is filled in or made. A range index over the copy is made, and serves the lookup.
- **Writes that bypass Drivine** (Cypher or SQL of your own) do not keep the copy in step: a node so written is missed by a lookup once the copy is recorded. So is one saved by a process whose fragment is not yet `@CaseInsensitive`, as while an upgrade rolls out. Write the copy as Drivine does, `SET ${LowerCopies.setClause("n", "email")}` in a `SET` of its own after the one that writes the property, or `recreate` the index to fill every copy in again.
- **A field that is no longer `@CaseInsensitive`** keeps its copies and its record. Nothing reads them: a lookup on the field lowercases the property itself again, and the copies are left as they were, harmless, and out of step once the property changes. Drop the index that ignored case, which the annotations no longer allow. Should the field be made `@CaseInsensitive` again, `ensure` its index before a lookup runs: the index is made anew, so the copies are filled in again. The record is still there, so a lookup reads the copies at once, stale until then.

### Relationships of a Runtime Type

A `@GraphView` declares its relationships, type included. When the type is data — a graph whose
relationship types are not known when the model is written — use `graphObjectManager.edges`:

```kotlin
val lyre = nodeRef<ThingNode>("lyre")
val ada = nodeRef<PersonNode>("ada", "Author")       // must also carry the label Author

graphObjectManager.edges.relate(lyre, ada, type = "OWNED_BY", properties = mapOf("since" to 1990))
graphObjectManager.edges.relate(lyre, ada, type = "PLAYED_BY", mode = RelateMode.CREATE)

val owners: List<PersonNode> = graphObjectManager.edges.loadRelated(lyre, "OWNED_BY", Direction.OUTGOING)

graphObjectManager.edges.unrelate(lyre, ada, "PLAYED_BY")     // every PLAYED_BY from lyre to ada
graphObjectManager.edges.unrelateAll(lyre, "OWNED_BY")        // every OWNED_BY that leaves lyre
```

- A `NodeRef` names a stored node by fragment class and id. Both ends are matched, never created:
  if either is absent, or lacks a label the reference names, nothing is written and `relate`
  returns false. Neither node is loaded and neither's properties are touched.
- `RelateMode.MERGE` (the default) keeps at most one relationship of the type between the two nodes
  in that direction and sets its properties; `CREATE` makes another each time.
- `loadRelated` returns each related node once, as the target fragment.
- `unrelate` and `unrelateAll` remove relationships and return how many. No node is deleted.
- `relate`, `unrelate` and `unrelateAll` give the nodes at both ends a new relationship token in
  their stamp when they change something, so a `Replace` from an object loaded before is refused. A
  `relate` that merges takes both nodes' write locks before it looks for the relationship, so two
  of them on the same node run one after the other, or one is turned away by the engine and run again.
  A `relate` that finds the relationship there as it is, and an `unrelate` that finds none, mark neither.

### Cypher Dialect

Each engine uses a Cypher dialect that controls query generation. The dialect is auto-detected from the database type but can be overridden:

```yaml
      cypher-dialect: NEO4J_5    # NEO4J_5, NEO4J_4, FALKORDB, NEPTUNE, MEMGRAPH
```

