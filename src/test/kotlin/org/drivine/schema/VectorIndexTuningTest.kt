package org.drivine.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pinning the physical shape of a vector index.
 *
 * The point of these is reproducibility across engine versions: Neo4j 2026.04 defaults to
 * `vector.quantization.enabled: true`, `hnsw.m: 16`, `ef_construction: 100`, which earlier versions did
 * not, so an unpinned declaration silently produces a different physical index per server.
 */
class VectorIndexTuningTest {

    private val neo4j = Neo4jSchemaGrammar()
    private val memgraph = MemgraphSchemaGrammar()

    private fun ddl(spec: VectorIndexSpec): String =
        (neo4j.createIndex(spec).single() as SchemaStatement.Cypher).statement

    // ----- DDL emission -----

    @Test
    fun `an unpinned spec emits only dimensions and similarity`() {
        // Emitting a Drivine-chosen default here would freeze whatever we believed the engine default
        // was, which is a different bug from the one pinning solves.
        val statement = ddl(VectorIndexSpec("Proposition", "embedding", 1536))

        assertTrue(statement.contains("`vector.dimensions`: 1536"), statement)
        assertTrue(statement.contains("`vector.similarity_function`: 'cosine'"), statement)
        assertFalse(statement.contains("quantization"), statement)
        assertFalse(statement.contains("hnsw"), statement)
    }

