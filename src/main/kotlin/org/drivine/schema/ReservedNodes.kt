package org.drivine.schema

/**
 * The statements that keep Drivine's schema-version marker: one node of the graph for each owner
 * scope, holding the version last applied, the inventory of what was applied, and when. Written in
 * the store's own language by its [SchemaGrammar]; the graph engines share the Cypher of
 * [CypherVersionMarker]. Every statement binds the scope as `$scope`.
 */
interface VersionMarkerStatements {

    /** Reads [property] of the newest marker of the scope, as text; no row when there is none. */
    fun read(property: String): String

    /** Writes the marker of the scope, making it if it is not there. Its parameters are those of [mergeBindings]. */
    val merge: String

    /** The values [merge] binds for [version] and the [items] of the inventory, as [storedItems] writes them. */
    fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        mapOf("scope" to scope, "version" to version, "items" to storedItems(items))

    /** Removes every marker of the scope but the newest. */
    val deleteAllButNewest: String

    /** Removes the marker of the scope. */
    val delete: String

    /** The inventory as the store keeps it; [items] reads it back. A store that cannot keep an empty string marks it. */
    fun storedItems(items: String): String = items

    /** The inventory as [read] gives it back, undoing [storedItems]. */
    fun items(stored: String): String = stored
}

/** The marker's statements in Cypher, for the graph engines. */
class CypherVersionMarker(label: String, scopeProperty: String) : VersionMarkerStatements {
    private val marker = "MATCH (m:`$label` {$scopeProperty: \$scope})"

    override fun read(property: String): String = "$marker RETURN m.$property ORDER BY m.appliedAt DESC LIMIT 1"

    override val merge: String = "MERGE (m:`$label` {$scopeProperty: \$scope}) SET m.version = \$version, m.items = \$items, m.appliedAt = timestamp()"

    override val deleteAllButNewest: String = "$marker WITH m ORDER BY m.appliedAt DESC SKIP 1 DELETE m"

    override val delete: String = "$marker DELETE m"
}

/**
 * The statements that keep the store's identity: one node of the graph, stamped on first sight. See
 * `StoreIdentityResolver`. Written in the store's own language by its [SchemaGrammar]; the graph
 * engines share the Cypher of [CypherStoreStamp].
 */
interface StoreStampStatements {

    /** Makes the stamp if there is none: binds `$key`, `$storeId`, `$assignedAt` and `$engine`. */
    val assignIfAbsent: String

    /** Reads every stamp as a map `{id, assignedAt}`, the oldest first, tie-broken by id, so every reader picks the same one. */
    val readStamps: String
}

/** The stamp's statements in Cypher, for the graph engines. */
class CypherStoreStamp(label: String, keyProperty: String) : StoreStampStatements {

    override val assignIfAbsent: String = """
        MERGE (s:$label { $keyProperty: ${'$'}key })
        ON CREATE SET s.storeId = ${'$'}storeId,
                      s.assignedAt = ${'$'}assignedAt,
                      s.engine = ${'$'}engine
    """.trimIndent()

    /**
     * Projected as a map, not as two columns: a multi-column row arrives as a positional list,
     * which cannot be bound to a stamp by name.
     */
    override val readStamps: String = """
        MATCH (s:$label)
        RETURN { id: s.storeId, assignedAt: s.assignedAt } AS stamp
        ORDER BY s.assignedAt, s.storeId
    """.trimIndent()
}
