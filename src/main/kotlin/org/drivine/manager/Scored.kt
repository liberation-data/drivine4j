package org.drivine.manager

/**
 * A graph object paired with its vector-similarity score, as returned by
 * [GraphObjectManager.loadNearest].
 *
 * The score lives here rather than on [value] so the projected `@GraphView` / `@NodeFragment`
 * stays a clean domain object. Scores are normalized to **similarity, higher = more similar**,
 * the same on every store (Neo4j, FalkorDB, Memgraph, Postgres, Oracle, SQLite) — so ordering and
 * thresholds mean the same thing regardless of engine.
 *
 * @param value the loaded graph object
 * @param score normalized similarity in `[0, 1]`, higher is closer: `(1 + cos) / 2` for a cosine
 *   index, `1 / (1 + d²)` for a Euclidean one
 */
data class Scored<out T>(
    val value: T,
    val score: Double,
)