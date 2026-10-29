## Java Interoperability

### DSL Generation Note

KSP generates the type-safe DSL in a module with Kotlin in it; a Java module gets it from the
annotation processor, `drivine4j-codegen-java`:
- Your `@GraphView` and `@NodeFragment` classes can be in Java or Kotlin
- At runtime, both Java and Kotlin classes work fully with the object manager

### Recommended Pattern

**Best Practice:** Define `@GraphView` classes in Kotlin, everything else can be Java.

```java
// Java fragments work great!
@NodeFragment(labels = {"Person"})
public class Person {
    @NodeId public UUID uuid;
    public String name;
    public String bio;
}
```

A fragment may extend a Java base class that keeps its fields private, `@NodeId private String id;` among them: they are the fragment's state, saved and loaded with its own, and the base's schema annotations, such as `@Unique`, are declared. A field the subclass declares again, `@GraphTransient` or not, hides the base's. A Kotlin base class's private properties are not stored, as Kotlin's reflection leaves them out.

```kotlin
// A GraphView in Kotlin gets its DSL from KSP; one in Java, from drivine4j-codegen-java
@GraphView
data class PersonContext(
    @Root val person: Person,  // References Java class!
    @GraphRelationship(type = "WORKS_FOR")
    val worksFor: List<Organization>
)
```

```java
// Use from Java with the fluent DSL API
List<PersonContext> results = JavaQueryBuilderKt
    .query(graphObjectManager, PersonContext.class)
    .filterWith(PersonContextQueryDsl.class)
    .where(dsl -> dsl.getPerson().getName().hasSubstring("Alice"))
    .loadAll();
```

### Summary

| Feature | Java Support | Notes |
|---------|-------------|-------|
| `@NodeFragment` | ✅ Full | Works identically in Java and Kotlin |
| `@RelationshipFragment` | ✅ Full | Works identically in Java and Kotlin |
| `@GraphView` runtime | ✅ Full | Loading, saving, polymorphism all work |
| Type-safe DSL | ✅ Full | Use `filterWith()` API from Java |
| `instanceOf()` | ✅ Full | Filter by `@NodeFragment` subtype |
| DSL generation | ✅ Full | KSP for Kotlin modules, `drivine4j-codegen-java` for Java ones |
| Generic collections | ✅ Full | Java reflection handles `List<T>`, `Set<T>` |
| Polymorphic types | ✅ Full | Works with sealed classes or `@JsonSubTypes` |

