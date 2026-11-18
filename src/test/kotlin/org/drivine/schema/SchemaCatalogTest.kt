package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.TextIndex
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

@NodeFragment(labels = ["Badge"])
data class BadgeFixture(
    @Unique
    val code: String,
)

@NodeFragment(labels = ["Message"])
data class OtherMessageFixture(
    @NodeId
    val id: String,
)

@NodeFragment(labels = ["Account"], nodeIdIndex = false)
data class UnindexedIdFixture(
    @NodeId
    val id: String,
)

@NodeFragment
data class LabellessIdFixture(
    @NodeId
    val id: String,
)

@NodeFragment(labels = ["Session"])
data class UniqueIdFixture(
    @NodeId
    @Unique
    val id: String,
)

@NodeFragment(labels = ["Ticket"])
data class TicketFixture(
    @NodeId
    @GraphProperty("ticket_id")
    val id: String,

    @Unique
    val serial: String,
)

@NodeFragment(labels = ["Chunk"])
data class ChunkFixture(
    @NodeId
    val id: String,

    @TextIndex
    @FullTextIndex
    val title: String,

    @TextIndex(name = "chunk_heading_text")
    @GraphProperty("heading_text")
    val heading: String,
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
    fun `named sets the owner and survives target and version copies`() {
        val base = SchemaCatalog.of(RangeIndexSpec("Chunk", "slug"))
        assertNull(base.name)

        val owned = base.named("rag")
        assertEquals("rag", owned.name)

        // name carries through the copy-returning builders
        assertEquals("rag", owned.forDatabase("neo").name)
        assertEquals("rag", owned.withVersion("v1").name)
        assertEquals("rag", owned.forDatabases("a", "b").name)
        assertEquals("rag", owned.forAllDatabases().name)

        // replace / last-wins, and back to anonymous
        assertEquals("app", owned.named("app").name)
        assertNull(owned.named(null).name)
    }

    @Test
    fun `merging catalogs with different owners fails fast`() {
        val app = SchemaCatalog.of(RangeIndexSpec("Chunk", "slug")).named("app")
        val rag = SchemaCatalog.of(RangeIndexSpec("Other", "id")).named("rag")

        val exception = assertThrows<DrivineException> { app + rag }
        assertTrue(exception.message!!.contains("different owners"))
    }

    @Test
    fun `inventoryKey is stable and distinguishes kind, properties, and name`() {
        assertEquals(
            "UNIQUENESS_CONSTRAINT:Chunk:id:Chunk_id_unique",
            UniquenessConstraintSpec("Chunk", "id").inventoryKey,
        )
        // an explicit name changes the key (a rename is a distinct inventory entry)
        assertEquals(
            "RANGE_INDEX:Chunk:slug:custom_name",
            RangeIndexSpec("Chunk", "slug", name = "custom_name").inventoryKey,
        )
        // identical declarations produce identical keys (co-ownership detection relies on this)
        assertEquals(
            VectorIndexSpec("Doc", "embedding", 768).inventoryKey,
            VectorIndexSpec("Doc", "embedding", 768).inventoryKey,
        )
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
    fun `scans @TextIndex as a TEXT index, apart from a @FullTextIndex on the same property`() {
        val catalog = SchemaCatalog.fromFragments(ChunkFixture::class)

        assertEquals(
            setOf(TextIndexSpec("Chunk", "title"), TextIndexSpec("Chunk", "heading_text", "chunk_heading_text")),
            catalog.indexes.filterIsInstance<TextIndexSpec>().toSet(),
        )
        assertEquals(listOf(FullTextIndexSpec("Chunk", "title")), catalog.indexes.filterIsInstance<FullTextIndexSpec>())
    }

    @Test
    fun `scans class-level composite annotations and uses the primary label`() {
        val catalog = SchemaCatalog.fromFragments(MessageFixture::class)

        val composite = catalog.indexes.filterIsInstance<RangeIndexSpec>().single { it !in catalog.nodeIdIndexes }
        assertEquals("Message", composite.label) // primary label, not ContentElement
        assertEquals(listOf("sessionId", "createdAt"), composite.properties)
        assertEquals("message_session_created_range", composite.name)

        val uniqueComposite = catalog.constraints.single() as UniquenessConstraintSpec
        assertEquals(listOf("sessionId", "sequence"), uniqueComposite.properties)
        assertEquals("id", uniqueComposite.nodeIdProperty, "a violation's sample names a node by the fragment's @NodeId")
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

    @Test
    fun `a fragment without a @NodeId names its conflicting nodes by the engine's id alone`() {
        val constraint = SchemaCatalog.fromFragments(BadgeFixture::class).constraints.single()

        assertNull(constraint.nodeIdProperty)
    }

    @Test
    fun `a @NodeId renamed by @GraphProperty names conflicting nodes by the property it is stored under`() {
        val constraint = SchemaCatalog.fromFragments(TicketFixture::class).constraints.single()

        assertEquals("ticket_id", constraint.nodeIdProperty)
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

        // The @NodeId names the sampled nodes of both, though it is the key of the one on id itself.
        assertEquals(listOf("id", "id"), listOf(composite, single).map { it.nodeIdProperty })
        assertNull(SchemaGrammar.sampledNodeId(single), "a key every conflicting node holds alike names none of them")
    }

    // ----- fragment + explicit spec merge -----

    @Test
    fun `fragment catalog merges with explicit spec catalog`() {
        val fromAnnotations = SchemaCatalog.fromFragments(MessageFixture::class)
        val explicit = SchemaCatalog.of(RangeIndexSpec("Topic", "name"))

        val merged = fromAnnotations + explicit

        // The composite, Topic(name), and Message(id), the fragment's @NodeId's.
        assertEquals(3, merged.indexes.size)
        assertEquals(setOf<IndexSpec>(RangeIndexSpec("Message", "id")), merged.nodeIdIndexes)
        assertEquals(1, merged.constraints.size)
    }

    // ----- A fragment's @NodeId's range index (#827) -----

    @Test
    fun `a fragment's @NodeId is given a range index under its primary label, over the property it is stored under`() {
        val message = SchemaCatalog.fromFragments(MessageFixture::class)
        assertEquals(setOf<IndexSpec>(RangeIndexSpec("Message", "id")), message.nodeIdIndexes)
        assertTrue(RangeIndexSpec("Message", "id") in message.indexes)
        // @GraphProperty: the property the id is stored under.
        assertEquals(setOf<IndexSpec>(RangeIndexSpec("Ticket", "ticket_id")), SchemaCatalog.fromFragments(TicketFixture::class).nodeIdIndexes)
    }

    @Test
    fun `a fragment without a @NodeId, or one that opts out, is given no range index for it`() {
        assertEquals(emptySet<IndexSpec>(), SchemaCatalog.fromFragments(BadgeFixture::class).nodeIdIndexes)
        val unindexed = SchemaCatalog.fromFragments(UnindexedIdFixture::class)
        assertEquals(emptySet<IndexSpec>(), unindexed.nodeIdIndexes)
        assertTrue(unindexed.indexes.isEmpty())
        // No label, which a save or load refuses: no index under the class's name.
        assertTrue(SchemaCatalog.fromFragments(LabellessIdFixture::class).isEmpty())
    }

    @Test
    fun `an item declared over the @NodeId alone serves it, so none is added beside it, whichever catalog declares it`() {
        for (declared in listOf(
            SchemaCatalog.of(RangeIndexSpec("Message", listOf("id"), "message_id")),
            SchemaCatalog.of(UniquenessConstraintSpec("Message", "id")),
        )) {
            val merged = SchemaCatalog.fromFragments(MessageFixture::class) + declared
            assertEquals(emptySet<IndexSpec>(), merged.nodeIdIndexes, "$declared")
            assertEquals(1, merged.items.count { it.label == "Message" && it.properties == listOf("id") }, "$declared")
        }
        // Declared on the fragment itself.
        assertEquals(emptySet<IndexSpec>(), SchemaCatalog.fromFragments(UniqueIdFixture::class).nodeIdIndexes)
    }

    @Test
    fun `an item over more properties, or one that ignores case, does not serve the @NodeId`() {
        for (declared in listOf(
            SchemaCatalog.of(RangeIndexSpec("Message", listOf("id", "sessionId"))),
            SchemaCatalog.of(RangeIndexSpec("Message", listOf("id"), ignoreCase = true)),
            SchemaCatalog.of(RangeIndexSpec("Other", "id")),
        )) {
            val merged = SchemaCatalog.fromFragments(MessageFixture::class) + declared
            assertEquals(setOf<IndexSpec>(RangeIndexSpec("Message", "id")), merged.nodeIdIndexes, "$declared")
        }
    }

    @Test
    fun `two fragments of one label give it one range index, and withoutNodeIdIndexes leaves only what is declared`() {
        val both = SchemaCatalog.fromFragments(MessageFixture::class, OtherMessageFixture::class)
        assertEquals(1, both.indexes.count { it == RangeIndexSpec("Message", "id") })
        val declared = both.withoutNodeIdIndexes()
        assertEquals(emptySet<IndexSpec>(), declared.nodeIdIndexes)
        assertTrue(RangeIndexSpec("Message", "id") !in declared.indexes)
        assertEquals(both.indexes.size - 1, declared.indexes.size)
        // The defaults survive a copy, and a merge.
        assertEquals(both.nodeIdIndexes, both.forDatabase("x").withVersion("1").named("app").nodeIdIndexes)
        assertEquals(both.nodeIdIndexes, (both + SchemaCatalog.of(RangeIndexSpec("Topic", "name"))).nodeIdIndexes)
    }

    @Test
    fun `a Java fragment's @NodeId is given a range index, and opts out as a Kotlin one does`() {
        // JavaMembershipNode's @NodeId is @Unique, which serves it.
        assertEquals(emptySet<IndexSpec>(), SchemaCatalog.fromFragments(JavaMembershipNode::class.java).nodeIdIndexes)
        val unindexed = SchemaCatalog.fromFragments(JavaUnindexedIdNode::class.java)
        assertEquals(emptySet<IndexSpec>(), unindexed.nodeIdIndexes)
        assertTrue(unindexed.isEmpty())
    }

    @Test
    fun `a default another catalog on the database serves is left out where they are applied, and one it doesn't serve kept`() {
        val fragments = SchemaCatalog.fromFragments(MessageFixture::class)
        val default = RangeIndexSpec("Message", "id")
        for (other in listOf(
            SchemaCatalog.of(RangeIndexSpec("Message", listOf("id"), "message_id")).named("app"),
            SchemaCatalog.of(UniquenessConstraintSpec("Message", "id")),
        )) {
            val declared = fragments.declaredItems + other.declaredItems
            assertTrue(default !in fragments.itemsBeside(declared), "$other")
            assertEquals(fragments.items.size - 1, fragments.itemsBeside(declared).size, "$other")
        }
        val unserved = fragments.declaredItems + SchemaCatalog.of(RangeIndexSpec("Message", listOf("id", "sessionId"))).declaredItems
        assertEquals(fragments.items, fragments.itemsBeside(unserved))
        // Another catalog's own default serves nothing: two catalogs of the fragment both keep theirs, as one item.
        assertEquals(fragments.items, fragments.itemsBeside(fragments.declaredItems + fragments.declaredItems))
        // One declared the same is the same item, co-owned: kept, so the owner's marker doesn't call it an orphan.
        assertEquals(fragments.items, fragments.itemsBeside(fragments.declaredItems + SchemaCatalog.of(default).named("app").declaredItems))
    }

    @Test
    fun `a constraint read from a fragment and one declared by hand are one, which names nodes by the fragment's id`() {
        val merged = SchemaCatalog.fromFragments(MessageFixture::class) +
            SchemaCatalog.of(UniquenessConstraintSpec("Message", listOf("sessionId", "sequence")))

        assertEquals(UniquenessConstraintSpec("Message", listOf("sessionId", "sequence"), nodeIdProperty = "id"), merged.constraints.single())

        // The fragment's wins though the one declared by hand comes first.
        val handFirst = SchemaCatalog.of(UniquenessConstraintSpec("Message", listOf("sessionId", "sequence"))) +
            SchemaCatalog.fromFragments(MessageFixture::class)
        assertEquals("id", handFirst.constraints.single().nodeIdProperty)
    }

    @Test
    fun `except leaves out what other catalogs declare under the same kind, label and properties, and keeps its own target, version and owner`() {
        val own = SchemaCatalog.of(
            RangeIndexSpec("Message", listOf("sessionId", "createdAt")),
            UniquenessConstraintSpec("Message", listOf("sessionId", "sequence")),
            RangeIndexSpec("Message", "sessionId"),
        ).named("app").withVersion("v1").forDatabase("users")
        // Named otherwise, its properties in another order, and for another database and owner: still the same item.
        val others = listOf(
            SchemaCatalog.of(RangeIndexSpec("Message", listOf("createdAt", "sessionId"), "other_name")).forDatabase("other"),
            SchemaCatalog.of(TextIndexSpec("Message", "sessionId")).named("lib"),
        )

        val left = own.except(others)

        assertEquals(listOf(RangeIndexSpec("Message", "sessionId")), left.indexes)
        assertEquals(own.constraints, left.constraints)
        assertEquals("app", left.name)
        assertEquals("v1", left.version)
        assertEquals(DatabaseTarget.Named(setOf("users")), left.target)
        assertEquals(own.items, own.except(emptyList()).items)
    }

    @Test
    fun `a default @NodeId index except keeps is still a default, and one another catalog declares is left to it`() {
        // As the Spring starter leaves to an app's own catalogs what its scan finds: the scan's defaults stay defaults.
        val scanned = SchemaCatalog.fromFragments(MessageFixture::class)
        val default = RangeIndexSpec("Message", "id")
        val kept = scanned.except(listOf(SchemaCatalog.of(RangeIndexSpec("Topic", "name"))))
        assertEquals(setOf<IndexSpec>(default), kept.nodeIdIndexes)
        assertTrue(default in kept.indexes)
        // So index-node-ids=false still leaves it out, and only it.
        assertTrue(default !in kept.withoutNodeIdIndexes().indexes)
        assertEquals(kept.indexes.size - 1, kept.withoutNodeIdIndexes().indexes.size)
        // An app catalog that declares the id's index takes it, default and all.
        val taken = scanned.except(listOf(SchemaCatalog.of(RangeIndexSpec("Message", listOf("id"), "message_id"))))
        assertEquals(emptySet<IndexSpec>(), taken.nodeIdIndexes)
        assertTrue(taken.indexes.none { it.properties == listOf("id") })
    }
}
