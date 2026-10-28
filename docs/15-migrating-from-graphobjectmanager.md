## Migrating from GraphObjectManager

`GraphObjectManager` is deprecated in favour of `StatelessGraphObjectManager`. It still works, with the fixes and the stamping that 0.1.0 brought to both managers, and is described in [docs/legacy-graph-object-manager.md](../docs/legacy-graph-object-manager.md). Everything 0.1.0 changed is listed once, in [docs/0.1.0-stateless-object-manager.md](../docs/0.1.0-stateless-object-manager.md#what-changed).

### Why

`GraphObjectManager` keeps a snapshot of every object it loads or saves, and a save writes only what differs from the snapshot. Whether a save was correct therefore depended on things you could not see from the call: whether this manager had loaded the object, whether it was still in the session, and whether anything else had written to the node since. Each of those produced a real bug:

- A node deleted by plain Cypher and saved again came back with only some of its properties.
- A relationship dropped from a list was removed or kept depending on whether the object had been evicted from the session.
- A view the manager had not loaded wrote a path field as a direct relationship.

A library should make the graph easier to work with, not require forensics to save an object safely. A `StatelessGraphObjectManager` save depends only on the object and the arguments you pass, and with a `@NodeStamp` field it is refused when another writer got there first.

### Upgrade steps

1. Move the core library and the code generator to 0.1.0 together, and compile again. Code compiled against 0.0.x does not link: the loading and query methods of `GraphObjectManager` are now declared on `GraphObjectOperations`.
2. A build that treats warnings as errors now fails on `GraphObjectManager` and `GraphObjectManagerFactory.get()`, which are deprecated. Injecting a `GraphObjectManager` by type warns too. Until the code has moved, suppress it where it is used: `@Suppress("DEPRECATION")` in Kotlin, `@SuppressWarnings("deprecation")` in Java.
3. Deploy 0.1.0 to everything that writes to the store.
4. If any view has an `INCOMING` relationship field, a `@GraphPath` field or a list read over several hops, and was ever saved, repair what the old saves left: see [Data saved before 0.1.0](#data-saved-before-010). Do it after step 3, since a writer still on 0.0.x writes the same mistakes again.
5. Replace `factory.get()` with `factory.stateless()`, and change the saves as the table below says.
6. Add a `@NodeStamp` field to each type you load, change and save. See [Existing data](06-object-manager.md#existing-data) for nodes that have no stamp yet.

### What you give up

- **Dirty-only writes are no longer automatic.** Ask for them with `update { }`, `only` or `except`.
- **Saving with `CascadeType.DELETE_ALL` has no equivalent.** Remove the relationships with `Replace`, then delete the nodes:

  ```kotlin
  val dropped = loaded.attachments - kept
  graphObjectManager.save(loaded.copy(attachments = kept), Replace(MessageView::attachments))
  dropped.forEach { graphObjectManager.delete<Attachment>(it.id) }
  ```
- **Saves have no interface.** `save`, `saveAll`, `patch`, `patchAll` and `update` are on `StatelessGraphObjectManager` itself. `GraphObjectOperations` covers loading, querying and deleting, so code that saves depends on the class.
- **The session.** There is none, so nothing to clear, and nothing that remembers what was loaded.

### What changes in your code

Loading, querying and deleting are the same: both managers implement `GraphObjectOperations`, and the generated query DSL, whose extensions now have that receiver, works on either. Saves change as follows.

| With `GraphObjectManager` | With `StatelessGraphObjectManager` |
|---|---|
| `factory.get()` | `factory.stateless()` |
| `save(obj)` of a new object | `save(obj)` |
| load, change a field, `save` | `update(id) { it.copy(...) }`, or `save` of the loaded object |
| load, drop an item from a list, `save` | `update(id) { ... }`, or `save(obj, Replace(View::field))` |
| `save(obj, CascadeType.DELETE_ORPHAN)` | `save(obj, Replace(View::a, View::b, removedTargets = RemovedTargets.DELETE_UNREFERENCED))`, naming each relationship field |
| `save(obj, CascadeType.PRESERVE)` | `save(obj)` |
| `save(obj, CascadeType.DELETE_ALL)` | no equivalent |
| `clearSession()` | not needed |

- `DELETE_UNREFERENCED` deletes a removed target that nothing else refers to the way the field did: see [Relationships](06-object-manager.md#relationships). `DELETE_ORPHAN` deletes one with no relationship in either direction. A removed target that nothing points at, and that points at something itself, is deleted by the first through an outgoing field and kept by the second.
- A `@GraphPath`, `@Count` or `@Aggregate` field, and a list of fragments read over several hops, is read-only with either manager: see [`@ReadOnly`](06-object-manager.md#readonly-a-field-that-is-loaded-and-never-written).
- Nodes saved by either manager, and the node at each end of a relationship either manager or `edges` writes or removes, now carry the property `__drivine.stamp`, whether or not their type declares a `@NodeStamp` field. It cannot be turned off. A test that compares a node's whole property map, Cypher that copies one node's properties to another, an export, and anything of your own that reads every property of a node, such as `properties(n)` mapped onto a class that refuses unknown properties, will see it. Drivine's own mapping ignores it. Names beginning `__drivine.` are Drivine's own, should your code need to tell them from its own.

### Data saved before 0.1.0

Three things a view save before 0.1.0 wrote are wrong in the store, and stay wrong until they are repaired:

- A relationship of a field declared `Direction.INCOMING` was written from the view's root to the target, so it points the wrong way and the view does not load it back. `RelationshipDirectionRepair` reports these and turns them round.
- A `@GraphPath` field of a view the manager had not loaded was written as one direct relationship, of the first hop's type, from the root to each node the field held. `PathRelationshipReport` counts these and gives the Cypher that removes them.
- A list read over several hops (`maxDepth` above 1) was written as one direct relationship from the root to each node, however far away. `PathRelationshipReport.reportSeveralHopLists` counts what may be left of those.

The tools, what each finding means, and the order to run them in are in [docs/0.1.0-stateless-object-manager.md](../docs/0.1.0-stateless-object-manager.md#repairing-data-saved-before-010). In short: back up, run the reports over every view of the model, read them, and only then repair. The repair changes relationships in the store, by label and type; it cannot tell a relationship a save wrote from one made some other way.

### How we know nothing else changed

Every test of `GraphObjectManager` in this repository has a mirror that runs on `StatelessGraphObjectManager`, but for four that save with `CascadeType.DELETE_ALL`, which has no equivalent. A comparison test runs the same scenarios through both managers on Neo4j, FalkorDB and Memgraph and compares the graphs they leave: for flat targets, relationships with properties and nested views, with and without another writer in between, they match.

