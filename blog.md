# The Graph, the Query, and the Honest Truth About ORMs

*Or: How I Learned to Stop Worrying and Love the Cypher*

---
At Embabel recently I've been working on persistence. It is an important part of AI-powered applications. Linked RAG document chunks, chat-bot conversation storage, contextual memories - these have all been on the agenda.  

Graph database persistence to be exact. 

You choose a graph database (we love Neo4j) for good reasons. Maybe great reasons. The pitch is compelling, and it's not wrong:

- **Relationships are first-class citizens**, not afterthoughts bolted on through join tables. Your data *is* connected, and the database knows it.
- **Flexible schema** — add new node types, new relationships, new properties without migration scripts that read like courtroom transcripts from a trial your schema lost.
  (Bailiff: “All rise. The table will now be altered.”)
- **Performance for connected data** — traversing relationships is what the engine is optimized for. No join penalties. Constant-time hops. In a graph, following a relationship is a pointer chase. In SQL, it’s a reunion tour for a band that probably should’ve retired in the ’90s.
- **The data model matches how you think.** Draw it on a whiteboard, and you've basically written your schema.
- **Knowledge graphs and ontologies** are the backbone of many AI applications — from RAG pipelines grounding LLM responses in structured facts, to agent systems reasoning over domain models. A graph database isn't just where you store data; it's where your AI thinks.

So you spin up Neo4j, sketch your domain, and everything feels right. Then you reach for an OGM, because that's what you do. You did it with Hibernate, you did it with JPA, you'll do it again. Muscle memory.

And for a while, it works. Then it doesn't.

We've all been there. You're debugging a query the OGM generated, and it's... a lot. You didn't write it. You _couldn't_ have written it — not like this, not with this many nested `OPTIONAL MATCH` clauses and `COLLECT` calls wrapping other `COLLECT` calls. It emerged from the abstraction layer fully formed, like a butterfly — if butterflies were at first iridescent, majestic… and then faintly demonic if you looked too closely.

The specific problems are familiar to anyone who's lived with an OGM long enough:

- **Queries too complex to debug.** The generated Cypher is opaque. When something goes wrong, you're reverse-engineering the tool instead of fixing the problem.
- **Over-selecting data you don't need.** The OGM loads the entire entity graph because it can't know which parts matter for *this* use case. You asked for a name and got a family tree — the sort of aristocratic dynasty where “uncle” and “brother” are negotiable terms.
- **Client-side processing to compensate.** All that extra data has to be filtered, mapped, and reshaped in your application code. The database did too much; now your code has to clean up after it.
- **Poor performance** — often a direct consequence of over-selecting. You chose a graph database for its speed. The OGM handed it a robe and a vow of latency. Results emerge slowly, like spiritual revelations after decades of silence.

The very things that made a graph database appealing — natural modeling, fast traversals, flexible schema — get quietly neutralized by a tool that treats every query as "give me everything and let the application sort it out."

There had to be a better way. 

## The Origin Story

The first Neo4j-powered application I worked on was a bootstrapped social platform for musicians and creatives. It wasn’t a unicorn. It was something harder: a real product with real users, tight budgets, and no tolerance for architectural indulgence.

Except… I indulged.

I used an OGM.

The code was beautiful. Entities were clean. Relationships were elegant. It felt expressive and modern and correct. The object graph mirrored the domain model. Everything had its place.

And then users started signing up.

They signed up fast.

That’s when the stillness began.

Queries that once snapped to attention began lingering. Traversals expanded beyond what was asked. What should have been a pointer hop became a pilgrimage. The database — optimized for constant-time relationship hops — was now taking the scenic route through half the graph.

The abstraction that had felt so graceful in development grew heavy under load. Over-selection crept in. Entire subgraphs were materialized because the OGM couldn’t know which parts mattered for this use case.

So we rewrote.

