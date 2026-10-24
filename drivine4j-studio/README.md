# Drivine Studio

Browse an application's `@GraphView` and `@NodeFragment` classes, load them with `loadAll`, and see
what came back as a graph. The studio reads and never writes.

This module is the endpoints, for a Spring MVC application. The browser app is
[drivine-studio](https://github.com/liberation-data/drivine-studio), which talks to them.

## Enabling it

```kotlin
@Configuration
@EnableDrivine
@EnableDrivineStudio          // looks for views and fragments in this class's package
class AppConfig
```

| Property | Default | |
| --- | --- | --- |
| `drivine.studio.enabled` | `true` | `false` turns the studio off without removing the annotation |
| `drivine.studio.base-packages` | the annotated class's package | where to look for views and fragments |
| `drivine.studio.datasource` | `drivine.default-datasource`, else `default` | the datasource it reads |
| `drivine.studio.default-limit` / `max-limit` | 50 / 500 | how many objects one query loads |

The studio has no login of its own and shows whatever the datasource holds to whoever can reach it.
Enable it in development, or put `/drivine/studio/**` behind the application's security.

## Endpoints

`GET /drivine/studio/api/catalog` lists the views and fragments.

`POST /drivine/studio/api/query` runs a `loadAll`, given as data that mirrors the query DSL:

```json
{
  "type": "org.drivine.sample.view.RaisedAndAssignedIssue",
  "where": [
    {"kind": "property", "property": "state", "op": "EQUALS", "value": "open"},
    {"kind": "related", "relationship": "assignedTo", "none": false,
     "conditions": [{"kind": "property", "property": "name", "op": "CONTAINS", "value": "Alice"}]},
    {"kind": "anyOf", "conditions": [ ... ]},
    {"kind": "not", "conditions": [ ... ]}
  ],
  "orderBy": [{"property": "id", "direction": "DESC"}],
  "limit": 50
}
```

Properties are named as the DSL names them, by their Kotlin or Java name. Every name is checked
against the catalogue and every value is converted to its property's type and bound as a parameter;
a query the catalogue does not allow is answered `400` with the reason. The answer is the nodes and
edges the loaded objects were read from, with the same node met twice appearing once.

## Trying it

```sh
./gradlew :drivine4j-studio:studioDemo      # the sample domain, seeded, on :8087 (needs Docker)
```

Then run [drivine-studio](https://github.com/liberation-data/drivine-studio) against it.

## Not yet

- Filtering on a relationship that carries a `@RelationshipFragment`, and on a related node's own relationships.
- `anyOf` and `not` in the browser app (the endpoint takes them).
- Vector and full-text search, keyset paging, `load` by id.
- Describing a query in words, and showing it as DSL source.
- Reading the catalogue from a jar the application is not running.
