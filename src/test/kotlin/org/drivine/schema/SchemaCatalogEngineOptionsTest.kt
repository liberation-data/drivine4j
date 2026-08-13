package org.drivine.schema

import org.drivine.DrivineException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Declaration-time resolution when the same vector index is declared twice.
 *
 * This is not an edge case: any index that is both *queried* and *tuned* is necessarily declared twice.
 * `@VectorIndex` has to stay on the fragment because `VectorIndexResolver` resolves `loadNearest`'s
 * target index from the annotation, while engine-specific options can only be written in a catalog spec.
 * The two declarations meet in `SchemaCatalog.deduplicate`, which runs on specs alone — no database is
 * involved, so drift cannot arbitrate here and a tie-break rule has to.
 */
class SchemaCatalogEngineOptionsTest {

    private val scanned = VectorIndexSpec("Proposition", "embedding", 1536, hnswM = 32)

    private val tuned = VectorIndexSpec(
        "Proposition", "embedding", 1536, hnswM = 32,
        engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false)),
    )

    @Test
    fun `the declaration carrying engine options wins`() {
        val catalog = SchemaCatalog.merge(listOf(SchemaCatalog.of(scanned), SchemaCatalog.of(tuned)))

        assertEquals(tuned, catalog.indexes.single())
    }

    @Test
    fun `the winner does not depend on declaration order`() {
        val catalog = SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(scanned)))

        assertEquals(tuned, catalog.indexes.single())
    }

    @Test
    fun `the untuned declaration is not left behind as a second index`() {
        // Both describe one index; emitting two specs would mean two CREATE statements for it.
        val catalog = SchemaCatalog.merge(listOf(SchemaCatalog.of(scanned), SchemaCatalog.of(tuned)))

        assertEquals(1, catalog.indexes.size)
    }

    @Test
    fun `identical declarations still collapse`() {
        val catalog = SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(tuned)))

        assertEquals(tuned, catalog.indexes.single())
    }

    @Test
    fun `no engine options anywhere behaves as before`() {
        val catalog = SchemaCatalog.merge(listOf(SchemaCatalog.of(scanned), SchemaCatalog.of(scanned)))

        assertEquals(scanned, catalog.indexes.single())
    }

    // ----- Still fail fast on genuine conflicts -----

    @Test
    fun `disagreement on portable parameters is still a conflict`() {
        // The check exists for exactly this: two fragments claiming different dimensions for one property.
        val other = VectorIndexSpec("Proposition", "embedding", 768, hnswM = 32)

        val error = assertThrows<DrivineException> {
            SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(other)))
        }
        assertTrue(error.message!!.contains("portable parameters"), error.message)
    }

    @Test
    fun `a portable pin that differs is a conflict even when one side is tuned`() {
        val other = VectorIndexSpec("Proposition", "embedding", 1536, hnswM = 64)

        assertThrows<DrivineException> {
            SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(other)))
        }
    }

    @Test
    fun `two declarations that both pin engine options conflict`() {
        val rival = VectorIndexSpec(
            "Proposition", "embedding", 1536, hnswM = 32,
            engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)),
        )

        val error = assertThrows<DrivineException> {
            SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(rival)))
        }
        assertTrue(error.message!!.contains("more than one declaration pins engine options"), error.message)
    }

    @Test
    fun `declarations for different engines still conflict rather than combining`() {
        // Combining them would be a field-level merge, which is the complexity this rule avoids. Declare
        // both engines' options on one spec instead.
        val falkor = VectorIndexSpec(
            "Proposition", "embedding", 1536, hnswM = 32,
            engineOptions = listOf(FalkorDbVectorOptions(efRuntime = 10)),
        )

        assertThrows<DrivineException> {
            SchemaCatalog.merge(listOf(SchemaCatalog.of(tuned), SchemaCatalog.of(falkor)))
        }
    }

    @Test
    fun `one spec can carry options for several engines`() {
        val both = VectorIndexSpec(
            "Proposition", "embedding", 1536,
            engineOptions = listOf(
                Neo4jVectorOptions(quantizationEnabled = false),
                FalkorDbVectorOptions(efRuntime = 10),
            ),
        )

        assertEquals(both, SchemaCatalog.of(both).indexes.single())
    }

    @Test
    fun `unrelated indexes are untouched`() {
        val catalog = SchemaCatalog.merge(
            listOf(
                SchemaCatalog.of(scanned, RangeIndexSpec("Proposition", "contextId")),
                SchemaCatalog.of(tuned),
            )
        )

        assertEquals(2, catalog.indexes.size)
        assertTrue(catalog.indexes.contains(tuned))
        assertTrue(catalog.indexes.contains(RangeIndexSpec("Proposition", "contextId")))
    }
}