The next version stripped the abstraction away. Carefully tuned, explicit Cypher queries replaced the OGM. Traversals became precise again. Performance returned. Users were happy.

But there was a cost.

Infrastructure concerns leaked into business logic. Transaction handling mixed with use-case code. Boilerplate multiplied. The code was fast — but less cohesive. The pendulum had swung from beautiful abstraction to raw control.

That tension — between architectural clarity and database-native performance — is where Drivine was born.

Originally introduced as a TypeScript library, Drivine took a different position. Use-case-specific, carefully tuned Cypher queries could be projected onto an object graph. The raw power of Cypher was preserved. Performance remained explicit.

At the same time, the library handled transaction management, boilerplate, and infrastructure concerns.

In this way, cohesive design patterns and the single-responsibility principle could coexist with the database’s native strengths.

The traditional ORM gives you a single object model — one entity to serve every use case — and then generates queries on your behalf. This is the canonical solution to the "impedance mismatch," that perennial tension between how your code thinks about data and how your database stores it.

Here's the thing about graph databases, though: **the impedance mismatch largely evaporates.** Your object graph *is* a graph. The shape of your data in Neo4j is already the shape of your data in memory. You can compose an entire object graph in the query itself, returning JSON. No translation layer needed. The data just... fits. The OGM was solving a problem that, for graphs, barely exists. And creating several new ones along the way.

