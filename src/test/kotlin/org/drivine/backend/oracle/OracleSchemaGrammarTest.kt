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
    fun `a range index is over the properties read as text, whatever the label, and beside it over those of the label's nodes, and is recorded`() {
        val spec = RangeIndexSpec("Person", listOf("name", "metadata.source"))
        val (index, labelledIndex, record) = checked(grammar.createIndex(spec, null))

        val name = graph.objectName("ix", spec.properties)
        assertEquals("CREATE INDEX IF NOT EXISTS $name ON g_node (JSON_VALUE(props, '$.name'), JSON_VALUE(props, '$.\"metadata.source\"'))", index)
        assertFalse(index.contains("Person"), "a range index does not depend on the label")

        val labelled = graph.objectName("lx", listOf("Person") + spec.properties)
        val person = "JSON_EXISTS(n.labels, '$[*]?(@ == \"Person\")')"
        val ddl = "CREATE INDEX IF NOT EXISTS $labelled ON g_node n (CASE WHEN $person THEN JSON_VALUE(n.props, '$.name') END, " +
            "CASE WHEN $person THEN JSON_VALUE(n.props, '$.\"metadata.source\"') END)"
        // Made unless a uniqueness constraint's index over the one property stands for it.
        labelledIndex.inOrder(
            "SELECT COUNT(*) INTO v_unique FROM g_schema u WHERE u.label = 'Person' AND u.properties = '[\"name\",\"metadata.source\"]' AND u.kind = 'UNIQUENESS_CONSTRAINT'",
            "IF v_unique = 0 THEN",
            "EXECUTE IMMEDIATE ${OracleSql.literal(ddl)};",
            "END IF;",
        )
        assertTrue(ddl.contains(OracleSql.labelled("n", "Person", "name")), "a filter reads the property as the index does")

        assertTrue(record.startsWith("BEGIN\n"))
        assertTrue(record.endsWith("\nEND;"))
        record.inOrder(
            "MERGE INTO g_schema c",
            "USING (SELECT 'RANGE_INDEX' AS kind, 'Person' AS label, '[\"name\",\"metadata.source\"]' AS properties FROM dual) k",
            "ON (c.kind = k.kind AND c.label = k.label AND c.properties = k.properties)",
            // A record made before there were labelled indexes takes the name of the one now made.
            "WHEN MATCHED THEN UPDATE SET c.details = '{\"labelled\": \"$labelled\"}'",
            "WHEN NOT MATCHED THEN INSERT (kind, label, properties, name, object_name, details)",
            "VALUES (k.kind, k.label, k.properties, 'Person_name_metadata.source_range', '$name', '{\"labelled\": \"$labelled\"}');",
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
            "IF INSTR('','' || LTRIM(RTRIM(JSON_SERIALIZE(:NEW.labels), '']''), ''['') || '',\"'', '',\"Doc\",\"'') > 0 AND JSON_EXISTS(:NEW.props, ''$.embedding'') THEN",
            "MERGE INTO $side s USING (SELECT :NEW.nid AS nid, TO_VECTOR(JSON_QUERY(:NEW.props, ''$.embedding'' RETURNING CLOB)) AS embedding FROM dual) n ON (s.nid = n.nid)",
            "EXCEPTION WHEN OTHERS THEN",
            "DELETE FROM $side WHERE nid = :NEW.nid;",
            "ELSE",
            "DELETE FROM $side WHERE nid = :NEW.nid;",
        )

        assertTrue(backfill.startsWith("INSERT INTO $side (nid, embedding)\nSELECT n.nid, TO_VECTOR(JSON_QUERY(n.props, '$.embedding' RETURNING CLOB)) FROM g_node n"), backfill)
        backfill.inOrder(
            "WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n.labels), ']'), '[') || ',\"', ',\"Doc\",\"') > 0 AND JSON_EXISTS(n.props, '$.embedding')",
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
        val texts = checked(grammar.createConstraint(spec))
        // Over several properties, the range index is made whole first: no unique index stands for its labelled index.
        assertEquals(checked(grammar.createIndex(RangeIndexSpec("Person", listOf("email", "tenant")), null)), texts.take(3))
        val (index, record) = texts.drop(3)

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
    fun `dropping a vector or full-text index forgets it, and drops the table the record names with its trigger`() {
        val vector = grammar.dropIndex(SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"))).texts().single()
        vector.inOrder(
            "DECLARE",
            "SELECT MAX(object_name), MAX(JSON_VALUE(details, '$.labelled')) INTO v_object, v_labelled",
            "FROM g_schema WHERE kind = 'VECTOR_INDEX' AND label = 'Doc' AND properties = '[\"embedding\"]';",
            "SELECT COUNT(*) INTO v_others FROM g_schema WHERE object_name = v_object AND NOT (kind = 'VECTOR_INDEX'",
            "IF v_object IS NOT NULL AND v_others = 0 THEN",
            "EXECUTE IMMEDIATE 'DROP TRIGGER IF EXISTS ' || v_object || '_t';",
            "EXECUTE IMMEDIATE 'DROP TABLE IF EXISTS ' || v_object || ' PURGE';",
            "END IF;",
            // Forgotten once dropped: a drop that fails leaves the record naming what is still there.
            "DELETE FROM g_schema WHERE kind = 'VECTOR_INDEX'",
            "COMMIT;",
            "END;",
        )
        assertFalse("DROP INDEX" in vector, "the index over the table goes with the table")

        val text = grammar.dropIndex(SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Doc", listOf("title", "body"))).texts().single()
        text.inOrder(
            "WHERE kind = 'FULLTEXT_INDEX' AND label = 'Doc' AND properties = '[\"title\",\"body\"]'",
            "IF v_object IS NOT NULL AND v_others = 0 THEN",
            "EXECUTE IMMEDIATE 'DROP TRIGGER IF EXISTS ' || v_object || '_t';",
            "EXECUTE IMMEDIATE 'DROP TABLE IF EXISTS ' || v_object || ' PURGE';",
        )

        listOf(vector, text).forEach { assertEquals(emptyList(), SqlParameters.positional(it).names) }
    }

    @Test
    fun `what is dropped is named by the record alone, so an item is dropped as it was made and not as it would be named now`() {
        val drops = listOf(
            grammar.dropIndex(SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"))),
            grammar.dropIndex(SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Doc", listOf("title", "body"))),
            grammar.dropIndex(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"))),
            grammar.dropConstraint(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"))),
        ).map { it.texts().single() }
        val made = listOf(
            graph.vectors("Doc", "embedding"),
            graph.texts("Doc", listOf("title", "body")),
            graph.objectName("ix", listOf("name")),
            graph.objectName("uq", listOf("Person", "email")),
        )

        drops.zip(made).forEach { (drop, name) -> assertFalse(name in drop, "'$name' is written into:\n$drop") }
        assertFalse(graph.objectName("lx", listOf("Person", "name")) in drops[2], "a labelled index is dropped by the name its record holds")
    }

    @Test
    fun `dropping a range index or a constraint forgets it, and drops the index the record names when nothing else recorded uses it`() {
        val range = grammar.dropIndex(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"))).texts()
        assertEquals(1, range.size)
        range.single().inOrder(
            "DECLARE",
            "FROM g_schema WHERE kind = 'RANGE_INDEX' AND label = 'Person' AND properties = '[\"name\"]';",
            // The labelled index is the record's own, and goes with it whatever else is recorded.
            "IF v_labelled IS NOT NULL THEN",
            "EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;",
            "END IF;",
            "IF v_object IS NOT NULL AND v_others = 0 THEN",
            "EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_object;",
            "END IF;",
            // Each drop commits what came before it: the record is forgotten last, so one that fails leaves it to be dropped again.
            "DELETE FROM g_schema WHERE kind = 'RANGE_INDEX' AND label = 'Person' AND properties = '[\"name\"]';",
            "COMMIT;",
        )
        assertFalse("DROP TABLE" in range.single() || "DROP TRIGGER" in range.single())

        val constraint = grammar.dropConstraint(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"))).texts()
        assertEquals(1, constraint.size)
        constraint.single().inOrder(
            "WHERE kind = 'UNIQUENESS_CONSTRAINT' AND label = 'Person' AND properties = '[\"email\"]'",
            "EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_object;",
        )
    }

    @Test
    fun `a drop is one statement with no indentation left over from where it was written`() {
        val drop = grammar.dropIndex(SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"))).texts().single()

        assertTrue(drop.startsWith("DECLARE\n    v_object VARCHAR2(128);"), drop)
        assertTrue(drop.endsWith("\n    END IF;\n    DELETE FROM g_schema WHERE kind = 'VECTOR_INDEX' AND label = 'Doc' AND properties = '[\"embedding\"]';\n    COMMIT;\nEND;"), drop)
        assertTrue("\n        EXECUTE IMMEDIATE 'DROP TRIGGER IF EXISTS ' || v_object || '_t';\n        EXECUTE IMMEDIATE 'DROP TABLE IF EXISTS ' || v_object || ' PURGE';\n" in drop, drop)
    }

    @Test
    fun `what is listed is what was recorded and is still there`() {
        val indexes = grammar.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        indexes.inOrder(
            "SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details,",
            // A range index with neither its labelled index nor a unique index to stand for it is listed, as incomplete.
            "'status' VALUE CASE WHEN kind = 'RANGE_INDEX' AND NOT EXISTS (SELECT 1 FROM user_indexes l WHERE l.index_name = UPPER(JSON_VALUE(c.details, '$.labelled')))",
            "AND NOT EXISTS (SELECT 1 FROM g_schema u WHERE u.label = c.label AND u.properties = c.properties AND u.kind = 'UNIQUENESS_CONSTRAINT' AND JSON_VALUE(u.properties, '$.size()' RETURNING NUMBER) = 1",
            "THEN 'INCOMPLETE' END",
            "RETURNING JSON)",
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
            "SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT('email' VALUE k0, 'tenant' VALUE k1), 'count' VALUE MAX(c),",
            "'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT('elementId' VALUE nid) ORDER BY nid RETURNING JSON) RETURNING JSON)",
            "FROM (SELECT n.nid, JSON_VALUE(n.props, '$.email') AS k0, JSON_VALUE(n.props, '$.tenant') AS k1, " +
                "COUNT(*) OVER (PARTITION BY JSON_VALUE(n.props, '$.email'), JSON_VALUE(n.props, '$.tenant')) AS c, " +
                "ROW_NUMBER() OVER (PARTITION BY JSON_VALUE(n.props, '$.email'), JSON_VALUE(n.props, '$.tenant') ORDER BY n.nid) AS r",
            "FROM g_node n",
            "WHERE JSON_EXISTS(n.labels, '$[*]?(@ == \"Person\")') AND JSON_VALUE(n.props, '$.email') IS NOT NULL AND JSON_VALUE(n.props, '$.tenant') IS NOT NULL)",
            "WHERE c > 1 AND r <= 5",
            "GROUP BY k0, k1",
            "FETCH FIRST 5 ROWS ONLY",
        )
        assertEquals(emptyList(), SqlParameters.positional(query).names)
    }

    @Test
    fun `a conflicting node is named by the fragment's id too, unless that is a key`() {
        val byUuid = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "uuid"), 3)
        byUuid.inOrder(
            "'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT('elementId' VALUE nid, 'uuid' VALUE i) ORDER BY nid RETURNING JSON)",
            "FROM (SELECT n.nid, JSON_VALUE(n.props, '$.email') AS k0, JSON_VALUE(n.props, '$.uuid') AS i, COUNT(*) OVER",
        )
        assertEquals(emptyList(), SqlParameters.positional(byUuid).names)

        val byKey = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "email"), 3)
        byKey.inOrder(
            "'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT('elementId' VALUE nid) ORDER BY nid RETURNING JSON)",
            "FROM (SELECT n.nid, JSON_VALUE(n.props, '$.email') AS k0, COUNT(*) OVER",
        )
    }

    @Test
    fun `the properties of an index are recorded as a JSON array, with quotes and backslashes escaped`() {
        assertEquals("[]", OracleSchemaGrammar.json(emptyList()))
        assertEquals("[\"a\\\"b\",\"c\\\\d\"]", OracleSchemaGrammar.json(listOf("a\"b", "c\\d")))
    }

    @Test
    fun `a uniqueness constraint over one property stands for the labelled index of its range index, which is recorded as served by it`() {
        val spec = UniquenessConstraintSpec("Person", "email")
        val (shared, range, unique, served, record) = checked(grammar.createConstraint(spec))

        val ix = graph.objectName("ix", listOf("email"))
        val uq = graph.objectName("uq", listOf("Person", "email"))
        assertEquals("CREATE INDEX IF NOT EXISTS $ix ON g_node (JSON_VALUE(props, '$.email'))", shared)
        assertFalse("lx" in range || "CASE WHEN" in range, "no labelled index is made:\n$range")
        range.inOrder("'RANGE_INDEX' AS kind, 'Person' AS label, '[\"email\"]' AS properties", "'$ix', '{\"unique\": \"$uq\"}'")
        assertFalse("WHEN MATCHED THEN UPDATE" in range, "a range index there already is changed only once the unique index is made")
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS $uq ON g_node n (${OracleSql.unique("n", "Person", listOf("email"), "email")})", unique)
        served.inOrder(
            "SELECT MAX(JSON_VALUE(details, '$.labelled')) INTO v_labelled FROM g_schema WHERE kind = 'RANGE_INDEX' AND label = 'Person' AND properties = '[\"email\"]';",
            // Dropped before the record stops naming it: a drop that fails leaves it named, to be dropped next time.
            "IF v_labelled IS NOT NULL THEN",
            "EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;",
            "END IF;",
            "UPDATE g_schema SET details = '{\"unique\": \"$uq\"}' WHERE kind = 'RANGE_INDEX'",
            "COMMIT;",
        )
        record.inOrder("'UNIQUENESS_CONSTRAINT' AS kind, 'Person' AS label", "'$uq', '{}'")
    }

    @Test
    fun `the indexes a filter is read through are the labelled indexes there, and the unique indexes over one property there`() {
        val query = grammar.labelledIndexesQuery()
        query.inOrder(
            "SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties) RETURNING JSON)",
            "FROM g_schema u",
            "WHERE (u.kind = 'UNIQUENESS_CONSTRAINT' AND JSON_VALUE(u.properties, '$.size()' RETURNING NUMBER) = 1 AND EXISTS (SELECT 1 FROM user_indexes q WHERE q.index_name = UPPER(u.object_name)))",
            "OR (kind = 'RANGE_INDEX' AND EXISTS (SELECT 1 FROM user_indexes l WHERE l.index_name = UPPER(JSON_VALUE(u.details, '$.labelled'))))",
        )
        assertEquals(emptyList(), SqlParameters.positional(query).names)
    }

    @Test
    fun `a range index listed as incomplete is made whole by ensure, and nothing else is`() {
        assertTrue(grammar.isIncomplete(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), status = OracleSchemaGrammar.INCOMPLETE)))
        assertFalse(grammar.isIncomplete(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"))))
        val row = mapOf("kind" to "RANGE_INDEX", "label" to "Person", "properties" to listOf("name"), "name" to null, "details" to emptyMap<String, Any?>(), "status" to "INCOMPLETE")
        assertEquals(OracleSchemaGrammar.INCOMPLETE, grammar.parseIndexRows(listOf(row)).single().status)
    }

    @Test
    fun `the grammar says what it is`() {
        assertEquals("Oracle", grammar.engine)
        assertTrue(grammar.supportsIfNotExists)
        assertTrue(grammar.supportsNamedItems)
        // The constraint makes its own range index, without a labelled index where its unique index stands for one.
        assertFalse(grammar.constraintsRequireBackingIndex)
    }
}
