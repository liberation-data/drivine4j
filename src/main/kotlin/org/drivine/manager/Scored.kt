package org.drivine.manager

/**
 * A graph object paired with its vector-similarity score, as returned by
 * [GraphObjectManager.loadNearest].
 *
 * The score lives here rather than on [value] so the projected `@GraphView` / `@NodeFragment`
 * stays a clean domain object. Scores are normalized to **similarity, higher = more similar**,
 * across every backend (Neo4j, FalkorDB, Memgraph) — so ordering and thresholds mean the same
 * thing regardless of engine.
 *
 * @param value the loaded graph object
 * @param score normalized similarity in engine-defined range (cosine → roughly 0.0..1.0), higher is closer
 */
data class Scored<out T>(
    val value: T,
    val score: Double,
)