    @Test
    fun `pinned parameters are emitted as backtick-quoted config keys`() {
        val statement = ddl(
            VectorIndexSpec(
                "Proposition", "embedding", 1536,
                hnswM = 32,
                hnswEfConstruction = 200,
                engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false)),
            )
        )

        assertTrue(statement.contains("`vector.quantization.enabled`: false"), statement)
        assertTrue(statement.contains("`vector.hnsw.m`: 32"), statement)
        assertTrue(statement.contains("`vector.hnsw.ef_construction`: 200"), statement)
    }

    @Test
    fun `parameters can be pinned independently`() {
        val statement = ddl(VectorIndexSpec("Proposition", "embedding", 1536, hnswM = 24))

        assertTrue(statement.contains("`vector.hnsw.m`: 24"), statement)
        assertFalse(statement.contains("quantization"), statement)
        assertFalse(statement.contains("ef_construction"), statement)
    }

    @Test
    fun `pinning quantization to true is emitted, not treated as unset`() {
        // Boolean false is the interesting case for `?:`-style bugs, but true must survive too.
        assertTrue(
            ddl(VectorIndexSpec("P", "e", 8, engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true))))
                .contains("`vector.quantization.enabled`: true")
        )
    }

    // ----- Validation -----

    @Test
    fun `non-positive HNSW parameters are rejected`() {
        assertThrows<IllegalArgumentException> { VectorIndexSpec("P", "e", 8, hnswM = 0) }
        assertThrows<IllegalArgumentException> { VectorIndexSpec("P", "e", 8, hnswM = -1) }
        assertThrows<IllegalArgumentException> { VectorIndexSpec("P", "e", 8, hnswEfConstruction = 0) }
    }

    @Test
    fun `pinsPhysicalConfig reflects whether anything was pinned`() {
        assertFalse(VectorIndexSpec("P", "e", 8).pinsPhysicalConfig)
        assertTrue(VectorIndexSpec("P", "e", 8, hnswM = 16).pinsPhysicalConfig)
        assertTrue(VectorIndexSpec("P", "e", 8, engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false))).pinsPhysicalConfig)
    }

    // ----- Introspection -----

    @Test
    fun `physical parameters are read back from SHOW INDEXES`() {
        val row = mapOf(
            "name" to "Proposition_embedding_vector",
            "type" to "VECTOR",
            "entityType" to "NODE",
            "labelsOrTypes" to listOf("Proposition"),
            "properties" to listOf("embedding"),
            "options" to mapOf(
                "indexConfig" to mapOf(
                    "vector.dimensions" to 1536,
                    "vector.similarity_function" to "cosine",
                    "vector.quantization.enabled" to true,
                    "vector.hnsw.m" to 16,
                    "vector.hnsw.ef_construction" to 100,
                )
            ),
        )

        val info = neo4j.parseIndexRows(listOf(row)).single()

        assertEquals(true, info.quantizationEnabled)
        assertEquals(16, info.hnswM)
        assertEquals(100, info.hnswEfConstruction)
    }

    @Test
    fun `an engine that does not report physical parameters yields nulls, not defaults`() {
        val row = mapOf(
            "name" to "v",
            "type" to "VECTOR",
            "entityType" to "NODE",
            "labelsOrTypes" to listOf("P"),
            "properties" to listOf("e"),
            "options" to mapOf("indexConfig" to mapOf("vector.dimensions" to 8)),
        )

        val info = neo4j.parseIndexRows(listOf(row)).single()

        assertNull(info.quantizationEnabled)
        assertNull(info.hnswM)
        assertNull(info.hnswEfConstruction)
    }

    // ----- Drift -----

    private fun existing(
        quantization: Boolean? = null,
        m: Int? = null,
        ef: Int? = null,
    ) = SchemaItemInfo(
        kind = SchemaItemKind.VECTOR_INDEX,
        label = "P",
        properties = listOf("e"),
        name = "v",
        dimensions = 8,
        similarity = SimilarityFunction.COSINE,
        quantizationEnabled = quantization,
        hnswM = m,
        hnswEfConstruction = ef,
    )

    @Test
    fun `a pinned parameter that the engine contradicts is drift`() {
        val spec = VectorIndexSpec("P", "e", 8, engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false)))

        assertFalse(neo4j.matchesShape(existing(quantization = true), spec))
    }

    @Test
    fun `a pinned parameter the engine agrees with is not drift`() {
        val spec = VectorIndexSpec("P", "e", 8, hnswM = 16, engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)))

        assertTrue(neo4j.matchesShape(existing(quantization = true, m = 16), spec))
    }

    @Test
    fun `an unpinned parameter never drifts, whatever the engine chose`() {
        // Otherwise every existing index would drift the moment a server changed its defaults — the
        // failure this feature exists to prevent, inverted.
        val spec = VectorIndexSpec("P", "e", 8)

        assertTrue(neo4j.matchesShape(existing(quantization = true, m = 16, ef = 100), spec))
    }

    @Test
    fun `a pinned parameter the engine does not report is not drift`() {
        val spec = VectorIndexSpec("P", "e", 8, hnswM = 32)

        assertTrue(neo4j.matchesShape(existing(m = null), spec))
    }

    // ----- Engines that cannot express these options -----

    @Test
    fun `Neo4j reports nothing as unsupported`() {
        val spec = VectorIndexSpec("P", "e", 8, hnswM = 16, hnswEfConstruction = 100, engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)))

        assertTrue(neo4j.unsupportedVectorTuning(spec).isEmpty())
    }

    @Test
    fun `an engine without these options names each portable pin it will drop`() {
        val spec = VectorIndexSpec("P", "e", 8, hnswM = 16, engineOptions = listOf(Neo4jVectorOptions()))

        assertEquals(listOf("hnswM"), memgraph.unsupportedVectorTuning(spec))
    }

    @Test
    fun `options addressed to another engine are not reported as unsupported here`() {
        // Declaring Neo4j options does not make a Memgraph deployment incorrect — they simply do not
        // apply there, and warning about them would train people to ignore the warning.
        val spec = VectorIndexSpec(
            "P", "e", 8,
            engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)),
        )

        assertTrue(memgraph.unsupportedVectorTuning(spec).isEmpty())
    }

    // ----- Portable vs engine-specific resolution -----

    @Test
    fun `an engine-specific value overrides the portable one on that engine only`() {
        val spec = VectorIndexSpec(
            "P", "e", 8, hnswM = 16,
            engineOptions = listOf(Neo4jVectorOptions(hnswM = 32)),
        )

        assertEquals(32, spec.tuningFor("Neo4j").hnswM)
        assertEquals(16, spec.tuningFor("Memgraph").hnswM)
    }

    @Test
    fun `a partial engine override defers to the portable value for the rest`() {
        val spec = VectorIndexSpec(
            "P", "e", 8, hnswM = 16, hnswEfConstruction = 100,
            engineOptions = listOf(Neo4jVectorOptions(hnswM = 32)),
        )

        assertEquals(100, spec.tuningFor("Neo4j").hnswEfConstruction)
    }

    @Test
    fun `two option sets for the same engine are rejected`() {
        assertThrows<IllegalArgumentException> {
            VectorIndexSpec(
                "P", "e", 8,
                engineOptions = listOf(Neo4jVectorOptions(hnswM = 16), Neo4jVectorOptions(hnswM = 32)),
            )
        }
    }

    @Test
    fun `an engine override is what reaches the DDL`() {
        val statement = ddl(
            VectorIndexSpec("P", "e", 8, hnswM = 16, engineOptions = listOf(Neo4jVectorOptions(hnswM = 32)))
        )

        assertTrue(statement.contains("`vector.hnsw.m`: 32"), statement)
        assertFalse(statement.contains("`vector.hnsw.m`: 16"), statement)
    }

    @Test
    fun `an unpinned spec reports nothing unsupported anywhere`() {
        assertTrue(memgraph.unsupportedVectorTuning(VectorIndexSpec("P", "e", 8)).isEmpty())
    }

    // ----- Effective config reporting -----

    @Test
    fun `effective config lists what the engine reported`() {
        assertEquals(
            "dimensions=8, similarity=COSINE, quantization.enabled=true, hnsw.m=16, hnsw.ef_construction=100",
            existing(quantization = true, m = 16, ef = 100).vectorConfigDescription(),
        )
    }

    @Test
    fun `effective config omits what the engine did not report`() {
        assertEquals("dimensions=8, similarity=COSINE", existing().vectorConfigDescription())
    }

    @Test
    fun `fromSpec carries pinned parameters but invents nothing`() {
        val info = SchemaItemInfo.fromSpec(VectorIndexSpec("P", "e", 8, hnswM = 32))

        assertEquals(32, info.hnswM)
        assertNull(info.quantizationEnabled)
        assertNull(info.hnswEfConstruction)
    }
}
