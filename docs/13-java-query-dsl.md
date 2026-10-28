## Java Query DSL

Drivine4j provides a fluent, type-safe query API for Java that mirrors the Kotlin DSL capabilities.

### Basic Usage

```java
import org.drivine.query.dsl.JavaQueryBuilderKt;

List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .loadAll();
```

The pattern is:
1. `query(graphObjectManager, GraphViewClass)` - Start a query
2. `filterWith(QueryDslClass)` - Specify the generated DSL for type-safe filtering
3. Chain `where()`, `whereAny()`, `orderBy()` as needed
4. Terminate with `loadAll()`, `loadFirst()`, or `deleteAll()`

### Available Operators

**Comparison:**
```java
.where(dsl -> dsl.getIssue().getId().isEqualTo(100L))      // equals
.where(dsl -> dsl.getIssue().getId().isNotEqualTo(100L))   // not equals
.where(dsl -> dsl.getIssue().getId().isGreaterThan(100L))  // greater than
.where(dsl -> dsl.getIssue().getId().isAtLeast(100L))      // greater than or equal
.where(dsl -> dsl.getIssue().getId().isLessThan(100L))     // less than
.where(dsl -> dsl.getIssue().getId().isAtMost(100L))       // less than or equal
```

**String Operations:**
```java
.where(dsl -> dsl.getIssue().getTitle().hasSubstring("Bug"))
.where(dsl -> dsl.getIssue().getTitle().hasPrefix("Feature"))
.where(dsl -> dsl.getIssue().getTitle().hasSuffix("needed"))
```

**Null Checks:**
```java
.where(dsl -> dsl.getIssue().getBody().isAbsent())
.where(dsl -> dsl.getIssue().getBody().isPresent())
```

**Collections:**
```java
.where(dsl -> dsl.getIssue().getState().isIn(Arrays.asList("open", "reopened")))
```

### Multiple Conditions (AND)

Chain multiple `where()` calls for AND logic:

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .where(dsl -> dsl.getIssue().getLocked().isEqualTo(false))
    .where(dsl -> dsl.getIssue().getId().isAtLeast(100L))
    .loadAll();
// WHERE issue.state = 'open' AND issue.locked = false AND issue.id >= 100
```

### OR Conditions

Use `whereAny()` for OR logic:

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .whereAny(dsl -> Arrays.asList(
        dsl.getIssue().getState().isEqualTo("open"),
        dsl.getIssue().getState().isEqualTo("reopened")
    ))
    .loadAll();
// WHERE (issue.state = 'open' OR issue.state = 'reopened')
```

Combine AND and OR:

```java
// locked=false AND (state='open' OR state='reopened')
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getLocked().isEqualTo(false))
    .whereAny(dsl -> Arrays.asList(
        dsl.getIssue().getState().isEqualTo("open"),
        dsl.getIssue().getState().isEqualTo("reopened")
    ))
    .loadAll();
```

### Ordering

```java
List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .orderBy(dsl -> dsl.getIssue().getId().descending())
    .loadAll();
```

