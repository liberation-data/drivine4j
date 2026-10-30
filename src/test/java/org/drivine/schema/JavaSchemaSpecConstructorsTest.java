package org.drivine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every schema spec, option class and {@link SchemaPolicy} has a constructor for each arity, so a
 * caller compiled against fewer optional parameters still links after one is added. Each arity is
 * checked against the full form with the defaults for the rest. See "Adding an option" on
 * {@link SchemaItemSpec}.
 */
class JavaSchemaSpecConstructorsTest {

    private static final List<String> TITLE = List.of("title");

    @Test
    void vectorIndexSpecAtEveryArity() {
        VectorIndexSpec full = new VectorIndexSpec(
                "Doc", "embedding", 3, SimilarityFunction.COSINE, null, null, null, List.of());

        assertEquals(full, new VectorIndexSpec("Doc", "embedding", 3));
        assertEquals(full, new VectorIndexSpec("Doc", "embedding", 3, SimilarityFunction.COSINE));
        assertEquals(full, new VectorIndexSpec("Doc", "embedding", 3, SimilarityFunction.COSINE, null));
        assertEquals(full, new VectorIndexSpec("Doc", "embedding", 3, SimilarityFunction.COSINE, null, null));
        assertEquals(full, new VectorIndexSpec("Doc", "embedding", 3, SimilarityFunction.COSINE, null, null, null));
    }

    @Test
    void rangeIndexSpecAtEveryArity() {
        RangeIndexSpec full = new RangeIndexSpec("Doc", TITLE, null, false);

        assertEquals(full, new RangeIndexSpec("Doc", TITLE));
        assertEquals(full, new RangeIndexSpec("Doc", TITLE, null));
        assertEquals(full, new RangeIndexSpec("Doc", "title"));
        assertEquals(full, new RangeIndexSpec("Doc", "title", null));
        assertEquals(full, new RangeIndexSpec("Doc", "title", null, false));
    }

    @Test
    void fullTextIndexSpecAtEveryArity() {
        FullTextIndexSpec full = new FullTextIndexSpec("Doc", TITLE, null, null);

        assertEquals(full, new FullTextIndexSpec("Doc", TITLE));
        assertEquals(full, new FullTextIndexSpec("Doc", TITLE, null));
        assertEquals(full, new FullTextIndexSpec("Doc", "title"));
        assertEquals(full, new FullTextIndexSpec("Doc", "title", null));
        assertEquals(full, new FullTextIndexSpec("Doc", "title", null, null));
    }

    @Test
    void textIndexSpecAtEveryArity() {
        TextIndexSpec full = new TextIndexSpec("Doc", "title", null, false);

        assertEquals(full, new TextIndexSpec("Doc", "title"));
        assertEquals(full, new TextIndexSpec("Doc", "title", null));
    }

    @Test
    void uniquenessConstraintSpecAtEveryArity() {
        UniquenessConstraintSpec full = new UniquenessConstraintSpec("Doc", TITLE, null, null, false);

        assertEquals(full, new UniquenessConstraintSpec("Doc", TITLE));
        assertEquals(full, new UniquenessConstraintSpec("Doc", TITLE, null));
        assertEquals(full, new UniquenessConstraintSpec("Doc", TITLE, null, null));
        assertEquals(full, new UniquenessConstraintSpec("Doc", "title"));
        assertEquals(full, new UniquenessConstraintSpec("Doc", "title", null));
        assertEquals(full, new UniquenessConstraintSpec("Doc", "title", null, null));
        assertEquals(full, new UniquenessConstraintSpec("Doc", "title", null, null, false));
    }

    @Test
    void neo4jVectorOptionsAtEveryArity() {
        Neo4jVectorOptions full = new Neo4jVectorOptions(null, null, null);

        assertEquals(full, new Neo4jVectorOptions());
        assertEquals(full, new Neo4jVectorOptions(null));
        assertEquals(full, new Neo4jVectorOptions(null, null));
    }

    @Test
    void falkorDbVectorOptionsAtEveryArity() {
        FalkorDbVectorOptions full = new FalkorDbVectorOptions(null, null, null);

        assertEquals(full, new FalkorDbVectorOptions());
        assertEquals(full, new FalkorDbVectorOptions(null));
        assertEquals(full, new FalkorDbVectorOptions(null, null));
    }

    @Test
    void schemaPolicyAtEveryArity() {
        SchemaPolicy full = new SchemaPolicy(SchemaMode.FAIL_FAST, false, false, 10, false);

        assertEquals(full, new SchemaPolicy());
        assertEquals(full, new SchemaPolicy(SchemaMode.FAIL_FAST));
        assertEquals(full, new SchemaPolicy(SchemaMode.FAIL_FAST, false));
        assertEquals(full, new SchemaPolicy(SchemaMode.FAIL_FAST, false, false));
        assertEquals(full, new SchemaPolicy(SchemaMode.FAIL_FAST, false, false, 10));
    }
}
