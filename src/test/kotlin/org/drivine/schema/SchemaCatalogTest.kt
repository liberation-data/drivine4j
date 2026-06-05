package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ----- Kotlin fragment fixtures -----

@NodeFragment(labels = ["Proposition"])
data class PropositionFixture(
    @NodeId
    @RangeIndex
    @Unique
    val id: String,

    @RangeIndex(name = "proposition_context_range")
    val contextId: String,

    val text: String,

    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>?,
)

@NodeFragment(labels = ["Message", "ContentElement"])
@RangeIndex(properties = ["sessionId", "createdAt"], name = "message_session_created_range")
@Unique(properties = ["sessionId", "sequence"])
data class MessageFixture(
    @NodeId
    val id: String,
    val sessionId: String,
    val createdAt: Long,
    val sequence: Int,
)

data class NotAFragment(val id: String)

class SchemaCatalogTest {

    // ----- of() -----

    @Test
    fun `of partitions specs into indexes and constraints and targets all databases by default`() {
        val catalog = SchemaCatalog.of(
            VectorIndexSpec("Proposition", "embedding", 1536),
            RangeIndexSpec("Proposition", "contextId"),
            UniquenessConstraintSpec("Proposition", "id"),
        )

        assertEquals(2, catalog.indexes.size)
        assertEquals(1, catalog.constraints.size)
        assertEquals(DatabaseTarget.All, catalog.target)
        assertFalse(catalog.isEmpty())
    }

    @Test
    fun `target narrowing - default, named, multiple, and back to all`() {
        val base = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId"))

        assertEquals(DatabaseTarget.All, base.target)
        assertEquals(DatabaseTarget.Named(setOf("default")), base.forDefaultDatabase().target)
        assertEquals(DatabaseTarget.Named(setOf("neo")), base.forDatabase("neo").target)
        assertEquals(DatabaseTarget.Named(setOf("a", "b")), base.forDatabases("a", "b").target)
        assertEquals(DatabaseTarget.All, base.forDatabase("neo").forAllDatabases().target)

        // narrowing preserves the specs
        assertEquals(1, base.forDatabase("neo").indexes.size)
    }

    @Test
    fun `withVersion sets the token and survives target narrowing`() {
        val base = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId"))
        assertNull(base.version)

        val versioned = base.withVersion("model-v3")
        assertEquals("model-v3", versioned.version)

        // version carries through forDatabase / forDatabases / forAllDatabases copies
        assertEquals("model-v3", versioned.forDatabase("neo").version)
        assertEquals("model-v3", versioned.forDatabases("a", "b").version)
        assertEquals("model-v3", versioned.forDefaultDatabase().version)
        assertEquals("model-v3", versioned.forDatabase("neo").forAllDatabases().version)
    }

    @Test
    fun `merge combines version tokens of its parts`() {
        val a = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId")).withVersion("v1")
        val b = SchemaCatalog.of(RangeIndexSpec("Mention", "id")).withVersion("v2")
        val unversioned = SchemaCatalog.of(RangeIndexSpec("Topic", "name"))

        assertEquals("v1|v2", (a + b).version)
        assertEquals("v1", (a + unversioned).version)   // token survives a merge with an unversioned catalog
        assertNull((unversioned + unversioned).version)
    }

    @Test
    fun `target includes resolves membership`() {
        assertTrue(DatabaseTarget.All.includes("anything"))
        assertTrue(DatabaseTarget.Named(setOf("a", "b")).includes("a"))
        assertFalse(DatabaseTarget.Named(setOf("a", "b")).includes("c"))
    }

    @Test
    fun `items orders indexes before constraints`() {
        val catalog = SchemaCatalog.of(
            UniquenessConstraintSpec("Proposition", "id"),
            RangeIndexSpec("Proposition", "contextId"),
        )

        assertTrue(catalog.items.first() is IndexSpec)
        assertTrue(catalog.items.last() is ConstraintSpec)
    }

    // ----- merge / dedup / conflicts -----

    @Test
    fun `identical declarations deduplicate on merge`() {
        val a = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId"))
        val b = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId"))

        val merged = a + b

        assertEquals(1, merged.indexes.size)
    }

    @Test
    fun `conflicting declarations for the same key fail fast`() {
        val a = SchemaCatalog.of(VectorIndexSpec("Proposition", "embedding", 768))
        val b = SchemaCatalog.of(VectorIndexSpec("Proposition", "embedding", 1536))

        val exception = assertThrows<DrivineException> { a + b }
        assertTrue(exception.message!!.contains("Conflicting schema declarations"))
    }

