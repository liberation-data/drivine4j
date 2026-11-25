## Importing a graph in bulk

`BulkImport` writes many nodes and relationships into a graph on Postgres at once. It does the same job as `saveAll`, many times faster, and its rows are the ones a save writes: the graph it leaves loads, saves and stamps as a saved one does.

It suits loading a large graph from an export, a generated dataset or another store. Use `saveAll` for the writes an application makes as it runs.

Postgres has it so far. On Oracle and SQLite, `BulkImport` refuses the provider with a `DrivineException`.

### An import

```kotlin
val provider = registry.connectionProvider("default") as JdbcConnectionProvider
val report = BulkImport(provider).run {
    nodes("Person", key = "id", rows = people, lowercased = listOf("email"))      // Sequence<Map<String, Any?>>
    nodes("Repo", key = "slug", rows = repos)
    relationships("OWNS", BulkImport.End("Person", "id"), BulkImport.End("Repo", "slug"),
        owners.map { BulkImport.Pair(it.person, it.repo, mapOf("since" to it.since)) })
}
println("${report.nodes} ${report.relationships} in ${report.seconds} s")
```

From Java, `run` takes a `Consumer<BulkImport.Writer>`, and `nodes` and `relationships` take an `Iterable` as well as a `Sequence`.

**Nodes come first.** Each call gives the nodes of one label as maps of their properties. `key` names the property the node's relationships will find it by.
- **Values:** stored as a save stores them, a date or a moment as its tagged value, a list as a list. Nulls are left out.
- **Stamp:** each node gets one of its own, so a row that holds `__drivine.stamp` refuses the import.
- **`lowercased`:** names the properties a fragment marks `@CaseInsensitive`. They get the lowercased copy that `eqIgnoreCase` reads.
- **`otherLabels`:** gives a node labels beside `label`.

The import makes nodes and doesn't merge into any. A node whose key the graph or the import already holds refuses the import, and so does a node without its key.

**Then relationships.** Each call gives the relationships of one type, from one label's nodes to another's, each as the keys of its two ends and its properties. An end can be:
- a node this import made;
- a node already in the graph, for an import that adds to an existing graph.

A key no node holds, or that two of the graph's nodes hold, refuses the import.
- **Duplicates:** each relationship is made as `CREATE` makes one, so two between the same nodes are two relationships.
- **Stamps:** a node already in the graph that gains one is stamped anew, so an object loaded from it before is seen to be stale on its next save.

### What it holds, and for how long

An import is one transaction, on a connection of its own. A refusal or any failure rolls all of it back, and the graph is left as it was.

While it runs:
- **Other writers wait** for it to commit.
- **Reads go on until the first call of `relationships`.** From then on, reads of the graph wait too: the import drops the edge table's two foreign keys and its two indexes, so that each relationship costs the same however large the graph is.
- **At the end** it makes them again, exactly as the store's layout makes them, and checks every relationship as it does. `maintenanceMegabytes` (1,024 by default) is the `maintenance_work_mem` it makes the indexes with.

Run it outside any transaction of your application's: a connection of a Spring or JTA transaction is refused.

### How fast

Graph Bench loads 44.8M relationships between 11.2M nodes at scale 10 on a 2-CPU, 4 GB Postgres container:
- **Its loader before:** 1,870 s, which wrote straight into the tables with their foreign keys and indexes in place.
- **The same load deferring them, as `BulkImport` does:** 219 to 285 s, with the rate of each type flat however large the graph grew.

Checked one at a time, the foreign keys cost most of the difference. Each check locks its node's row. Once the node table outgrows the cache, every check is a read and a write of the disk.
