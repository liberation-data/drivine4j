# `GraphObjectManager` (deprecated)

`GraphObjectManager` is deprecated in favour of `StatelessGraphObjectManager`. It behaves as it always
did, and this page keeps its documentation. For why it was replaced and how to move, see
[Migrating from GraphObjectManager](../README.md#migrating-from-graphobjectmanager).

Loading, querying and deleting are the same on both managers and are described in the README. This
page covers what differs: saving, the cascade on save, and the session.

Three fixes in 0.1.0 apply to this manager too. A relationship field declared `INCOMING` is written
towards the root. A `@GraphPath`, `@Count` or `@Aggregate` field is never written. An `UNDIRECTED`
field does not add a relationship beside one stored towards the root. It also stamps the nodes it
changes, without checking a stamp: see
[0.1.0-stateless-object-manager.md](0.1.0-stateless-object-manager.md).

Null handling is independent of dirty tracking: the `NullPolicy` alone decides, so the result never
depends on whether the object is tracked by the session.

```kotlin
@Bean
fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager = factory.get()
```

## Saving

### Simple Save (Dirty Tracking)

GraphObjectManager tracks loaded objects and only saves changed fields:

```kotlin
// Load an object
val person = graphObjectManager.loadOrThrow<PersonCareer>(uuid)

// Modify it
val updated = person.copy(
    person = person.person.copy(bio = "Updated bio")
)

// Save - only dirty fields are written!
graphObjectManager.save(updated)
```

### Save with Relationship Changes

```kotlin
val person = graphObjectManager.loadOrThrow<PersonCareer>(uuid)

// Remove all employment history
val updated = person.copy(employmentHistory = emptyList())

graphObjectManager.save(updated, CascadeType.NONE)
```

### Batch Save (`saveAll`)

Persist a collection in **one atomic round-trip group**, with `save`'s per-item semantics unchanged
(cascade, dirty tracking, MERGE identity). Within an ambient `@Transactional` the statements join it;
otherwise they run together in a single transaction — a failure on any item rolls the whole call back.

```kotlin
val saved = graphObjectManager.saveAll(views, CascadeType.DELETE_ORPHAN)
```

Homogeneous root upserts collapse into chunked `UNWIND … MERGE … SET n += row.props` statements
(sub-linear round trips); relationship/cascade statements stay per-item. Heterogeneous collections are
grouped by runtime class; the returned list preserves input order. Roots with a `@PropertyBag` fall
back to the per-item path. Null handling follows [`NullPolicy`](../README.md#null-write-policy-nullpolicy)
uniformly with `save` — `IGNORE` (default) leaves nulls, `CLEAR` clears them —
via `saveAll(objs, nullPolicy = …)`.


What `GraphObjectManager` does not do:

- It does not check a `@NodeStamp`. It writes a new stamp on each node it changes, so a
  `StatelessGraphObjectManager` save notices its writes.
- It does not notice a node that anything else changed or deleted. Its snapshot is what it last saw,
  not what the store holds.

## Cascade on save

When saving `@GraphView` objects with modified relationships, `CascadeType` determines what happens to target nodes:

### CascadeType.NONE (Default - Safest)

Only deletes the relationship, leaves target nodes intact:

```kotlin
graphObjectManager.save(updated, CascadeType.NONE)
```

Use when: Target nodes are shared or should persist independently.

### CascadeType.DELETE_ORPHAN (Safe Deletion)

Deletes relationship and target only if no other relationships exist to the target:

```kotlin
graphObjectManager.save(updated, CascadeType.DELETE_ORPHAN)
```

The relationship list you save is authoritative: every relationship of that type to a target not in
the list is removed, whether or not the view was loaded first, and including relationships another
writer added after it was loaded.

Use when: You want to clean up orphaned nodes but preserve shared ones.

**Example:** Removing a person's employment at a solo startup deletes the startup (orphaned), but removing employment at a company with other employees keeps the company.

### CascadeType.DELETE_ALL (Destructive)

Always deletes both the relationship and target nodes:

```kotlin
graphObjectManager.save(updated, CascadeType.DELETE_ALL)
```

⚠️ **Warning:** Permanently deletes data. Use with caution.

Use when: Target nodes are exclusively owned and should be deleted with the relationship.

`CascadeType.PRESERVE` skips removals: relationships are added and none is removed.

`DELETE_ORPHAN` is not available on Memgraph.

## Session and dirty tracking

`GraphObjectManager` maintains a session that tracks loaded objects:

1. **On Load**: Takes a snapshot of the object's state
2. **On Save**: Compares current state to snapshot
3. **Optimization**: Only writes changed fields (dirty checking)

This means:
- **Loaded objects**: Optimized saves (only dirty fields)
- **New objects**: Full saves (all fields written)

The session outlives transactions, so an object loaded in one request and saved in another still
writes only what changed. It is kept small and bounded:

- **Compact snapshots**: a snapshot keeps the object's shape and ids and replaces large values (long
  strings, embeddings) with a 64-bit hash, so a tracked object costs bytes per field, not a copy of
  its data.
- **Bounded**: at most `drivine.query.session-max-entries` objects (default 100,000) per
  `GraphObjectManager`; past that the least recently used is evicted. An evicted object is untracked,
  and its next save writes all fields. `DELETE_ORPHAN` and `NullPolicy.CLEAR` do not depend on
  tracking, so an evicted object saves correctly under both.
- **Thread-safe**: one manager can be shared by concurrent requests.
- **Scoping**: call `graphObjectManager.clearSession()` to end tracking for a unit of work, such as a
  request or a job.
