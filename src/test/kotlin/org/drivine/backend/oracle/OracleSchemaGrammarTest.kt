package org.drivine.backend.oracle

import org.drivine.query.SqlParameters
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OracleSchemaGrammarTest {

    private val graph = OracleGraph("g")
    private val grammar = OracleSchemaGrammar(graph)

    private fun List<SchemaStatement>.texts(): List<String> = map { (it as SchemaStatement.Cypher).statement }

    /** Asserts [statements] begin with the block that makes the store, and that nothing in any of them is read as a parameter. */
    private fun checked(statements: List<SchemaStatement>): List<String> {
        val texts = statements.texts()
        assertEquals(graph.ensuring, texts.first(), "the store is made first")
        texts.forEach { assertEquals(emptyList(), SqlParameters.positional(it).names, "no parameter in:\n$it") }
        return texts.drop(1)
    }

    private fun String.indexOfOrFail(part: String): Int = indexOf(part).also { assertTrue(it >= 0, "missing '$part' in:\n$this") }

    /** Asserts each of [parts] occurs in this text, after the one before it. */
    private fun String.inOrder(vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = indexOf(part, from)
            assertTrue(at >= 0, "missing '$part' after position $from in:\n$this")
            from = at + part.length
        }
    }

    @Test
    fun `a range index is over the properties read as text, whatever the label, and is recorded`() {
        val spec = RangeIndexSpec("Person", listOf("name", "metadata.source"))
        val (index, record) = checked(grammar.createIndex(spec, null))

        val name = graph.objectName("ix", spec.properties)
        assertEquals("CREATE INDEX IF NOT EXISTS $name ON g_node (JSON_VALUE(props, '$.name'), JSON_VALUE(props, '$.\"metadata.source\"'))", index)
        assertFalse(index.contains("Person"), "a range index does not depend on the label")

        assertTrue(record.startsWith("BEGIN\n"))
        assertTrue(record.endsWith("\nEND;"))
        record.inOrder(
            "MERGE INTO g_schema c",
            "USING (SELECT 'RANGE_INDEX' AS kind, 'Person' AS label, '[\"name\",\"metadata.source\"]' AS properties FROM dual) k",
            "ON (c.kind = k.kind AND c.label = k.label AND c.properties = k.properties)",
            "WHEN NOT MATCHED THEN INSERT (kind, label, properties, name, object_name, details)",
            "VALUES (k.kind, k.label, k.properties, 'Person_name_metadata.source_range', '$name', '{}');",
            "EXCEPTION WHEN DUP_VAL_ON_INDEX THEN",
            "NULL;",
            "END;",
        )
    }

    @Test
    fun `a vector index makes the side table, its trigger and backfill, then the index over what was filled, and records the dimensions and similarity`() {
        val spec = VectorIndexSpec("Doc", "embedding", 3, SimilarityFunction.EUCLIDEAN, name = "doc_vec")
        val texts = checked(grammar.createIndex(spec, null))
        assertEquals(5, texts.size)
        val (table, trigger, backfill, index, record) = texts
        val side = graph.vectors("Doc", "embedding")

        assertEquals("CREATE TABLE IF NOT EXISTS $side (nid NUMBER PRIMARY KEY REFERENCES g_node (nid) ON DELETE CASCADE, embedding VECTOR(3, FLOAT32) NOT NULL)", table)

        assertTrue(trigger.startsWith("BEGIN EXECUTE IMMEDIATE 'CREATE OR REPLACE TRIGGER ${side}_t AFTER INSERT OR UPDATE OF labels, props ON g_node FOR EACH ROW"), trigger)
        assertTrue(trigger.endsWith("END;'; END;"), trigger)
        trigger.inOrder(
            "IF INSTR(JSON_SERIALIZE(:NEW.labels), ''\"Doc\"'') > 0 AND JSON_EXISTS(:NEW.props, ''$.embedding'') THEN",
            "MERGE INTO $side s USING (SELECT :NEW.nid AS nid, TO_VECTOR(JSON_QUERY(:NEW.props, ''$.embedding'' RETURNING CLOB)) AS embedding FROM dual) n ON (s.nid = n.nid)",
            "EXCEPTION WHEN OTHERS THEN",
            "DELETE FROM $side WHERE nid = :NEW.nid;",
            "ELSE",
            "DELETE FROM $side WHERE nid = :NEW.nid;",
        )

        assertTrue(backfill.startsWith("INSERT INTO $side (nid, embedding)\nSELECT n.nid, TO_VECTOR(JSON_QUERY(n.props, '$.embedding' RETURNING CLOB)) FROM g_node n"), backfill)
        backfill.inOrder(
            "WHERE INSTR(JSON_SERIALIZE(n.labels), '\"Doc\"') > 0 AND JSON_EXISTS(n.props, '$.embedding')",
            "AND VECTOR_DIMENSION_COUNT(TO_VECTOR(JSON_QUERY(n.props, '$.embedding' RETURNING CLOB))) = 3",
            "AND NOT EXISTS (SELECT 1 FROM $side s WHERE s.nid = n.nid)",
        )

        assertEquals("CREATE VECTOR INDEX IF NOT EXISTS ${side}_i ON $side (embedding) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE EUCLIDEAN_SQUARED", index)

        record.inOrder(
            "MERGE INTO g_schema c",
            "'VECTOR_INDEX' AS kind, 'Doc' AS label, '[\"embedding\"]' AS properties",
            "VALUES (k.kind, k.label, k.properties, 'doc_vec', '$side', '{\"dimensions\": 3, \"similarity\": \"EUCLIDEAN\"}');",
        )
    }

    @Test
    fun `a full-text index is one block that makes the table, the search index when it can, the trigger and the backfill, and records what it made`() {
        val spec = FullTextIndexSpec("Doc", listOf("title", "body"), analyzer = "english")
        val texts = checked(grammar.createIndex(spec, null))
        val block = texts.single()
        val side = graph.texts("Doc", listOf("title", "body"))

        assertTrue(block.trimStart().startsWith("DECLARE"), block)
        assertTrue(block.endsWith("END;"), block)
        assertFalse(block.contains("END;;"), "no doubled terminator in:\n$block")
        block.inOrder(
            "PRAGMA EXCEPTION_INIT(e_no_text, -29833);",
            "PRAGMA EXCEPTION_INIT(e_exists, -955);",
            "BEGIN",
            "EXECUTE IMMEDIATE 'CREATE TABLE IF NOT EXISTS $side (nid NUMBER PRIMARY KEY REFERENCES g_node (nid) ON DELETE CASCADE, doc JSON NOT NULL)';",
            "SELECT COUNT(*) INTO v_indexed FROM user_indexes WHERE index_name = '${side.uppercase()}_I';",
            "IF v_indexed = 0 THEN",
            "EXECUTE IMMEDIATE 'CREATE SEARCH INDEX ${side}_i ON $side (doc) FOR JSON PARAMETERS (''SYNC (ON COMMIT)'')';",
            "WHEN e_exists THEN",
            "WHEN e_no_text THEN",
            "EXECUTE IMMEDIATE 'DROP TABLE $side PURGE';",
            "MERGE INTO g_schema c",
            "VALUES (k.kind, k.label, k.properties, 'Doc_title_body_fulltext', NULL, '{\"analyzer\": \"english\"}');",
            "RETURN;",
            "END IF;",
            "EXECUTE IMMEDIATE 'CREATE OR REPLACE TRIGGER ${side}_t AFTER INSERT OR UPDATE OF labels, props ON g_node FOR EACH ROW",
            "JSON_OBJECT(''title'' VALUE JSON_VALUE(:NEW.props, ''$.title'' RETURNING CLOB), ''body'' VALUE JSON_VALUE(:NEW.props, ''$.body'' RETURNING CLOB) ABSENT ON NULL RETURNING JSON) AS doc",
            "EXECUTE IMMEDIATE 'INSERT INTO $side (nid, doc)",
            "MERGE INTO g_schema c",
            "VALUES (k.kind, k.label, k.properties, 'Doc_title_body_fulltext', '$side', '{\"analyzer\": \"english\"}');",
        )
        assertEquals(2, Regex("MERGE INTO g_schema c").findAll(block).count())
        assertTrue(block.contains("'{}'").not() || spec.analyzer == null)
    }

    @Test
    fun `an analyzer is recorded as a JSON string, whatever it holds`() {
        val block = checked(grammar.createIndex(FullTextIndexSpec("Doc", listOf("title"), analyzer = "my \"own\" \\ one"), null)).single()

        assertTrue(block.contains("""'{"analyzer": "my \"own\" \\ one"}'"""), block)
    }

    @Test
    fun `a uniqueness constraint is a unique index with one expression per property, null for a node that is not constrained`() {
        val spec = UniquenessConstraintSpec("Person", listOf("email", "tenant"))
        val (index, record) = checked(grammar.createConstraint(spec))

        val name = graph.objectName("uq", listOf("Person", "email", "tenant"))
        val constrained = "JSON_EXISTS(n.labels, '$[*]?(@ == \"Person\")') AND JSON_EXISTS(n.props, '$.email') AND JSON_EXISTS(n.props, '$.tenant')"
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS $name ON g_node n (CASE WHEN $constrained THEN JSON_VALUE(n.props, '$.email') END, CASE WHEN $constrained THEN JSON_VALUE(n.props, '$.tenant') END)",
            index,
        )
        assertEquals(2, Regex("CASE WHEN").findAll(index).count())
        record.inOrder("'UNIQUENESS_CONSTRAINT' AS kind, 'Person' AS label, '[\"email\",\"tenant\"]' AS properties", "'Person_email_tenant_unique', '$name', '{}'")
    }

    @Test
    fun `dropping a vector or full-text index drops the trigger and the table beside the nodes, then forgets the index`() {
        val vector = SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"))
        val vectorTexts = grammar.dropIndex(vector).texts()
        val vectorSide = graph.vectors("Doc", "embedding")
        assertEquals(3, vectorTexts.size)
        assertEquals("DROP TRIGGER IF EXISTS ${vectorSide}_t", vectorTexts[0])
        assertEquals("DROP TABLE IF EXISTS $vectorSide PURGE", vectorTexts[1])
        vectorTexts[2].inOrder(
            "DECLARE",
            "DELETE FROM g_schema",
            "WHERE kind = 'VECTOR_INDEX' AND label = 'Doc' AND properties = '[\"embedding\"]'",
            "RETURNING MAX(object_name) INTO v_object;",
            "SELECT COUNT(*) INTO v_others FROM g_schema WHERE object_name = v_object;",
            "IF v_object IS NOT NULL AND v_others = 0 THEN",
            "EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_object;",
            "END;",
        )

        val text = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Doc", listOf("title", "body"))
        val textTexts = grammar.dropIndex(text).texts()
        val textSide = graph.texts("Doc", listOf("title", "body"))
        assertEquals(listOf("DROP TRIGGER IF EXISTS ${textSide}_t", "DROP TABLE IF EXISTS $textSide PURGE"), textTexts.take(2))
        assertTrue(textTexts[2].contains("WHERE kind = 'FULLTEXT_INDEX' AND label = 'Doc' AND properties = '[\"title\",\"body\"]'"))

        (vectorTexts + textTexts).forEach { assertEquals(emptyList(), SqlParameters.positional(it).names) }
    }

    @Test
    fun `dropping a range index or a constraint only forgets it, which drops the index when nothing else uses it`() {
        val range = grammar.dropIndex(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"))).texts()
        assertEquals(1, range.size)
        range.single().inOrder("DECLARE", "DELETE FROM g_schema", "WHERE kind = 'RANGE_INDEX' AND label = 'Person' AND properties = '[\"name\"]'", "DROP INDEX IF EXISTS")

        val constraint = grammar.dropConstraint(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"))).texts()
        assertEquals(1, constraint.size)
        assertTrue(constraint.single().contains("WHERE kind = 'UNIQUENESS_CONSTRAINT' AND label = 'Person' AND properties = '[\"email\"]'"))
    }

    @Test
    fun `what is listed is what was recorded and is still there`() {
        val indexes = grammar.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        indexes.inOrder(
            "SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details RETURNING JSON)",
            "FROM g_schema c",
            "WHERE kind IN ('RANGE_INDEX')",
            "object_name IS NULL",
            "EXISTS (SELECT 1 FROM user_indexes i WHERE i.index_name = UPPER(c.object_name))",
            "EXISTS (SELECT 1 FROM user_tables t WHERE t.table_name = UPPER(c.object_name))",
            "ORDER BY label, properties",
        )
        assertTrue(grammar.listIndexesQuery(SchemaItemKind.VECTOR_INDEX).contains("WHERE kind IN ('VECTOR_INDEX')"))
        assertTrue(grammar.listConstraintsQuery().contains("WHERE kind IN ('UNIQUENESS_CONSTRAINT')"))
        assertEquals(emptyList(), SqlParameters.positional(indexes).names)
    }

    @Test
    fun `a listed row is read back as the item it records, and a row that is not a map is passed over`() {
        val rows = listOf<Any?>(
            mapOf(
                "kind" to "VECTOR_INDEX", "label" to "Doc", "properties" to listOf("embedding"), "name" to "doc_vec",
                "details" to mapOf("dimensions" to 3L, "similarity" to "COSINE"),
            ),
            mapOf("kind" to "FULLTEXT_INDEX", "label" to "Doc", "properties" to listOf("title", "body"), "name" to null, "details" to mapOf("analyzer" to "english")),
            mapOf("kind" to "RANGE_INDEX", "label" to "Person", "properties" to listOf("name"), "name" to "r", "details" to emptyMap<String, Any>()),
            "not a row",
            null,
        )
        assertEquals(
            listOf(
                SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"), name = "doc_vec", dimensions = 3, similarity = SimilarityFunction.COSINE),
                SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Doc", listOf("title", "body"), name = null, analyzer = "english"),
                SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), name = "r"),
            ),
            grammar.parseIndexRows(rows),
        )
        assertEquals(grammar.parseIndexRows(rows), grammar.parseConstraintRows(rows))
    }

    @Test
    fun `a second row for a unique key, or a unique index over rows that repeat one, is a constraint violation, wherever in the cause chain`() {
        assertTrue(grammar.isConstraintViolation(SQLException("ORA-00001: unique constraint violated", "23000", 1)))
        assertTrue(grammar.isConstraintViolation(RuntimeException("wrapped", IllegalStateException("again", SQLException("ORA-01452", "72000", 1452)))))
        assertFalse(grammar.isConstraintViolation(SQLException("ORA-00942: table or view does not exist", "42000", 942)))
        assertFalse(grammar.isConstraintViolation(RuntimeException("no SQL in it")))
    }

    @Test
    fun `the conflicts of a constraint are the labelled nodes that share values of its properties`() {
        val query = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", listOf("email", "tenant")), 5)
        query.inOrder(
            "SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT('email' VALUE JSON_VALUE(n.props, '$.email'), 'tenant' VALUE JSON_VALUE(n.props, '$.tenant')), 'count' VALUE COUNT(*) RETURNING JSON)",
            "FROM g_node n",
            "WHERE JSON_EXISTS(n.labels, '$[*]?(@ == \"Person\")') AND JSON_VALUE(n.props, '$.email') IS NOT NULL AND JSON_VALUE(n.props, '$.tenant') IS NOT NULL",
            "GROUP BY JSON_VALUE(n.props, '$.email'), JSON_VALUE(n.props, '$.tenant')",
            "HAVING COUNT(*) > 1",
            "FETCH FIRST 5 ROWS ONLY",
        )
        assertEquals(emptyList(), SqlParameters.positional(query).names)
    }

    @Test
    fun `the properties of an index are recorded as a JSON array, with quotes and backslashes escaped`() {
        assertEquals("[]", OracleSchemaGrammar.json(emptyList()))
        assertEquals("[\"a\\\"b\",\"c\\\\d\"]", OracleSchemaGrammar.json(listOf("a\"b", "c\\d")))
    }

    @Test
    fun `the grammar says what it is`() {
        assertEquals("Oracle", grammar.engine)
        assertTrue(grammar.supportsIfNotExists)
        assertTrue(grammar.supportsNamedItems)
        assertTrue(grammar.constraintsRequireBackingIndex)
    }
}