    @Test
    fun `same properties in different order on a composite index is a conflict`() {
        val a = SchemaCatalog.of(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))
        val b = SchemaCatalog.of(RangeIndexSpec("Message", listOf("createdAt", "sessionId")))

        assertThrows<DrivineException> { a + b }
    }

    @Test
    fun `merging catalogs with different targets fails fast`() {
        val a = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId"))                       // All
        val b = SchemaCatalog.of(RangeIndexSpec("Other", "id")).forDatabase("analytics")           // Named

        assertThrows<DrivineException> { SchemaCatalog.merge(listOf(a, b)) }
    }

    @Test
    fun `merging catalogs with the same named target succeeds`() {
        val a = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId")).forDatabase("analytics")
        val b = SchemaCatalog.of(RangeIndexSpec("Other", "id")).forDatabase("analytics")

        val merged = a + b

        assertEquals(2, merged.indexes.size)
        assertEquals(DatabaseTarget.Named(setOf("analytics")), merged.target)
    }

    // ----- fromFragments: Kotlin -----

    @Test
    fun `scans property-level annotations off a Kotlin fragment`() {
        val catalog = SchemaCatalog.fromFragments(
            VectorDimensionProvider.fixed(1536),
            PropositionFixture::class,
        )

        // @RangeIndex on id + @RangeIndex on contextId + @VectorIndex on embedding
        assertEquals(3, catalog.indexes.size)
        // @Unique on id
        assertEquals(1, catalog.constraints.size)

        val vector = catalog.indexes.filterIsInstance<VectorIndexSpec>().single()
        assertEquals("Proposition", vector.label)
        assertEquals("embedding", vector.property)
        assertEquals(1536, vector.dimensions)

        val named = catalog.indexes.filterIsInstance<RangeIndexSpec>().first { it.name != null }
        assertEquals("proposition_context_range", named.name)
        assertEquals(listOf("contextId"), named.properties)

        val unique = catalog.constraints.single() as UniquenessConstraintSpec
        assertEquals(listOf("id"), unique.properties)
    }

    @Test
    fun `scans class-level composite annotations and uses the primary label`() {
        val catalog = SchemaCatalog.fromFragments(MessageFixture::class)

        val composite = catalog.indexes.filterIsInstance<RangeIndexSpec>().single()
        assertEquals("Message", composite.label) // primary label, not ContentElement
        assertEquals(listOf("sessionId", "createdAt"), composite.properties)
        assertEquals("message_session_created_range", composite.name)

        val uniqueComposite = catalog.constraints.single() as UniquenessConstraintSpec
        assertEquals(listOf("sessionId", "sequence"), uniqueComposite.properties)
    }

    @Test
    fun `vector annotation without a dimension provider fails fast`() {
        val exception = assertThrows<DrivineException> {
            SchemaCatalog.fromFragments(PropositionFixture::class)
        }
        assertTrue(exception.message!!.contains("VectorDimensionProvider"))
    }

    @Test
    fun `scanning a class that is not a fragment fails fast`() {
        assertThrows<DrivineException> {
            SchemaCatalog.fromFragments(NotAFragment::class)
        }
    }

    // ----- fromFragments: Java -----

    @Test
    fun `scans annotations off a Java fragment via field reflection`() {
        val catalog = SchemaCatalog.fromFragments(JavaMembershipNode::class.java)

        // field-level @RangeIndex on tenantId
        val range = catalog.indexes.filterIsInstance<RangeIndexSpec>().single()
        assertEquals("Membership", range.label)
        assertEquals(listOf("tenantId"), range.properties)

        // class-level composite @Unique + field-level @Unique on id
        assertEquals(2, catalog.constraints.size)
        val composite = catalog.constraints.first { it.properties.size == 2 }
        assertEquals(listOf("tenantId", "userId"), composite.properties)
        val single = catalog.constraints.first { it.properties.size == 1 }
        assertEquals(listOf("id"), single.properties)
    }

    // ----- fragment + explicit spec merge -----

    @Test
    fun `fragment catalog merges with explicit spec catalog`() {
        val fromAnnotations = SchemaCatalog.fromFragments(MessageFixture::class)
        val explicit = SchemaCatalog.of(RangeIndexSpec("Topic", "name"))

        val merged = fromAnnotations + explicit

        assertEquals(2, merged.indexes.size)
        assertEquals(1, merged.constraints.size)
    }
}