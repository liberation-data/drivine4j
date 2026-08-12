package org.drivine.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Name resolution for schema item specs.
 *
 * Two distinct rules, easily confused: an EMPTY name means "unset" (annotation attributes cannot default
 * to null, so the scanner passes `""` through), while a WHITESPACE-ONLY name is a mistake and is rejected
 * at construction. Rejecting rather than normalizing keeps this in lockstep with the query-side resolvers,
 * which derive the name they search for independently.
 */
class SchemaItemSpecNameTest {

    // ----- Empty name means "derive a default" -----

    @Test
    fun `an empty explicit name derives the default name for every spec kind`() {
        assertEquals(
            "Proposition_embedding_vector",
            VectorIndexSpec("Proposition", "embedding", 1536, name = "").effectiveName,
        )
        assertEquals("Chunk_title_range", RangeIndexSpec("Chunk", "title", name = "").effectiveName)
        assertEquals("Chunk_body_fulltext", FullTextIndexSpec("Chunk", "body", name = "").effectiveName)
        assertEquals(
            "ChatSession_id_unique",
            UniquenessConstraintSpec("ChatSession", "id", name = "").effectiveName,
        )
    }

    @Test
    fun `an empty explicit name resolves identically to a null one`() {
        val derived = VectorIndexSpec("Proposition", "embedding", 1536, name = null)
        val empty = VectorIndexSpec("Proposition", "embedding", 1536, name = "")

        assertEquals(derived.effectiveName, empty.effectiveName)
        assertEquals(derived.inventoryKey, empty.inventoryKey)
    }

    @Test
    fun `an explicit name is honoured`() {
        assertEquals(
            "my_index",
            VectorIndexSpec("Proposition", "embedding", 1536, name = "my_index").effectiveName,
        )
    }

    // ----- Whitespace-only names are rejected, not normalized -----

    @Test
    fun `a whitespace-only name is rejected for every spec kind`() {
        assertThrows<IllegalArgumentException> { VectorIndexSpec("Proposition", "embedding", 1536, name = " ") }
        assertThrows<IllegalArgumentException> { RangeIndexSpec("Chunk", "title", name = " ") }
        assertThrows<IllegalArgumentException> { FullTextIndexSpec("Chunk", "body", name = "\t") }
        assertThrows<IllegalArgumentException> { UniquenessConstraintSpec("ChatSession", "id", name = "\n") }
    }

    @Test
    fun `the whitespace rejection message points at the two valid ways to derive a name`() {
        val error = assertThrows<IllegalArgumentException> {
            VectorIndexSpec("Proposition", "embedding", 1536, name = " ")
        }
        assertTrue(error.message!!.contains("whitespace-only"), error.message)
        assertTrue(error.message!!.contains("derive a name"), error.message)
    }

    @Test
    fun `a name with internal whitespace is still allowed`() {
        // Backtick quoting makes this legal DDL, and unlike a blank name it is plainly deliberate.
        assertEquals("my index", RangeIndexSpec("Chunk", "title", name = "my index").effectiveName)
    }

    // ----- Synthesized info describes what was actually created -----

    @Test
    fun `fromSpec reports the effective name, not the raw one`() {
        // find() returns null in the FalkorDB async-create window and wherever introspection is
        // unavailable, so this synthesized info is what ensure() hands back as EnsureResult.Created.
        // Reporting the raw "" would both misdescribe the database and, fed back to dropIndex, emit
        // `DROP INDEX `` IF EXISTS` — which the `name ?: throw` guard does not catch.
        val info = SchemaItemInfo.fromSpec(VectorIndexSpec("Proposition", "embedding", 1536, name = ""))

        assertEquals("Proposition_embedding_vector", info.name)
    }

    @Test
    fun `fromSpec derives a name for specs that never had an explicit one`() {
        assertEquals("Chunk_title_range", SchemaItemInfo.fromSpec(RangeIndexSpec("Chunk", "title")).name)
        assertEquals("Chunk_body_fulltext", SchemaItemInfo.fromSpec(FullTextIndexSpec("Chunk", "body")).name)
        assertEquals(
            "ChatSession_id_unique",
            SchemaItemInfo.fromSpec(UniquenessConstraintSpec("ChatSession", "id")).name,
        )
    }

    @Test
    fun `fromSpec carries the shape fields through unchanged`() {
        val info = SchemaItemInfo.fromSpec(
            VectorIndexSpec("Proposition", "embedding", 1536, SimilarityFunction.EUCLIDEAN),
        )

        assertEquals(SchemaItemKind.VECTOR_INDEX, info.kind)
        assertEquals("Proposition", info.label)
        assertEquals(listOf("embedding"), info.properties)
        assertEquals(1536, info.dimensions)
        assertEquals(SimilarityFunction.EUCLIDEAN, info.similarity)
    }
}