`.nullsLast()` or `.nullsFirst()` after `ascending()` / `descending()` says where the rows with no
value go, as [in Kotlin](06-object-manager.md#ordering): `.orderBy(dsl -> dsl.getIssue().getClosedAt().descending().nullsLast())`.

### Load First

Get only the first matching result:

```java
RaisedAndAssignedIssue result = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("open"))
    .orderBy(dsl -> dsl.getIssue().getId().descending())
    .loadFirst();  // Returns null if no matches
```

### Polymorphic Filtering with instanceOf

Filter by `@NodeFragment` subtype using `instanceOf()`:

```java
import org.drivine.sample.fragment.AnonymousWebUser;
import org.drivine.sample.fragment.RegisteredWebUser;

// Filter to only anonymous web users
List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
    .loadAll();

// Combine with other conditions
List<GuideUserWithPolymorphicWebUser> activeAnonymous = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .where(dsl -> dsl.getCore().getGuideProgress().isAtLeast(10))
    .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
    .loadAll();

// Use in OR conditions
List<GuideUserWithPolymorphicWebUser> allUsers = JavaQueryBuilderKt
    .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
    .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
    .whereAny(dsl -> Arrays.asList(
        dsl.getWebUser().instanceOf(AnonymousWebUser.class),
        dsl.getWebUser().instanceOf(RegisteredWebUser.class)
    ))
    .loadAll();
```

### Delete with DSL

```java
int deleted = JavaQueryBuilderKt
    .query(graphObjectManager, RaisedAndAssignedIssue.class)
    .filterWith(RaisedAndAssignedIssueQueryDsl.class)
    .where(dsl -> dsl.getIssue().getState().isEqualTo("closed"))
    .deleteAll();
```

### Grouped Reads

A [grouped read](06-object-manager.md#grouped-reads-groups) is not on the query builder: it is `getGroups()` on either manager, and its handles come from the spec. That section has an example; this is what each Kotlin line is in Java.

| Kotlin | Java |
|--------|------|
| `where { … }` | `spec.onlyIf(condition, …)` |
| `key(query.x)`, `keyOrNull(query.x)` | `spec.key(dsl.getX())`, `spec.keyOrNull(dsl.getX())` |
| `key(path<N>(Hop(…), …))` | `spec.key(spec.path(N.class, spec.hop("TYPE", Direction.INCOMING, "Label"), …))` |
| `count()`, `count { … }` | `spec.count()`, `spec.countWhere(condition, …)` |
| `sum(query.x)` | `spec.sumOfInts`, `sumOfLongs`, `sumOfFloats`, `sumOfDoubles` |
| `avg`, `min`, `max` | `spec.avg(dsl.getX())`, `spec.min(…)`, `spec.max(…)` |
| `sum(query.x) { … }` | `spec.filtered(spec.sumOfLongs(dsl.getX()), condition, …)` |
| `having { votes gte 3 }` | `spec.having(votes, ComparisonOperator.GREATER_THAN_OR_EQUAL, 3)` |
| `orderBy { votes.desc() }` | `spec.orderBy(votes.descending())`, then `.nullsFirst()` / `.nullsLast()` |
| `rows { … }` | `return spec.rowsOf(row -> …)` |
| `groups.countBy(T::x)` | `manager.getGroups().countBy(T.class, "x")` |

A Java field does not say whether it may be null, so `key` of one is not refused: a node with no value is reported when its row is read, and `keyOrNull` reads it as null.

### Java DSL Summary

The Java methods are named differently from their Kotlin counterparts (`isEqualTo` for `eq`, `ascending` for
`asc`, …). Kotlin's are context-parameter members that register themselves inside a `where`/`orderBy` block;
from Kotlin 2.3 two members sharing a name are ambiguous there, so each language gets its own. Renamed in
0.0.87 — see [0.0.87-java-dsl-names.md](../docs/0.0.87-java-dsl-names.md) for the old → new table.

The Java annotation processor, `drivine4j-codegen-java`, gives a view held by another view's field, and a
`@RelationshipFragment`, a Properties class of its own, as KSP does: `dsl.staff.employer.title()`. Inside a held
view, a relationship to a further view is a method, not a field, so a view that holds itself, or two that hold
each other, can be built: `dsl.reports.reports().person.name()`. A relationship fragment's Properties class holds
the relationship's own properties; its target is not among them.

| Operation | Example |
|-----------|---------|
| Equality | `.isEqualTo("value")`, `.isNotEqualTo("value")` |
| Comparison | `.isGreaterThan(n)`, `.isAtLeast(n)`, `.isLessThan(n)`, `.isAtMost(n)` |
| Strings | `.hasSubstring("x")`, `.hasPrefix("x")`, `.hasSuffix("x")` |
| Null | `.isAbsent()`, `.isPresent()` |
| Collections | `.isIn(Arrays.asList(...))` |
| Type filter | `.instanceOf(SubtypeClass.class)` |
| AND | Chain multiple `.where()` |
| OR | `.whereAny(dsl -> Arrays.asList(...))` |
| Order | `.orderBy(dsl -> dsl.getProp().ascending())`, `.descending()`; then `.nullsLast()` / `.nullsFirst()` |
| Keyset cursor | `.seek(dsl -> List.of(dsl.getProp().cursorAfter(v)))` |
| Grouped read | `manager.getGroups().load(T.class, dsl, spec -> …)`: see [Grouped Reads](#grouped-reads) |

