package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.model.FragmentModel
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

// What the check sees lasts for the test JVM, so every fragment here has a label of its own.

@NodeFragment(labels = ["CheckMessageLater"])
data class CheckMessageLater(@NodeId val id: String, val embedding: List<Float>?)

@NodeFragment(labels = ["CheckMessageFirst"])
data class CheckMessageFirst(@NodeId val id: String, val embedding: List<Float>?)

@NodeFragment(labels = ["CheckRenamed"])
data class CheckRenamed(@NodeId val id: String, @GraphProperty("embedding_vec") val vector: List<Float>?)

@NodeFragment(labels = ["CheckRenamedAway"])
data class CheckRenamedAway(@NodeId val id: String, @GraphProperty("other") val embedding: List<Float>?)

@NodeFragment(labels = ["CheckChunk", "CheckContent"])
data class CheckChunk(@NodeId val id: String, val embedding: List<Float>?)

@NodeFragment(labels = ["CheckTwoFields"])
data class CheckTwoFields(@NodeId val id: String, val embedding: List<Float>?, val summary: List<Float>?)

@NodeFragment(labels = ["CheckAnnotated"])
data class CheckAnnotated(@NodeId val id: String, @VectorIndex val embedding: List<Float>?)

@NodeFragment(labels = ["CheckAnnotatedLater"])
data class CheckAnnotatedLater(@NodeId val id: String, @VectorIndex val embedding: List<Float>?)

/**
 * A vector index declared in a catalog over a fragment field that has no `@VectorIndex` is refused,
 * whichever of the two Drivine sees first. No database is registered, so [SchemaManager.enforce] gets
 * as far as the check and then has nowhere to apply.
 */
class VectorAnnotationCheckTest {

    private fun enforce(vararg specs: SchemaItemSpec) = enforce(listOf(SchemaCatalog.of(*specs)))

    private fun enforce(catalogs: List<SchemaCatalog>) {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap()))
        SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs)
            .enforce()
    }

    private fun message(field: String, spec: String) =
        "`$field` is covered by the vector index on `$spec` but is not annotated `@VectorIndex`. Add the annotation: " +
            "without it the field is not written as a vector on FalkorDB, and `loadNearest` cannot find its index."

    @Test
    fun `applying a catalog refuses a spec over a known fragment's unannotated field`() {
        FragmentModel.from(CheckMessageLater::class)

        val error = assertThrows<DrivineException> { enforce(VectorIndexSpec("CheckMessageLater", "embedding", 4)) }

        assertEquals(message("CheckMessageLater.embedding", "CheckMessageLater.embedding"), error.message)
    }

    @Test
    fun `building a fragment refuses an unannotated field a catalog already indexed`() {
        enforce(VectorIndexSpec("CheckMessageFirst", "embedding", 4))

        val error = assertThrows<DrivineException> { FragmentModel.from(CheckMessageFirst::class) }
        assertEquals(message("CheckMessageFirst.embedding", "CheckMessageFirst.embedding"), error.message)
        // Refused every time, not only the first.
        assertThrows<DrivineException> { FragmentModel.from(CheckMessageFirst::class) }
    }

    @Test
    fun `a field is matched by the property it is stored under`() {
        FragmentModel.from(CheckRenamed::class)
        FragmentModel.from(CheckRenamedAway::class)

        val error = assertThrows<DrivineException> { enforce(VectorIndexSpec("CheckRenamed", "embedding_vec", 4)) }
        assertEquals(message("CheckRenamed.vector", "CheckRenamed.embedding_vec"), error.message)
        // Stored under "other", so a spec on "embedding" covers nothing of it.
        assertDoesNotThrow { enforce(VectorIndexSpec("CheckRenamedAway", "embedding", 4)) }
    }

    @Test
    fun `a spec on any of a fragment's labels covers its field`() {
        FragmentModel.from(CheckChunk::class)

        val error = assertThrows<DrivineException> { enforce(VectorIndexSpec("CheckContent", "embedding", 4)) }

        assertEquals(message("CheckChunk.embedding", "CheckContent.embedding"), error.message)
    }

    @Test
    fun `a spec for a label no fragment maps is allowed`() {
        assertDoesNotThrow { enforce(VectorIndexSpec("CheckCypherOnly", "embedding", 4)) }
    }

    @Test
    fun `the annotation beside a spec is allowed, whichever comes first`() {
        FragmentModel.from(CheckAnnotated::class)
        // The catalog's declaration wins where the two differ, so a different similarity is no conflict.
        assertDoesNotThrow { enforce(VectorIndexSpec("CheckAnnotated", "embedding", 4, SimilarityFunction.EUCLIDEAN)) }

        enforce(VectorIndexSpec("CheckAnnotatedLater", "embedding", 4))
        assertDoesNotThrow { FragmentModel.from(CheckAnnotatedLater::class) }
    }

    @Test
    fun `every uncovered field is reported, once however many specs cover it`() {
        FragmentModel.from(CheckTwoFields::class)

        val error = assertThrows<DrivineException> {
            // Two owners may declare the same index; the field is still reported once.
            enforce(
                listOf(
                    SchemaCatalog.of(VectorIndexSpec("CheckTwoFields", "embedding", 4)).named("check-a"),
                    SchemaCatalog.of(
                        VectorIndexSpec("CheckTwoFields", "embedding", 4),
                        VectorIndexSpec("CheckTwoFields", "summary", 4),
                    ).named("check-b"),
                ),
            )
        }

        assertEquals(
            message("CheckTwoFields.embedding", "CheckTwoFields.embedding") + "\n" +
                message("CheckTwoFields.summary", "CheckTwoFields.summary"),
            error.message,
        )
    }
}
