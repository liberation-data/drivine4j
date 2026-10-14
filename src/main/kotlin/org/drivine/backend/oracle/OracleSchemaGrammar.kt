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
import org.drivine.schema.StoreStampStatements
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.VersionMarkerStatements
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by Oracle. A property is a path into a JSON column, so
 * each is an index over an expression:
 *
 * - a range index is two indexes. One reads the property as text, the expression a save and a load
 *   by id find a node by, testing the label on what they find. It is over the property whatever the
 *   label, so two labels that index a property of one name share it. The other, the labelled index,
 *   reads the property of the nodes that carry the label and is null for every other node (see
 *   [OracleSql.labelled]): a filter on the nodes of the label is read through it, so that one index
 *   answers both the label and the property, and Oracle is not left to choose between the index over
 *   labels and the one over the property, which it does badly;
 * - a uniqueness constraint is a unique index over the property of the nodes that carry the label,
 *   and null for every other node, which a unique index does not hold (see [OracleSql.unique]). It
 *   comes with the range index a node is found by. Over one property, its index is the same value
 *   as a labelled index would be, so it stands for one: a filter on the property is read through
 *   it, and no labelled index is made beside it.
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

    /**
     * A node is found by the range index's expression, and the unique index only refuses a second
     * one; but the range index is made by [createConstraint] itself, which knows a labelled index is
     * not wanted beside a unique index over one property.
     */
    override val constraintsRequireBackingIndex = false

    /** A range index made before there were labelled indexes, with neither one nor a uniqueness constraint's index to stand for it: see [listed]. */
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = existing.status == INCOMPLETE

    /** What is kept of the catalog to write a read with is read again: see [LabelledIndexCatalog]. */
    override fun changed() = LabelledIndexCatalog.changed(graph.name)

    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements = OracleVersionMarker(graph, label, scopeProperty)

    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements = OracleStoreStamp(graph, label, keyProperty)

    /** [text] as a schema statement. */
    private fun statement(text: String): SchemaStatement = SchemaStatement.Cypher(text.trimIndent())

    /** [text] as schema statements, after the one that makes the store when it is not there. */
    private fun statements(vararg text: String) = (listOf(graph.ensuring) + text).map(::statement)

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is
     * recorded already; with [updated], a record already there takes [details]. A `MERGE` does not
     * read again before it inserts, so of two processes recording it at once the second is refused on
     * the catalog's key; that refusal is taken as recorded, and never read as the constraint itself
     * violated.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}", updated: Boolean = false): String = """
        BEGIN
            MERGE INTO ${graph.catalog} c
            USING (SELECT ${literal(spec.kind.name)} AS kind, ${literal(spec.label)} AS label, ${literal(json(spec.properties))} AS properties FROM dual) k
            ON (c.kind = k.kind AND c.label = k.label AND c.properties = k.properties)${if (updated) "\n            WHEN MATCHED THEN UPDATE SET c.details = ${literal(details)}" else ""}
            WHEN NOT MATCHED THEN INSERT (kind, label, properties, name, object_name, details)
                VALUES (k.kind, k.label, k.properties, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)});
        EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
            -- Another process recorded it between the MERGE's read and its insert: it is recorded.
            NULL;
        END;
    """

    /**
     * Forgets [item], and drops what it was made as when nothing else recorded uses it: an index, or
     * for a vector or a full-text index the table beside the nodes with its trigger, which takes the
     * index over it along. What is dropped is the object the record names, and not one named again
     * from the label and the properties, so an item is dropped as it was made whatever a later
     * version would call it. An item nothing was made for, a full-text index where there is no
     * Oracle Text, records no object and has none dropped. A range index's labelled index is its
     * own, and is dropped with the record.
     */
    private fun forgotten(item: SchemaItemInfo): SchemaStatement {
        val drops = when (item.kind) {
            SchemaItemKind.VECTOR_INDEX, SchemaItemKind.FULLTEXT_INDEX -> OracleSideTable.dropping("v_object")
            else -> listOf("'DROP INDEX IF EXISTS ' || v_object")
        }
        val labelled = if (item.kind != SchemaItemKind.RANGE_INDEX) "" else """
                IF v_labelled IS NOT NULL THEN
                    EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;
                END IF;"""
        val record = "kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}"
        // Dropped first and forgotten last: a drop commits what came before it, and one that fails
        // (another session writing the table) then leaves the record naming what is still there,
        // for the next drop to find. A record naming what is gone is listed as not there.
        return statement(
            """
            DECLARE
                v_object VARCHAR2(128);
                v_labelled VARCHAR2(128);
                v_others NUMBER;
            BEGIN
                SELECT MAX(object_name), MAX(JSON_VALUE(details, '$.labelled')) INTO v_object, v_labelled
                FROM ${graph.catalog} WHERE $record;
                SELECT COUNT(*) INTO v_others FROM ${graph.catalog} WHERE object_name = v_object AND NOT ($record);$labelled
                IF v_object IS NOT NULL AND v_others = 0 THEN
                    ${drops.joinToString("\n                    ") { "EXECUTE IMMEDIATE $it;" }}
                END IF;
                DELETE FROM ${graph.catalog} WHERE $record;
                COMMIT;
            END;
            """
        )
    }

    companion object {
        /** The status a range index is listed with when it lacks what this version makes for it. */
        const val INCOMPLETE = "INCOMPLETE"

        /**
         * The properties of an index as the record of it holds them: part of the catalog's key, so
         * written here, byte for byte the same each time, and not by a JSON library whose spacing
         * or escaping might change.
         */
        fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is RangeIndexSpec -> statements(*rangeIndex(spec).toTypedArray())
        is VectorIndexSpec -> vectorIndex(spec)
        is FullTextIndexSpec -> statements(textIndex(spec))
    }

    /** The index over the property of every node, whatever the label: what a save and a load by id find a node by. */
    private fun sharedIndex(spec: RangeIndexSpec): Pair<String, String> {
        val name = graph.objectName("ix", spec.properties)
        val expressions = spec.properties.joinToString(", ") { "JSON_VALUE(props, ${path(it)})" }
        return name to "CREATE INDEX IF NOT EXISTS $name ON ${graph.nodes} ($expressions)"
    }

    /**
     * A range index: the index over the property of every node, and the labelled index. It is
     * recorded with the name of its labelled index among its details, and a record already there
     * takes them: one made before there were labelled indexes is listed as incomplete (see
     * [listed]), so `ensure` makes it again, and its labelled index with it.
     */
    private fun rangeIndex(spec: RangeIndexSpec): List<String> {
        val (name, shared) = sharedIndex(spec)
        val labelled = graph.objectName("lx", listOf(spec.label) + spec.properties)
        val scoped = spec.properties.joinToString(", ") { OracleSql.labelled("n", spec.label, it) }
        // Not where a uniqueness constraint's index over the one property stands for it, as when the
        // range index is made again under a constraint: the record then names an index that is not
        // there, and the unique index is read through.
        val standing = "SELECT COUNT(*) INTO v_unique FROM ${graph.catalog} u WHERE u.label = ${literal(spec.label)} AND u.properties = ${literal(json(spec.properties))} AND $uniqueThere"
        return listOf(
            shared,
            """
            DECLARE
                v_unique NUMBER;
            BEGIN
                $standing;
                IF v_unique = 0 THEN
                    EXECUTE IMMEDIATE ${literal("CREATE INDEX IF NOT EXISTS $labelled ON ${graph.nodes} n ($scoped)")};
                END IF;
            END;
            """,
            recorded(spec, name, "{\"labelled\": \"$labelled\"}", updated = true),
        )
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = listOf(forgotten(item))

    /**
     * A vector index. Oracle indexes a `VECTOR` column, and an embedding is a list among a node's
     * JSON properties, so the embeddings of the label's nodes are kept beside them in a table of
     * their own (see [OracleSideTable]). The index is over that table, by neighbour partitions, which
     * needs no memory set aside in the database as a neighbour graph does. The table is filled before
     * the index is made, so the partitions are trained on the embeddings there are.
     */
    private fun vectorIndex(spec: VectorIndexSpec): List<SchemaStatement> {
        val property = spec.properties.single()
        val side = OracleSideTable(
            name = graph.vectors(spec.label, property),
            nodes = graph.nodes,
            column = "embedding",
            columnType = "VECTOR(${spec.dimensions}, FLOAT32)",
            payload = { row -> "TO_VECTOR(JSON_QUERY($row.props, ${path(property)} RETURNING CLOB))" },
            indexed = { row -> "${OracleSql.carriesLabel(row, spec.label)} AND JSON_EXISTS($row.props, ${path(property)})" },
            backfillFilter = { row -> "VECTOR_DIMENSION_COUNT(TO_VECTOR(JSON_QUERY($row.props, ${path(property)} RETURNING CLOB))) = ${spec.dimensions}" },
            tolerant = true,
        )
        return statements(
            side.create,
            side.triggerStatement,
            side.backfill,
            "CREATE VECTOR INDEX IF NOT EXISTS ${side.index} ON ${side.name} (embedding) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE ${OracleSql.metric(spec.similarity)}",
            recorded(spec, side.name, """{"dimensions": ${spec.dimensions}, "similarity": "${spec.similarity.name}"}"""),
        )
    }

    /**
     * A full-text index, where the database has Oracle Text. The text of the label's nodes is kept
     * beside them in a table of its own (see [OracleSideTable]), as a vector index keeps embeddings,
     * and the Oracle Text index is over that table.
     *
     * It is not over the node table, though it could be. An Oracle Text index holds what has been
     * committed, and once one is there Oracle answers ordinary tests of a JSON property from it: a save
     * then cannot see a node it made a moment before in the same statement, and makes it again.
     *
     * A database without Oracle Text cannot make the index. Nothing is made there, the index is
     * recorded, and a search reads the text of every node of the label.
     *
     * Table, index, trigger and backfill are each made when they are not there, so a run that stopped
     * short, or that another process ran at the same moment, is finished rather than repeated.
     */
    private fun textIndex(spec: FullTextIndexSpec): String {
        val side = OracleSideTable(
            name = graph.texts(spec.label, spec.properties),
            nodes = graph.nodes,
            column = "doc",
            columnType = "JSON",
            payload = { row ->
                spec.properties.joinToString(", ", "JSON_OBJECT(", " ABSENT ON NULL RETURNING JSON)") {
                    "${literal(it)} VALUE JSON_VALUE($row.props, ${path(it)} RETURNING CLOB)"
                }
            },
            indexed = { row -> OracleSql.carriesLabel(row, spec.label) },
        )
        val details = spec.analyzer?.let { "{\"analyzer\": ${json(listOf(it)).removeSurrounding("[", "]")}}" } ?: "{}"
        return """
            DECLARE
                v_indexed NUMBER;
                e_no_text EXCEPTION;
                e_exists EXCEPTION;
                PRAGMA EXCEPTION_INIT(e_no_text, -29833);
                PRAGMA EXCEPTION_INIT(e_exists, -955);
            BEGIN
                EXECUTE IMMEDIATE ${literal(side.create)};
                SELECT COUNT(*) INTO v_indexed FROM user_indexes WHERE index_name = '${side.index.uppercase()}';
                IF v_indexed = 0 THEN
                    BEGIN
                        EXECUTE IMMEDIATE ${literal("CREATE SEARCH INDEX ${side.index} ON ${side.name} (doc) FOR JSON PARAMETERS ('SYNC (ON COMMIT)')")};
                    EXCEPTION
                        WHEN e_exists THEN
                            NULL; -- made by another process at this moment
                        WHEN e_no_text THEN
                            EXECUTE IMMEDIATE 'DROP TABLE ${side.name} PURGE';
                            ${recorded(spec, null, details).trimIndent().replace("\n", "\n                            ")}
                            RETURN;
                    END;
                END IF;
                EXECUTE IMMEDIATE ${literal(side.triggerDdl)};
                EXECUTE IMMEDIATE ${literal(side.backfill)};
                ${recorded(spec, side.name, details).trimIndent().replace("\n", "\n                ")}
            END;
        """
    }

    /**
     * A uniqueness constraint, with the range index a node is found by. Over several properties the
     * range index is made whole. Over one, the unique index stands for its labelled index: the range
     * index is recorded as served by it, and a labelled index made for it before is dropped, once the
     * unique index is there. Data the constraint refuses stops the statements at the unique index,
     * and the range index is left incomplete, to be made whole by an `ensure` of it.
     */
    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = graph.objectName("uq", listOf(spec.label) + spec.properties)
            // A node that lacks any of the properties is not constrained, as on the graph engines.
            val expressions = spec.properties.joinToString(", ") { OracleSql.unique("n", spec.label, spec.properties, it) }
            val unique = "CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${graph.nodes} n ($expressions)"
            val range = RangeIndexSpec(spec.label, spec.properties)
            if (spec.properties.size > 1) {
                statements(*rangeIndex(range).toTypedArray(), unique, recorded(spec, name))
            } else {
                val (shared, index) = sharedIndex(range)
                val served = "{\"unique\": \"$name\"}"
                statements(index, recorded(range, shared, served), unique, servedBy(range, served), recorded(spec, name))
            }
        }
    }

    /**
     * Records the range index [spec] as served by a uniqueness constraint's index, [details], and
     * drops the labelled index it was made with, when it was. The index is dropped first: a drop that
     * fails (another session writing the table) leaves the record naming it, for the next
     * constraint made to find and drop.
     */
    private fun servedBy(spec: RangeIndexSpec, details: String): String {
        val record = "kind = ${literal(spec.kind.name)} AND label = ${literal(spec.label)} AND properties = ${literal(json(spec.properties))}"
        return """
            DECLARE
                v_labelled VARCHAR2(128);
            BEGIN
                SELECT MAX(JSON_VALUE(details, '$.labelled')) INTO v_labelled FROM ${graph.catalog} WHERE $record;
                IF v_labelled IS NOT NULL THEN
                    EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;
                END IF;
                UPDATE ${graph.catalog} SET details = ${literal(details)} WHERE $record;
                COMMIT;
            END;
        """
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(forgotten(item))

    /** Whether the labelled index the record `c` names is there. */
    private val labelledThere = "EXISTS (SELECT 1 FROM user_indexes l WHERE l.index_name = UPPER(JSON_VALUE(c.details, '$.labelled')))"

    /** Whether the record `u` is of a uniqueness constraint over one property whose index is there: an index that stands for a labelled one. */
    private val uniqueThere = "u.kind = ${literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name)} AND JSON_VALUE(u.properties, '$.size()' RETURNING NUMBER) = 1 " +
        "AND EXISTS (SELECT 1 FROM user_indexes q WHERE q.index_name = UPPER(u.object_name))"

    /**
     * What is recorded of [kinds], and still there: an index or a table beside the nodes dropped by
     * hand is not listed, so that it is made again when asked for. A range index with neither its
     * labelled index nor a uniqueness constraint's to stand for it, dropped by hand or made before
     * there were any, is listed as it is, [INCOMPLETE], so that `ensure` makes the rest.
     */
    private fun listed(kinds: String): String = """
        SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details,
                           'status' VALUE CASE WHEN kind = ${literal(SchemaItemKind.RANGE_INDEX.name)} AND NOT $labelledThere
                                                AND NOT EXISTS (SELECT 1 FROM ${graph.catalog} u WHERE u.label = c.label AND u.properties = c.properties AND $uniqueThere)
                                          THEN '$INCOMPLETE' END
                           RETURNING JSON)
        FROM ${graph.catalog} c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL
               OR EXISTS (SELECT 1 FROM user_indexes i WHERE i.index_name = UPPER(c.object_name))
               OR EXISTS (SELECT 1 FROM user_tables t WHERE t.table_name = UPPER(c.object_name)))
        ORDER BY label, properties
    """.trimIndent()

    /**
     * The indexes over the nodes of one label a filter can be read through, as rows of `kind`,
     * `label` and `properties`: each range index whose labelled index is there, and each uniqueness
     * constraint over one property whose index is there. See [LabelledIndexCatalog].
     */
    fun labelledIndexesQuery(): String = """
        SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties) RETURNING JSON)
        FROM ${graph.catalog} u
        WHERE ($uniqueThere)
           OR (kind = ${literal(SchemaItemKind.RANGE_INDEX.name)} AND ${labelledThere.replace("c.details", "u.details")})
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
            status = map["status"] as? String,
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
