package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.hasLabel
import org.drivine.backend.oracle.OracleSql.literal
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by Oracle. A property is a path into a JSON column, so
 * each is an index over an expression:
 *
 * - a range index reads the property as text, the expression a save finds a node by and a
 *   comparison with a string uses. It is over the property whatever the label, so two labels that
 *   index a property of one name share an index;
 * - a uniqueness constraint is a unique index over the property of the nodes that carry the label,
 *   and null for every other node, which a unique index does not hold.
 *
 * A vector index is a table of embeddings beside the nodes, and a full-text index a table of their
 * text: see [vectorIndex] and [textIndex].
 *
 * Oracle's dictionary holds the expressions and not what they were made for, so what Drivine made is
 * recorded in a table of the graph's own, and read back from there.
 */
internal class OracleSchemaGrammar(private val graph: OracleGraph) : SchemaGrammar {

    override val engine = "Oracle"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** A node is found by the range index's expression; the unique index only refuses a second one. */
    override val constraintsRequireBackingIndex = true

    private fun statements(vararg text: String) = (graph.ddl + text).map { SchemaStatement.Cypher(it.trimIndent()) }

    /** A name for a schema object: the graph's, what it is, and its properties, cut to fit with a hash that keeps it its own. */
    private fun objectName(what: String, vararg parts: String): String {
        val readable = parts.joinToString("_") { it.replace(Regex("[^A-Za-z0-9]"), "") }.take(40)
        return "${graph.name}_${what}_${readable}_${Integer.toHexString(parts.joinToString("\u0000").hashCode())}"
    }

    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}"): String {
        val key = "kind = ${literal(spec.kind.name)} AND label = ${literal(spec.label)} AND properties = ${literal(json(spec.properties))}"
        return """
            INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
            SELECT ${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)},
                   ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)}
            FROM dual WHERE NOT EXISTS (SELECT 1 FROM ${graph.catalog} WHERE $key)
        """
    }

    /** Forgets [item], and drops the object it was made as when nothing else recorded uses it. */
    private fun forgotten(item: SchemaItemInfo): String = """
        DECLARE
            v_object VARCHAR2(128);
            v_others NUMBER;
        BEGIN
            DELETE FROM ${graph.catalog}
            WHERE kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}
            RETURNING MAX(object_name) INTO v_object;
            SELECT COUNT(*) INTO v_others FROM ${graph.catalog} WHERE object_name = v_object;
            IF v_object IS NOT NULL AND v_others = 0 THEN
                EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_object;
            END IF;
        END;
    """

    private fun json(values: List<String>): String = Companion.json(values)

    companion object {
        /** The properties of an index as the record of it holds them. */
        fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName("ix", *spec.properties.toTypedArray())
            val expressions = spec.properties.joinToString(", ") { "JSON_VALUE(props, ${path(it)})" }
            statements("CREATE INDEX IF NOT EXISTS $name ON ${graph.nodes} ($expressions)", recorded(spec, name))
        }
        is VectorIndexSpec -> vectorIndex(spec)
        is FullTextIndexSpec -> statements(textIndex(spec))
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> =
        when (item.kind) {
            // The table beside the nodes takes its index with it.
            SchemaItemKind.VECTOR_INDEX -> beside(graph.vectors(item.label, item.properties.single()), item)
            SchemaItemKind.FULLTEXT_INDEX -> beside(graph.texts(item.label, item.properties), item)
            else -> listOf(SchemaStatement.Cypher(forgotten(item).trimIndent()))
        }

    private fun beside(table: String, item: SchemaItemInfo): List<SchemaStatement> =
        listOf("DROP TRIGGER IF EXISTS ${table}_t", "DROP TABLE IF EXISTS $table PURGE", forgotten(item)).map { SchemaStatement.Cypher(it.trimIndent()) }

    /**
     * A vector index. Oracle indexes a `VECTOR` column, and an embedding is a list among a node's
     * JSON properties, so the embeddings of the label's nodes are kept beside them in a table of
     * their own, which a trigger on the node table keeps in step: whatever writes a node, an object
     * manager or SQL by hand, the embedding follows. The index is over that table, by neighbour
     * partitions, which needs no memory set aside in the database as a neighbour graph does.
     */
    private fun vectorIndex(spec: VectorIndexSpec): List<SchemaStatement> {
        val property = spec.properties.single()
        val side = graph.vectors(spec.label, property)
        val embedding = "TO_VECTOR(JSON_QUERY(%s.props, ${path(property)} RETURNING CLOB))"
        val indexed = "${OracleSql.carriesLabel("%s", spec.label)} AND JSON_EXISTS(%s.props, ${path(property)})"
        val trigger = """
            CREATE OR REPLACE TRIGGER ${side}_t AFTER INSERT OR UPDATE OF labels, props ON ${graph.nodes} FOR EACH ROW
            BEGIN
                IF ${indexed.replace("%s", ":NEW")} THEN
                    MERGE INTO $side v USING (SELECT :NEW.nid AS nid, ${embedding.format(":NEW")} AS embedding FROM dual) n ON (v.nid = n.nid)
                    WHEN MATCHED THEN UPDATE SET v.embedding = n.embedding
                    WHEN NOT MATCHED THEN INSERT (nid, embedding) VALUES (n.nid, n.embedding);
                ELSE
                    DELETE FROM $side WHERE nid = :NEW.nid;
                END IF;
            END;
        """.trimIndent()
        return statements(
            """
            CREATE TABLE IF NOT EXISTS $side (
                nid       NUMBER PRIMARY KEY REFERENCES ${graph.nodes} (nid) ON DELETE CASCADE,
                embedding VECTOR(${spec.dimensions}, FLOAT32) NOT NULL
            )
            """,
            "CREATE VECTOR INDEX IF NOT EXISTS ${side}_i ON $side (embedding) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE ${OracleSql.metric(spec.similarity)}",
            // The trigger names the new row with a colon, which a driver reads as a parameter: it is made from inside a block.
            "BEGIN EXECUTE IMMEDIATE ${literal(trigger)}; END;",
            """
            INSERT INTO $side (nid, embedding)
            SELECT n.nid, ${embedding.format("n")} FROM ${graph.nodes} n
            WHERE ${indexed.replace("%s", "n")} AND NOT EXISTS (SELECT 1 FROM $side v WHERE v.nid = n.nid)
            """,
            recorded(spec, side, """{"dimensions": ${spec.dimensions}, "similarity": "${spec.similarity.name}"}"""),
        )
    }

    /**
     * A full-text index, where the database has Oracle Text. The text of the label's nodes is kept
     * beside them in a table of its own, as a vector index keeps embeddings, and the Oracle Text
     * index is over that table.
     *
     * It is not over the node table, though it could be. An Oracle Text index holds what has been
     * committed, and once one is there Oracle answers ordinary tests of a JSON property from it: a save
     * then cannot see a node it made a moment before in the same statement, and makes it again.
     *
     * A database without Oracle Text cannot make the index. Nothing is made there, the index is
     * recorded, and a search reads the text of every node of the label.
     */
    private fun textIndex(spec: FullTextIndexSpec): String {
        val side = graph.texts(spec.label, spec.properties)
        fun doc(row: String) = spec.properties.joinToString(", ", "JSON_OBJECT(", " ABSENT ON NULL RETURNING JSON)") {
            "${literal(it)} VALUE JSON_VALUE($row.props, ${path(it)} RETURNING CLOB)"
        }
        val trigger = """
            CREATE OR REPLACE TRIGGER ${side}_t AFTER INSERT OR UPDATE OF labels, props ON ${graph.nodes} FOR EACH ROW
            BEGIN
                IF ${OracleSql.carriesLabel(":NEW", spec.label)} THEN
                    MERGE INTO $side t USING (SELECT :NEW.nid AS nid, ${doc(":NEW")} AS doc FROM dual) n ON (t.nid = n.nid)
                    WHEN MATCHED THEN UPDATE SET t.doc = n.doc
                    WHEN NOT MATCHED THEN INSERT (nid, doc) VALUES (n.nid, n.doc);
                ELSE
                    DELETE FROM $side WHERE nid = :NEW.nid;
                END IF;
            END;
        """.trimIndent()
        val details = spec.analyzer?.let { """{"analyzer": "$it"}""" } ?: "{}"
        return """
            DECLARE
                v_there NUMBER;
                e_no_text EXCEPTION;
                PRAGMA EXCEPTION_INIT(e_no_text, -29833);
            BEGIN
                SELECT COUNT(*) INTO v_there FROM user_tables WHERE table_name = '${side.uppercase()}';
                IF v_there = 0 THEN
                    EXECUTE IMMEDIATE 'CREATE TABLE $side (nid NUMBER PRIMARY KEY REFERENCES ${graph.nodes} (nid) ON DELETE CASCADE, doc JSON NOT NULL)';
                    BEGIN
                        EXECUTE IMMEDIATE 'CREATE SEARCH INDEX ${side}_i ON $side (doc) FOR JSON PARAMETERS (''SYNC (ON COMMIT)'')';
                    EXCEPTION WHEN e_no_text THEN
                        EXECUTE IMMEDIATE 'DROP TABLE $side PURGE';
                        ${recorded(spec, null, details).trim()};
                        RETURN;
                    END;
                    EXECUTE IMMEDIATE ${literal(trigger)};
                    EXECUTE IMMEDIATE ${literal("INSERT INTO $side (nid, doc) SELECT n.nid, ${doc("n")} FROM ${graph.nodes} n WHERE ${OracleSql.carriesLabel("n", spec.label)}")};
                END IF;
                ${recorded(spec, side, details).trim()};
            END;
        """
    }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName("uq", spec.label, *spec.properties.toTypedArray())
            val expressions = spec.properties.joinToString(", ") { "CASE WHEN ${hasLabel("n", spec.label)} THEN JSON_VALUE(n.props, ${path(it)}) END" }
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${graph.nodes} n ($expressions)", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(SchemaStatement.Cypher(forgotten(item).trimIndent()))

    private fun listed(kinds: String): String = """
        SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details RETURNING JSON)
        FROM ${graph.catalog} WHERE kind IN ($kinds) ORDER BY label, properties
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String = listed(literal(kind.name))

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name))

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        val details = map["details"] as? Map<*, *>
        SchemaItemInfo(
            kind = SchemaItemKind.valueOf(map["kind"] as String),
            label = map["label"] as String,
            properties = (map["properties"] as List<*>).map { it.toString() },
            name = map["name"] as? String,
            dimensions = (details?.get("dimensions") as? Number)?.toInt(),
            similarity = (details?.get("similarity") as? String)?.let(SimilarityFunction::valueOf),
            analyzer = details?.get("analyzer") as? String,
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.errorCode in setOf(1, 1452) }

    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { "JSON_VALUE(n.props, ${path(it)})" }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${literal(property)} VALUE $key" }
        return """
            SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT($entries), 'count' VALUE COUNT(*) RETURNING JSON)
            FROM ${graph.nodes} n
            WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            FETCH FIRST $limit ROWS ONLY
        """.trimIndent()
    }
}