[Drivine4j](https://github.com/embabel/drivine4j) is the Kotlin/Java port. Same philosophy. Kotlin's type system.

## JSON: The Lingua Franca of (Almost) Everything

When your queries return JSON-like structures instead of columnar result sets, two genuinely useful things happen.

First, you can hand all the mapping tedium — type conversion, null handling, the kind of work that's important but nobody's excited about at standup — to a custom Jackson ObjectMapper. You configure it once. It handles the details. You get on with the interesting parts.

Second, and this is increasingly relevant: JSON is the lingua franca of LLMs. Your graph query returns a structure that an AI model can consume directly. No transformation pipeline. No adapter layer. The data arrives ready to work with.

Now, if you *prefer* TOML, or YAML, or whatever format is having its moment this season — that's fine. Our industry has a healthy appetite for new serialization formats. We adopt them like a serial hobbyist who has finally found their thing. This is it. This defines me now. Six months later, the guitar gathers dust and we’re deep into sourdough fermentation. It's all good - JSON converts to whatever is in fashion this quarter. 

## Two Levels of Abstraction (Because the Right Number Is Rarely One?)

Drivine4j gives you two ways to talk to your database. This turns out to be the right number. Sometimes you need precision. Sometimes you need convenience. A framework that makes you choose one forever isn't being opinionated — it's being inflexible. And inflexibility is just rigidity with better marketing.

### The PersistenceManager: When You Want to Drive

The first level is the `PersistenceManager`. You write the Cypher. Drivine handles the infrastructure — transaction management, connection pooling, keeping your business logic and your plumbing in separate rooms so they can each do their job without tripping over each other.

```kotlin
@Component
class PersonRepository @Autowired constructor(
    @param:Qualifier("neoManager") private val persistenceManager: PersistenceManager,
    @param:Qualifier("listPersons") private val stmtListPersons: CypherStatement
) {

    @Transactional
    fun findPersonsByCity(city: String): List<Person> {
        return persistenceManager.query(
            QuerySpecification
                .withStatement(stmtListPersons)
                .bind(mapOf("city" to city))
                .limit(15)
                .transform(Person::class.java)
                .filter { it.isActive }
        )
    }
}
```

The query lives in an external `.cypher` file:

```cypher
MATCH (p:Person)
  WHERE p.city = $city AND p.createdBy = 'test'
RETURN properties(p)
```

You can read it. You can tune it. You can test it independently. 

Why does this matter? Because — as Max de Marzi once memorably put it — declarative query languages are *the Iraq War of computer science*. Everyone knows that *how* you write a query affects performance. We've all seen the benchmark. We've all done the rewrite. But the ORM abstraction encourages us to pretend the optimizer will sort it out. Sometimes it does. And sometimes you need to write the query yourself, and your tools should make that easy rather than awkward.

### The GraphObjectManager: Autopilot Done Right

The second level is the `GraphObjectManager`, and this is where Drivine's philosophy really comes through.

Traditional ORMs over-select. They load the whole entity because they can't predict which fields you'll actually use. GraphQL popularized a better idea: *fetch what you need.* Drivine takes this seriously. You create models for specific use cases — not one model to answer every possible question, but one model per *question you're actually asking.*

```kotlin
@NodeFragment(labels = ["Person", "Mapped"])
data class Person(
    @NodeId val uuid: UUID,
    val name: String,
    val bio: String?
)

@RelationshipFragment
data class WorkHistory(
    val startDate: LocalDate,
    val role: String,
    val target: Organization
)

@GraphView
data class PersonCareer(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR")
    val employmentHistory: List<WorkHistory>
)
```

A `PersonCareer` isn't a `Person`. It's a *view* of a person — specifically, a person in the context of where they've worked. Need a person in the context of their social connections? That's a different `@GraphView`. Different question, different shape. Same underlying node in Neo4j.

This maps to how we actually think. A person can be a holiday customer, a team member, a person of interest, an emergency contact. We all play multiple roles. In real life, nobody finds this confusing.

In software, we’ve spent decades trying to cram all those roles into a single class — usually via inheritance at first (“Customer extends Person”, “Employee extends Person”) — and then via sheer desperation (“PersonWithEverything”). Eventually it’s 400 lines long with thirty nullable fields and a constructor that looks like a cry for help.

Drivine’s approach is to just… stop doing that.

Model roles as composable fragments. Compose what you need for this use case. Let a “person” be a person, and let roles be roles.

Composition over inheritance isn’t just cleaner design — it maps better to reality.

Then you query with a type-safe DSL:

```kotlin
fun findOpenIssues(): List<RaisedAndAssignedIssue> {
    return graphObjectManager.loadAll<RaisedAndAssignedIssue> {
        where {
            issue.state eq "open"
            issue.locked eq false
        }
        orderBy {
            issue.id.desc()
        }
    }
}
```

And it composes across relationships:

```kotlin
fun findIssuesFromOrganization(orgName: String): List<RaisedAndAssignedIssue> {
    return graphObjectManager.loadAll<RaisedAndAssignedIssue> {
        where {
            raisedBy.worksFor.name eq orgName
        }
    }
}
```

That `raisedBy.worksFor.name` traversal is compile-time checked. Your IDE autocompletes it. The generated Cypher is readable. This is what happens when you design a graph persistence layer for graphs, instead of adapting a relational one and hoping for the best.

## What about Depth?

The examples above show a single level of relationships: an issue with assignees, a person with employers. But real graph models aren't flat. They nest. An issue is raised by a person, who works for an organization, which has a location, which is part of a region. The question is: how deep can you go?

The answer: as deep as you need. The relationship targets in a `@GraphView` can be:

- **A `@NodeFragment`** — the simple case. Load a related node with its properties.
- **Another `@GraphView`** — nested views. The target has its own root fragment *and* its own relationships, recursively resolved - this @GraphView can refer to other @GraphView's as well. 
- **A `@RelationshipFragment`** — when the relationship itself carries data. Edge properties plus the target node, captured in a single object, again recursively, if needed. 

Here's what three levels of nesting looks like:

```kotlin
// Level 3 — the leaf
@GraphView
data class DeeplyNestedView(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val employer: Organization?
)

// Level 2 — the middle
@GraphView
data class MiddleLevelView(
    @Root val issue: Issue,

    @GraphRelationship(type = "RAISED_BY", direction = Direction.OUTGOING)
    val raisedBy: DeeplyNestedView?
)

// Level 1 — the top
@GraphView
data class TopLevelWithNestedGraphViews(
    @Root val root: Organization,

    @GraphRelationship(type = "HAS_ISSUES", direction = Direction.OUTGOING)
    val issues: List<MiddleLevelView>
)
```

And when relationships themselves carry meaning — not just "who's connected" but "how" — that's what `@RelationshipFragment` is for:

```kotlin
@RelationshipFragment
data class WorkHistory(
    val startDate: LocalDate,   // property on the edge
    val role: String,            // property on the edge
    val target: Organization     // the target node
)

@GraphView
data class PersonCareer(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR")
    val employmentHistory: List<WorkHistory>
)
```

The employment history isn't just a list of organizations — it's a list of *relationships*, each carrying its own start date and role. The edge has data. Drivine models that directly.

### Show Me the Cypher

One promise of Drivine is that the generated Cypher should be something you'd actually want to read. Here's what the `GraphObjectManager` generates for a two-level `RaisedAndAssignedIssue` view (actual test output, not a cleaned-up example):

```cypher
MATCH (issue:Issue)
WHERE EXISTS { (issue)-[:RAISED_BY]->(_:Person:Mapped) }

WITH
    // Issue
    issue {
        body: issue.body,
        id: issue.id,
        locked: issue.locked,
        state: issue.state,
        title: issue.title,
        uuid: issue.uuid,
        labels: labels(issue)
    } AS issue,

    // assignedTo (0 or many Person)
    [(issue)-[:ASSIGNED_TO]->(assignedTo:Person:Mapped) |
        assignedTo {
            bio: assignedTo.bio,
            name: assignedTo.name,
            uuid: assignedTo.uuid,
            labels: labels(assignedTo)
        }
    ] AS assignedTo,

    // raisedBy (0 or 1 PersonContext)
    [(issue)-[:RAISED_BY]->(raisedBy:Person:Mapped) |
        raisedBy {
            person: {
                bio: raisedBy.bio,
                name: raisedBy.name,
                uuid: raisedBy.uuid
            },
            worksFor: [
                (raisedBy)-[:WORKS_FOR]->(worksFor:Organization) |
                worksFor {
                    name: worksFor.name,
                    uuid: worksFor.uuid,
                    labels: labels(worksFor)
                }
            ]
        }
    ][0] AS raisedBy

RETURN {
    issue: issue,
    assignedTo: assignedTo,
    raisedBy: raisedBy
} AS result
```

Compare that with what a typical OGM generates. This query is commented. It selects exactly the fields each fragment declares. Collections use pattern comprehensions. Single relationships use `[0]`. Nested `@GraphView` targets (like `PersonContext`) are recursively projected — `raisedBy` includes its own `person` root fragment *and* its `worksFor` relationship, all in one query.

You could paste this into the Neo4j browser, read it, debug it, tune it. That's the point.

And for three levels of nesting? The generated Cypher just keeps composing:

```cypher
MATCH (root:Organization)

WITH
    root {
        name: root.name,
        uuid: root.uuid,
        labels: labels(root)
    } AS root,

    // issues (0 or many MiddleLevelView)
    [(root)-[:HAS_ISSUES]->(issues:Issue) |
        issues {
            issue: {
                body: issues.body,
                id: issues.id,
                locked: issues.locked,
                state: issues.state,
                title: issues.title,
                uuid: issues.uuid
            },
            raisedBy: [
                (issues)-[:RAISED_BY]->(raisedBy:Person:Mapped) |
                raisedBy {
                    person: {
                        bio: raisedBy.bio,
                        name: raisedBy.name,
                        uuid: raisedBy.uuid
                    },
                    employer: [
                        (raisedBy)-[:WORKS_FOR]->(employer:Organization) |
                        employer {
                            name: employer.name,
                            uuid: employer.uuid,
                            labels: labels(employer)
                        }
                    ][0]
                }
            ][0]
        }
    ] AS issues

RETURN {
    root: root,
    issues: issues
} AS result
```

Organization → Issues → Raised By (Person + Employer). Three levels deep, one query, all readable. The structure mirrors the Kotlin model definitions almost exactly.

### Saving: Cascade Policies

Loading is half the story. When you save a modified `@GraphView`, Drivine needs to know what to do with relationships that have changed — particularly when a relationship is removed. Did you just unlink the node, or do you want it gone?

Drivine provides three cascade policies:

```kotlin
enum class CascadeType {
    NONE,           // Delete the relationship only. The safest default.
    DELETE_ORPHAN,  // Delete the target if no other relationships point to it.
    DELETE_ALL      // Delete the target unconditionally. The nuclear option.
}

// Usage
graphObjectManager.save(modifiedView, CascadeType.DELETE_ORPHAN)
```

`NONE` is the default, and it's the right default — removing an assignee from an issue shouldn't delete the person. `DELETE_ORPHAN` is the thoughtful middle ground: clean up after yourself, but only if nobody else is using it. `DELETE_ALL` is there for when you mean it.

### The Snapshot Cache: Only Write What Changed

When the `GraphObjectManager` loads an object, it takes a JSON snapshot. When you save it back, it compares the current state to the snapshot and only writes the fields that actually changed.

This is a small optimization that compounds. If you load a view with twenty fields and change one, the merge statement targets that one field. No unnecessary writes. No accidental overwrites of data that another process might have changed in the meantime.

The session tracks objects by `className:id`, so it works across nested fragments. Load an issue, modify the title, save — Drivine merges only the title. The assignees, the raiser, the organization chain — all untouched.

## Ontologies and Recursive Structures

There's one pattern that graph databases handle beautifully but most persistence layers don't touch: recursive relationships. Ontologies and hierarchies — the kind of structures that let you infer new facts from the shape of the data itself.

```cypher
(:Location)-[:HAS_LOCATION]->(:Location)-[:HAS_LOCATION]->(:Location)
```

A country contains regions. Regions contain countries. Countries contain cities. In a knowledge graph, this kind of hierarchy lets you reason upward and downward — "is this address in France?" becomes a traversal, not a lookup table.

These structures are central to AI applications. RAG pipelines use them to ground responses in structured knowledge. Agent systems use them to reason over domain models. Drivine supports them directly.

A `@GraphView` can reference its own type. Drivine expands the self-reference to a configurable depth using nested pattern comprehensions:

```kotlin
@NodeFragment(labels = ["Location"])
data class Location(
    @NodeId val uuid: UUID,
    val name: String,
    val type: String
)

@GraphView
data class LocationHierarchy(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.OUTGOING, maxDepth = 3)
    val subLocations: List<LocationHierarchy>  // Self-referential
)
```

`maxDepth = 3` means Drivine expands three levels deep. Load a continent and you get:

```
Europe (continent)
├── Western Europe (region)
│   ├── France (country) → subLocations: []
│   ├── Germany (country) → subLocations: []
│   └── ...
├── Northern Europe (region)
│   ├── Sweden (country) → subLocations: []
│   └── ...
└── ...
```

At the terminal depth, collections become `[]` and nullable singles become `null`. No infinite recursion. No "fetch the entire graph" surprises.

The generated Cypher nests pattern comprehensions at each level:

```cypher
MATCH (location:Location)
WITH
    location { name: location.name, type: location.type, uuid: location.uuid } AS location,
    [(location)-[:HAS_LOCATION]->(sub_d1:Location) |
        sub_d1 {
            location: { name: sub_d1.name, type: sub_d1.type, uuid: sub_d1.uuid },
            subLocations: [(sub_d1)-[:HAS_LOCATION]->(sub_d2:Location) |
                sub_d2 {
                    location: { name: sub_d2.name, ... },
                    subLocations: [(sub_d2)-[:HAS_LOCATION]->(sub_d3:Location) |
                        sub_d3 { location: { ... }, subLocations: [] }
                    ]
                }
            ]
        }
    ] AS subLocations
RETURN { location: location, subLocations: subLocations } AS result
```

Readable. Debuggable. And you didn't write it.

**Traversing upward** is just a different view with `Direction.INCOMING`:

```kotlin
@GraphView
data class LocationAncestry(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.INCOMING, maxDepth = 3)
    val parent: LocationAncestry?  // Nullable — each location has at most one parent
)
```

Load "France" and you get France → Western Europe → Europe → `null`. Same model, different direction, same compositional approach.

You can also override depth at query time:

```kotlin
graphObjectManager.loadAll<LocationHierarchy> {
    depth("subLocations", 5)  // Override the annotation's maxDepth=3
    where { query.location.type eq "continent" }
}
```

And for structures where the cycle goes through multiple types — Person → Organization → Person — Drivine tracks visit counts and terminates at `maxDepth`, so you get bounded traversal without manual bookkeeping.

## What's Next

The original TypeScript Drivine supports multiple Cypher-compatible graph databases — not just Neo4j, but any engine that speaks the language. In principle, Drivine4j could do the same. The architecture doesn't assume Neo4j internals; it generates standard Cypher.

In practice, Neo4j is miles ahead. And not all graph databases support the Cypher features Drivine leans on — pattern comprehensions, `labels()`, `EXISTS` subqueries. The further you go from Neo4j, the more you'd need to negotiate what's available. It's doable, but it's an honest engineering effort, not a checkbox.

Still, the door is open. If the Cypher ecosystem continues to converge — and there are signs that it will — multi-database support becomes a natural extension rather than a rewrite.

## Wrapping Up

Drivine4j started as a reaction to a real problem — an OGM that made a fast database slow — and grew into a framework with a genuine point of view. Let's recap what it offers.

**Two levels of abstraction**, because that's how many you actually need:

- The **PersistenceManager** lets you write your own Cypher — tuned, tested, readable — and project the results onto typed objects. Drivine handles transactions, connection pooling, and the infrastructure plumbing. You keep control of the query. This is the right choice when performance matters, when the query is complex, or when you simply want to see exactly what's hitting the database.

- The **GraphObjectManager** generates Cypher from compositional models. `@NodeFragment`, `@RelationshipFragment`, and `@GraphView` compose into use-case-specific views — not one god-entity for all scenarios, but one model per question you're asking. The generated Cypher is readable and debuggable. Dirty tracking means saves only write what changed. Cascade policies give you control over deletions.

**The type-safe DSL** deserves a special mention. Built via KSP code generation, it gives you compile-time checked queries with full IDE autocomplete — filter across nested relationships, sort collections database-side with APOC, query polymorphic types with `instanceOf<T>()`. It makes the `GraphObjectManager` feel less like a framework and more like a language feature. And if Java is your thing, there's a fluent Java API that mirrors the Kotlin DSL — `filterWith()`, `where()`, `whereAny()`, `orderBy()` — so nothing is left behind.

**Recursive relationships** let you model ontologies and hierarchies — the structures that power knowledge graphs and AI reasoning — with the same compositional approach that works for everything else.

And the generated Cypher? You can read it. You can paste it into the Neo4j browser and debug it. You can show it to someone who's never seen Drivine and they'll understand what it does. That's not a small thing. That's the whole point.

If you're working with Neo4j and you've felt the friction of the OGM — the slow queries, the over-selection, the generated Cypher you can't debug — [Drivine4j](https://github.com/embabel/drivine4j) might be worth a look. It's open source and part of the [Embabel](https://github.com/embabel) ecosystem.

Next up: I'll show how we used Drivine4j to build [embabel-chat-store](https://github.com/embabel/embabel-chat-store) — a conversation persistence layer for AI agents where the graph model really shines. Multi-party messages, explicit attribution, async persistence. Stay tuned.

