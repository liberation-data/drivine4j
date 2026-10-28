## Philosophy

### Composition Over Inheritance

A typical ORM defines a reusable object model. From this model, statements are generated to hydrate to and from the model to the database. This addresses the so-called impedance mismatch between the object model and the database. However, there are drawbacks:

* Generated queries work well for simple cases, but can get out of hand and degrade performance when it's more complex. Debugging these generated statements can be painful.
* One model for many use cases is a big ask - the original CRUD cases work well, but more complex cases mean the model gets in the way more than it helps.

These trade-offs might be acceptable for relational databases, but with graph databases this mismatch doesn't really exist.

Just as we favor composition over inheritance in software development, we prefer composition when mapping results from complex queries. A person can play many roles: sometimes we're here to help them have a great holiday, other times to manage a team, in others they're a person of interest. With Drivine, you compose views as needed:

```kotlin
@GraphView
data class HolidayingPerson(
    @Root val person: Person,
    @GraphRelationship(type = "BOOKED_HOLIDAY")
    val holidays: List<Holiday>
)
```

Behind the scenes, Drivine generates efficient Cypher:

```cypher
MATCH (person:Person {firstName: $firstName})
WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.*}] AS holidays
RETURN {
         person:   properties(person),
         holidays: holidays
       }
```

Composition lets us mix and match as needed.  


