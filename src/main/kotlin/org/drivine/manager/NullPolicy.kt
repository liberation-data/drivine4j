package org.drivine.manager

/**
 * How a **null** field value is treated on save (`save` / `saveAll`) — the single, declared contract for
 * null handling, applied identically across single/batch, bagged/bagless, and every engine.
 *
 * Previously the answer was emergent from hidden axes (single vs batch, bagged vs bagless, a
 * vector-wrapping engine vs not, tracked vs detached) — a caller couldn't reason about whether a null
 * would clear a stored property. Now it's one policy, defined purely on the **object you pass**:
 *
 * - [IGNORE] — write only the non-null fields; nulls are left untouched.
 * - [CLEAR] — the object is authoritative; nulls clear the corresponding property.
 *
 * No field is special. In particular an embedding (`@VectorIndex`) is treated like any other property:
 * a null embedding is preserved under [IGNORE] and cleared under [CLEAR]. The safety against
 * accidentally wiping a computed vector comes from the **default being [IGNORE]**, not from a hidden
 * per-field exception — so `save(chunk)` on a partially-loaded object never destroys anything.
 *
 * The policy governs null semantics independently of dirty-tracking: the session snapshot only
 * optimizes away re-writes of unchanged **non-null** fields (a no-op), and never decides whether a null
 * clears. So the observable result does not depend on whether the object is tracked.
 */
enum class NullPolicy {
    /**
     * A null field is **left untouched** — a merge-patch: only non-null fields are written, existing
     * properties are never cleared. The **default** for `save` / `saveAll`. Use for partial updates
     * where an absent value means "don't touch" (e.g. re-saving a `ChunkNode` whose embedding wasn't
     * recomputed).
     */
    IGNORE,

    /**
     * A null field **clears** the stored property (`SET n.x = null` / `+= {x: null}`) — persist the
     * object exactly as given, an explicit full overwrite. Opt in when you genuinely intend to null a
     * property (including an embedding). Reach for it only with a complete object.
     */
    CLEAR,
}
