package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.query.ActorDirectorsByReleaseYear
import org.drivine.query.JavaActorDirectorsLatestFirst
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Test
import sample.flatexpand.SeqWindow2View
import sample.mapped.view.DeletableSessionShelf
import sample.mapped.view.DeletableSessionTree
import sample.mapped.view.LocationHierarchy
import sample.readpath.KeptShelfView
import sample.readpath.Shelf
import sample.readpath.ShelfView
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimStats
import sample.stateless.ClaimView
import sample.stateless.Dossier
import sample.stateless.UndeclaredAggregate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The statements [OracleSelect] writes for a read: their shape, and what they bind. No database is needed. */
class OracleSelectTest {

    private val graph = OracleGraph("g")

    private fun select(type: Class<*>, sorts: List<CollectionSortSpec> = emptyList(), depths: Map<String, Int> = emptyMap()) =
        OracleSelect(graph, type, sorts, depths)

    /** [this] with each run of whitespace one space, so a shape can be asserted across lines. */
    private fun String.squashed(): String = replace(Regex("\\s+"), " ")

    private fun count(text: String, part: String): Int = Regex(Regex.escape(part)).findAll(text).count()

    private val scansClaim = "JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")')"
    private val carriesClaim = "INSTR(JSON_SERIALIZE(n_.labels), '\"Claim\"') > 0"

    // ----- load -----

    @Test
    fun `a fragment is loaded as its document from the nodes of its label`() {
        val statement = select(Claim::class.java).load(emptyList())
        assertEquals(
            """
            SELECT n_.doc AS result
            FROM GRAPH_TABLE (g
                MATCH (n_ IS node WHERE $scansClaim)
                COLUMNS (n_.doc)) n_
            """.trimIndent(),
            statement.statement,
        )
        assertTrue(statement.bindings.isEmpty())
    }

    @Test
    fun `the page is bound, so the text is the same for every page`() {
        val statement = select(Claim::class.java).load(emptyList(), skip = 20, limit = 10)
        assertTrue(statement.statement.endsWith("\nOFFSET \$_p0 ROWS\nFETCH NEXT \$_p1 ROWS ONLY"), statement.statement)
        assertEquals(mapOf<String, Any?>("_p0" to 20, "_p1" to 10), statement.bindings)
    }

    @Test
    fun `a load by id tests the label on the node found, where a scan asks the index for it`() {
        val byId = select(Claim::class.java)
        val statement = byId.load(listOf(byId.hasId("id"))).statement
        assertTrue(statement.contains(carriesClaim), statement)
        assertFalse(statement.contains(scansClaim), statement)
        assertTrue(statement.endsWith("    COLUMNS (n_.props, n_.doc)) n_\nWHERE JSON_VALUE(n_.props, '$.id') = \$id"), statement)

        val all = select(Claim::class.java).load(emptyList()).statement
        assertTrue(all.contains(scansClaim), all)
        assertFalse(all.contains(carriesClaim), all)
    }

    @Test
    fun `predicates are joined by AND, a line each, and one written by hand is in parentheses, for it may hold an OR`() {
        val select = select(Claim::class.java)
        val statement = select.load(listOf("a = 1", select.written("n.text = 'x' OR n.text = 'y'"))).statement
        assertTrue(statement.endsWith("WHERE a = 1\n  AND (JSON_VALUE(n_.props, '$.text') = 'x' OR JSON_VALUE(n_.props, '$.text') = 'y')"), statement)
    }

    @Test
    fun `a predicate written by hand may name a column any way SQL allows, so the root then gives every column`() {
        val every = "COLUMNS (n_.nid, n_.labels, n_.props, n_.stamp, n_.doc)) n_"
        listOf("N_.STAMP IS NULL OR N_.STAMP = 'x'", "n_ . stamp IS NULL", "\"N_\".\"STAMP\" IS NULL", "n.text = 'x'").forEach { predicate ->
            val load = select(Claim::class.java)
            assertTrue(load.load(listOf(load.written(predicate))).statement.contains(every), predicate)
            val count = select(Claim::class.java)
            assertTrue(count.count(listOf(count.written(predicate))).statement.contains(every), predicate)
        }
    }

    @Test
    fun `the root gives only the columns the statement reads of it`() {
        val byId = select(Claim::class.java)
        assertTrue(byId.load(listOf(byId.hasId("id"))).statement.contains("COLUMNS (n_.props, n_.doc)) n_"))

        // A view reads the root's id to find what is related to it, and a count of every root reads nothing but that there is one.
        assertTrue(select(ClaimView::class.java).load(emptyList()).statement.endsWith("COLUMNS (claim_.nid, claim_.doc)) claim_"))
        assertTrue(select(Claim::class.java).count(emptyList()).statement.endsWith("COLUMNS (n_.nid)) n_"))
    }

    @Test
    fun `a view with a relationship that must be there loads and counts only the roots that have it`() {
        val required = """
            WHERE EXISTS (
                SELECT 1
                FROM GRAPH_TABLE (g
                    MATCH (shelf_ IS node) -[e IS edge WHERE e.type = 'KEPT_BY']-> (KEEPER IS node WHERE INSTR(JSON_SERIALIZE(KEEPER.labels), '"Keeper"') > 0)
                    COLUMNS (shelf_.nid AS src)) KEEPER
                WHERE KEEPER.src = shelf_.nid)
        """.trimIndent()
        // The projection names its rows first, so the test of the relationship is of the field's second row.
        val load = select(KeptShelfView::class.java).load(emptyList()).statement
        assertTrue(load.endsWith(required.replace("KEEPER", "keeper2_")), load)

        val count = select(KeptShelfView::class.java).count(emptyList()).statement
        assertTrue(count.startsWith("SELECT COUNT(*)\nFROM GRAPH_TABLE (g"), count)
        assertTrue(count.endsWith(required.replace("KEEPER", "keeper_")), count)

        val optional = select(ShelfView::class.java).load(emptyList()).statement
        assertFalse(optional.contains("EXISTS ("), optional)
    }

    @Test
    fun `a count has no projection and no order`() {
        val count = select(Claim::class.java).count(listOf("x = 1"))
        assertEquals(
            "SELECT COUNT(*) FROM GRAPH_TABLE (g MATCH (n_ IS node WHERE $scansClaim) COLUMNS (n_.nid)) n_ WHERE x = 1",
            count.statement.squashed(),
        )
    }

    // ----- Projection -----

    @Test
    fun `a collection is gathered as an array in the order of the relationships`() {
        val statement = select(ClaimView::class.java).load(emptyList()).statement
        // The fields are visited by name: companies before people. Each row is called by the field it is of,
        // in the pattern as in the statement, and each query sits under the line that opens it.
        assertEquals(
            """
            SELECT JSON_OBJECT(
                'claim' VALUE claim_.doc,
                'companies' VALUE COALESCE((
                    SELECT JSON_ARRAYAGG(companies_.doc ORDER BY companies_.eid RETURNING JSON)
                    FROM GRAPH_TABLE (g
                        MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (companies_ IS node WHERE INSTR(JSON_SERIALIZE(companies_.labels), '"Company"') > 0)
                        COLUMNS (claim_.nid AS src, e.eid, companies_.doc)) companies_
                    WHERE companies_.src = claim_.nid), JSON('[]')),
                'people' VALUE COALESCE((
                    SELECT JSON_ARRAYAGG(people_.doc ORDER BY people_.eid RETURNING JSON)
                    FROM GRAPH_TABLE (g
                        MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(JSON_SERIALIZE(people_.labels), '"Human"') > 0)
                        COLUMNS (claim_.nid AS src, e.eid, people_.doc)) people_
                    WHERE people_.src = claim_.nid), JSON('[]'))
                RETURNING JSON) AS result
            FROM GRAPH_TABLE (g
                MATCH (claim_ IS node WHERE JSON_EXISTS(claim_.labels, '$[*]?(@ == "Claim")'))
                COLUMNS (claim_.nid, claim_.doc)) claim_
            """.trimIndent(),
            statement,
        )
    }

    @Test
    fun `a single relationship is the first related node or null`() {
        val statement = select(ShelfView::class.java).load(emptyList()).statement
        assertTrue(
            statement.contains(
                """
                |    'keeper' VALUE (
                |        SELECT keeper_.doc
                |        FROM GRAPH_TABLE (g
                |            MATCH (shelf_ IS node) -[e IS edge WHERE e.type = 'KEPT_BY']-> (keeper_ IS node WHERE INSTR(JSON_SERIALIZE(keeper_.labels), '"Keeper"') > 0)
                |            COLUMNS (shelf_.nid AS src, e.eid, keeper_.doc)) keeper_
                |        WHERE keeper_.src = shelf_.nid
                |        ORDER BY keeper_.eid FETCH FIRST 1 ROWS ONLY)
                |    RETURNING JSON) AS result
                """.trimMargin(),
            ),
            statement,
        )
    }

    @Test
    fun `a relationship fragment is the relationship's properties with the node under its target field`() {
        val statement = select(ClaimCitations::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("JSON_ARRAYAGG(JSON_TRANSFORM(cited_.eprops, SET '$.target' = cited_.doc) ORDER BY cited_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("-[e IS edge WHERE e.type = 'CITES']->"), statement)
        // The relationship's properties are read, so its row carries them.
        assertTrue(statement.contains("COLUMNS (claim_.nid AS src, e.eid, e.props AS eprops, cited_.doc)) cited_"), statement)
    }

    @Test
    fun `a path reaches each node once, through the hops with their intermediate labels`() {
        val statement = select(ClaimEmployers::class.java).load(emptyList()).statement
        assertTrue(
            statement.contains(
                """
                |    'employers' VALUE COALESCE((
                |        SELECT JSON_ARRAYAGG(employers_.doc ORDER BY employers_.nid RETURNING JSON)
                |        FROM (
                |            SELECT reached_.src, reached_.nid, ANY_VALUE(reached_.doc) AS doc
                |            FROM GRAPH_TABLE (g
                |                MATCH (claim_ IS node)
                |                    -[e1 IS edge WHERE e1.type = 'MENTIONS']-> (m1 IS node WHERE INSTR(JSON_SERIALIZE(m1.labels), '"Human"') > 0)
                |                    -[e2 IS edge WHERE e2.type = 'WORKS_AT']-> (reached_ IS node WHERE INSTR(JSON_SERIALIZE(reached_.labels), '"Company"') > 0)
                |                COLUMNS (claim_.nid AS src, reached_.nid, reached_.doc)) reached_
                |            GROUP BY reached_.src, reached_.nid
                |        ) employers_
                |        WHERE employers_.src = claim_.nid), JSON('[]')),
                """.trimMargin(),
            ),
            statement,
        )
        // Two hops give no single relationship to carry.
        assertFalse(statement.substringBefore("'people'").contains(".eid"), statement)
    }

    @Test
    fun `a path ordered by a hop reads the order from the node that hop reaches, by kind and then by value`() {
        val statement = select(ActorDirectorsByReleaseYear::class.java).load(emptyList()).statement.squashed()
        // The node the ordered hop reaches is m1, and its property's name is quoted as a JSON path step.
        assertTrue(statement.contains("JSON_VALUE(m1.props, '$.\"release year\".numberOnly()' RETURNING NUMBER) AS order_number"), statement)
        assertTrue(statement.contains("JSON_VALUE(m1.props, '$.\"release year\"') AS order_text"), statement)
        assertTrue(statement.contains("JSON_EXISTS(m1.props, '$.\"release year\"?(@ == \"\")')"), statement)
        assertTrue(
            statement.contains(
                "ORDER BY directors_.order_kind ASC NULLS LAST, " +
                    "CASE WHEN directors_.order_kind = 1 THEN directors_.order_number END ASC, " +
                    "CASE WHEN directors_.order_kind = 0 THEN directors_.order_text END ASC NULLS FIRST, directors_.nid",
            ),
            statement,
        )
    }

    @Test
    fun `a path ordered highest first keeps the highest value of each node, and an empty text last among the texts`() {
        val statement = select(JavaActorDirectorsLatestFirst::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("MAX(reached_.order_number) AS order_number"), statement)
        assertTrue(statement.contains("CASE WHEN directors_.order_kind = 0 THEN directors_.order_text END DESC NULLS LAST, directors_.nid"), statement)
    }

    @Test
    fun `a path is read from the root outward, and never as a test of every node of the graph`() {
        val statement = select(ClaimEmployers::class.java).load(emptyList()).statement.squashed()
        // The nodes a path reaches come from the path's own rows. Read on their own, as those whose id
        // the path reaches, Oracle reads every node of the graph and asks the path of each.
        assertFalse(statement.contains("MATCH (employers_ IS node)"), statement)
        assertFalse(statement.contains(".nid IN ("), statement)
    }

    @Test
    fun `a flat list read to a depth is one step taken up to that many times`() {
        val statement = select(SeqWindow2View::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("(node_ IS node) -[e IS edge WHERE e.type = 'NEXT']->{1,2} (reached_ IS node"), statement)
        assertTrue(statement.contains("'following' VALUE COALESCE(( SELECT JSON_ARRAYAGG(following_.doc ORDER BY following_.nid RETURNING JSON)"), statement)
        // Read from the root outward as a path is, each node once: never as a test of every node of the graph.
        assertTrue(statement.contains("SELECT reached_.src, reached_.nid, ANY_VALUE(reached_.doc) AS doc"), statement)
        assertTrue(statement.contains("GROUP BY reached_.src, reached_.nid ) following_ WHERE following_.src = node_.nid"), statement)
        assertFalse(statement.contains(".nid IN ("), statement)
    }

    @Test
    fun `a recursive view nests itself to its depth, where the field is empty`() {
        val statement = select(LocationHierarchy::class.java, depths = mapOf("subLocations" to 2)).load(emptyList()).statement
        assertEquals(3, count(statement, "'subLocations' VALUE"), statement)
        assertEquals(2, count(statement, "e.type = 'HAS_LOCATION'"), statement)
        assertEquals(1, count(statement, "'subLocations' VALUE JSON('[]')"), statement)
        // Each level is a row of its own, the next of the field's name, reached from the level above.
        assertTrue(statement.contains("MATCH (location_ IS node) -[e IS edge WHERE e.type = 'HAS_LOCATION']-> (subLocations_ IS node"), statement)
        assertTrue(statement.contains("MATCH (subLocations_ IS node) -[e IS edge WHERE e.type = 'HAS_LOCATION']-> (subLocations2_ IS node"), statement)
        assertTrue(statement.contains("WHERE subLocations2_.src = subLocations_.nid"), statement)
    }

    @Test
    fun `a recursive view at depth zero is not followed at all`() {
        val statement = select(LocationHierarchy::class.java, depths = mapOf("subLocations" to 0)).load(emptyList()).statement
        assertEquals(1, count(statement, "'subLocations' VALUE JSON('[]')"), statement)
        assertFalse(statement.contains("HAS_LOCATION"), statement)
    }

    @Test
    fun `a sort on a collection orders its array by the property, with the relationship to break a tie`() {
        val statement = select(ClaimView::class.java, sorts = listOf(CollectionSortSpec("people", "name", false))).load(emptyList()).statement
        assertTrue(statement.contains("JSON_ARRAYAGG(people_.doc ORDER BY JSON_VALUE(people_.props, '$.name') DESC, people_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("COLUMNS (claim_.nid AS src, e.eid, people_.props, people_.doc)) people_"), statement)
        // The other collection is untouched.
        assertTrue(statement.contains("JSON_ARRAYAGG(companies_.doc ORDER BY companies_.eid RETURNING JSON)"), statement)
    }

    @Test
    fun `a sort on a nested view's collection is applied under the relationship that leads to it`() {
        val statement = select(Dossier::class.java, sorts = listOf(CollectionSortSpec("claims_people", "name", true))).load(emptyList()).statement
        assertEquals(1, Regex("ORDER BY JSON_VALUE\\(\\w+_\\.props, '\\$\\.name'\\) ASC, \\w+_\\.eid").findAll(statement).count(), statement)
        assertTrue(statement.contains("JSON_ARRAYAGG(people_.doc ORDER BY JSON_VALUE(people_.props, '$.name') ASC, people_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("MATCH (claims_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node"), statement)
        assertTrue(statement.contains("JSON_ARRAYAGG(JSON_OBJECT("), statement)
    }

    @Test
    fun `a sort on a recursive field is applied at every level, so the tree is in one order`() {
        val statement = select(LocationHierarchy::class.java, sorts = listOf(CollectionSortSpec("subLocations", "name", true))).load(emptyList()).statement
        assertEquals(3, count(statement, "e.type = 'HAS_LOCATION'"), statement)
        assertEquals(3, Regex("ORDER BY JSON_VALUE\\(subLocations\\d?_\\.props, '\\$\\.name'\\) ASC, subLocations\\d?_\\.eid").findAll(statement).count(), statement)
    }

    @Test
    fun `a count field counts the relationships, and another aggregate reads the property as a number`() {
        val counted = select(ClaimStats::class.java).load(emptyList()).statement
        assertTrue(
            counted.contains(
                """
                |    'mentionCount' VALUE (
                |        SELECT COUNT(*)
                |        FROM GRAPH_TABLE (g
                |            MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (mentionCount_ IS node)
                |            COLUMNS (claim_.nid AS src)) mentionCount_
                |        WHERE mentionCount_.src = claim_.nid)
                """.trimMargin(),
            ),
            counted,
        )

        val averaged = select(UndeclaredAggregate::class.java).load(emptyList()).statement.squashed()
        assertTrue(averaged.contains("'averageWeight' VALUE ( SELECT AVG(JSON_VALUE(averageWeight_.props, '$.weight' RETURNING NUMBER)) FROM GRAPH_TABLE"), averaged)
    }

    // ----- cascaded -----

    @Test
    fun `a fragment cascades to nothing`() {
        assertNull(select(Claim::class.java).cascaded(emptyList()))
    }

    @Test
    fun `a cascade selects the nodes of each written relationship field, one SELECT a path`() {
        val select = select(ClaimView::class.java)
        val sql = assertNotNull(select.cascaded(listOf(select.hasId("id"))))
        // The roots are selected as a delete selects them: by the label on the node found, and the predicates.
        fun reached(row: String, label: String) = """
            SELECT $row.nid
            FROM GRAPH_TABLE (g
                MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> ($row IS node WHERE INSTR(JSON_SERIALIZE($row.labels), '"$label"') > 0)
                COLUMNS (claim_.nid AS src, $row.nid)) $row
            WHERE $row.src IN (
                SELECT claim_.nid
                FROM g_node claim_
                WHERE INSTR(JSON_SERIALIZE(claim_.labels), '"Claim"') > 0
                  AND JSON_VALUE(claim_.props, '$.id') = ${'$'}id)
        """.trimIndent()
        assertEquals(reached("cascaded_", "Company") + "\nUNION\n" + reached("cascaded2_", "Human"), sql)
    }

    @Test
    fun `a read-only field is not the view's to delete`() {
        val reviewers = assertNotNull(select(ClaimReviewers::class.java).cascaded(emptyList()))
        assertFalse(reviewers.contains("UNION"), reviewers)
        assertFalse(reviewers.contains("REVIEWED_BY"), reviewers)
        assertTrue(reviewers.contains("'MENTIONS'"), reviewers)

        val employers = assertNotNull(select(ClaimEmployers::class.java).cascaded(emptyList()))
        assertFalse(employers.contains("WORKS_AT"), employers)
    }

    @Test
    fun `a cascade follows a nested view's fields, and a recursive field to its depth`() {
        val nested = assertNotNull(select(Dossier::class.java).cascaded(emptyList()))
        assertEquals(2, count(nested, "UNION"), nested)
        assertTrue(
            nested.contains(
                "MATCH (memo_ IS node)\n" +
                    "        -[e1 IS edge WHERE e1.type = 'COVERS']-> (m1 IS node WHERE INSTR(JSON_SERIALIZE(m1.labels), '\"Claim\"') > 0)\n" +
                    "        -[e2 IS edge WHERE e2.type = 'MENTIONS']-> (cascaded2_ IS node",
            ),
            nested,
        )

        val recursive = assertNotNull(select(LocationHierarchy::class.java).cascaded(emptyList()))
        assertFalse(recursive.contains("UNION"), recursive)
        assertTrue(recursive.contains("(location_ IS node) -[e IS edge WHERE e.type = 'HAS_LOCATION']->{1,3} (cascaded_ IS node"), recursive)
    }

    @Test
    fun `each level of a recursive field holds what the root does, from the far node of its steps`() {
        val tree = assertNotNull(select(DeletableSessionTree::class.java).cascaded(emptyList()))
        // The root's messages, the parts to their depth, and the messages of every part.
        assertEquals(2, count(tree, "UNION"), tree)
        assertTrue(tree.contains("MATCH (session_ IS node)\n        <-[e1 IS edge WHERE e1.type = 'PART_OF']-{1,3} (m1 IS node"), tree)
        assertTrue(tree.contains("\n        -[e2 IS edge WHERE e2.type = 'HAS_MESSAGE']-> (cascaded3_ IS node"), tree)
        // A part's own parts are the field's next level, which its depth already takes.
        assertFalse(tree.contains("e2.type = 'PART_OF'"), tree)
    }

    @Test
    fun `a recursive field is checked one level below its depth, from where the cascade reaches it`() {
        assertEquals(emptyList(), select(ClaimView::class.java).cascadeDepthChecks(emptyList()))

        val (recursion, sql) = select(DeletableSessionShelf::class.java).cascadeDepthChecks(emptyList()).single()
        assertEquals(DeletableSessionTree::class.java.name, recursion.view)
        assertEquals("parts", recursion.field)
        assertEquals(3, recursion.step.maxDepth)
        // Reached through the shelf, past the depth: the nodes not within it, nor the trees it starts from.
        assertTrue(sql.startsWith("SELECT COUNT(DISTINCT beyond_.nid)"), sql)
        assertTrue(sql.contains("<-[e2 IS edge WHERE e2.type = 'PART_OF']-{4,4} (beyond_ IS node"), sql)
        assertTrue(sql.contains("<-[e2 IS edge WHERE e2.type = 'PART_OF']-{1,3} (cascaded"), sql)
        assertEquals(2, count(sql, "NOT IN"), sql)
    }

    // ----- relatedTo -----

    @Test
    fun `the nodes related to a node found by id are selected through the arrow of the direction`() {
        val out = select(Claim::class.java)
        assertEquals(
            """
            n_.nid IN (
                SELECT related_.nid
                FROM GRAPH_TABLE (g
                    MATCH (from_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (related_ IS node)
                    COLUMNS (from_.nid AS src, related_.nid)) related_
                WHERE related_.src IN (
                    SELECT from_.nid
                    FROM g_node from_
                    WHERE JSON_VALUE(from_.props, '$.id') = ${'$'}_fromId
                      AND INSTR(JSON_SERIALIZE(from_.labels), '"Human"') > 0))
            """.trimIndent(),
            out.relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.OUTGOING),
        )
        assertTrue(out.bindings().isEmpty())
        // The roots are now found through the relationship, so their label is tested on what is found.
        assertTrue(out.load(emptyList()).statement.contains(carriesClaim))

        assertTrue(select(Claim::class.java).relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.INCOMING).contains("<-[e IS edge WHERE e.type = 'MENTIONS']- (related_ IS node)"))
        assertTrue(select(Claim::class.java).relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.UNDIRECTED).contains(") -[e IS edge WHERE e.type = 'MENTIONS']- (related_ IS node)"))
    }

    @Test
    fun `a node with no label that relationships are read from is found by its id alone, outside the pattern`() {
        val related = select(Claim::class.java).relatedTo(emptyList(), "id", "_fromId", "TAGS", Direction.OUTGOING)

        assertTrue(related.contains("MATCH (from_ IS node) -[e IS edge WHERE e.type = 'TAGS']-> (related_ IS node)"), related)
        assertTrue(related.contains("FROM g_node from_\n        WHERE JSON_VALUE(from_.props, '$.id') = ${'$'}_fromId))"), related)
    }

    // ----- scored search -----

    @Test
    fun `a scored search filters its candidates by the threshold and returns them best first`() {
        val select = select(Claim::class.java)
        val statement = select.scored("SELECT *\nFROM candidates", listOf("x = 1"), 0.7, 5)
        assertEquals(
            """
            SELECT JSON_OBJECT(
                'value' VALUE n_.doc,
                'score' VALUE n_.score
                RETURNING JSON) AS result
            FROM (
                SELECT *
                FROM candidates
            ) n_
            WHERE n_.score >= ${'$'}_p0
              AND x = 1
            ORDER BY n_.score DESC, n_.nid
            FETCH FIRST ${'$'}_p1 ROWS ONLY
            """.trimIndent(),
            statement.statement,
        )
        assertEquals(mapOf<String, Any?>("_p0" to 0.7, "_p1" to 5), statement.bindings)
    }

    @Test
    fun `a scored search with no limit and no threshold has neither`() {
        val statement = select(Claim::class.java).scored("SELECT * FROM candidates", emptyList(), null, null)
        assertFalse(statement.statement.contains("FETCH FIRST"), statement.statement)
        assertFalse(statement.statement.contains("WHERE"), statement.statement)
        assertTrue(statement.statement.endsWith("ORDER BY n_.score DESC, n_.nid"), statement.statement)
        assertTrue(statement.bindings.isEmpty())
    }

    @Test
    fun `a scored search of a view applies the relationships it requires to the candidates`() {
        val statement = select(KeptShelfView::class.java).scored("SELECT * FROM candidates", emptyList(), null, null).statement
        assertTrue(statement.contains(") shelf_\nWHERE EXISTS (\n    SELECT 1\n    FROM GRAPH_TABLE"), statement)
        assertTrue(statement.contains("'score' VALUE shelf_.score"), statement)
    }

    @Test
    fun `the nearest by an exact measure reads every node that has the embedding`() {
        val select = select(Shelf::class.java)
        val sql = select.nearestTo("embedding", SimilarityFunction.COSINE, null, listOf(0.1f, 0.2f), 7).squashed()
        assertTrue(sql.startsWith("SELECT c.nid, c.labels, c.props, c.stamp, c.doc, (2 - VECTOR_DISTANCE(TO_VECTOR(JSON_QUERY(c.props, '$.embedding' RETURNING CLOB)), TO_VECTOR(\$_p0), COSINE)) / 2 AS score FROM GRAPH_TABLE (g MATCH (c IS node WHERE JSON_EXISTS(c.labels, '$[*]?(@ == \"Shelf\")') AND JSON_EXISTS(c.props, '$.embedding'))"), sql)
        assertTrue(sql.endsWith("ORDER BY score DESC, c.nid FETCH FIRST \$_p1 ROWS ONLY"), sql)
        assertEquals(mapOf<String, Any?>("_p0" to listOf(0.1f, 0.2f), "_p1" to 7), select.bindings())
    }

    @Test
    fun `a partition label narrows the nearest, and the Euclidean score is one over one plus the distance`() {
        val sql = select(Shelf::class.java).nearestTo("embedding", SimilarityFunction.EUCLIDEAN, "Hot", listOf(0.1f), 3).squashed()
        assertTrue(sql.contains("JSON_EXISTS(c.labels, '$[*]?(@ == \"Shelf\")') AND JSON_EXISTS(c.labels, '$[*]?(@ == \"Hot\")') AND JSON_EXISTS(c.props, '$.embedding')"), sql)
        assertTrue(sql.contains("1 / (1 + VECTOR_DISTANCE(TO_VECTOR(JSON_QUERY(c.props, '$.embedding' RETURNING CLOB)), TO_VECTOR(\$_p0), EUCLIDEAN_SQUARED)) AS score"), sql)
    }

    @Test
    fun `the nearest through an index are its approximate nearest, then tested for the root's labels`() {
        val select = select(Shelf::class.java)
        val sql = select.nearestThrough("g_v_shelf", SimilarityFunction.COSINE, listOf(0.5f), 4).squashed()
        assertTrue(sql.contains("FROM (SELECT s.nid, VECTOR_DISTANCE(s.embedding, TO_VECTOR(\$_p0), COSINE) AS distance FROM g_v_shelf s ORDER BY distance FETCH APPROX FIRST \$_p1 ROWS ONLY) k"), sql)
        assertTrue(sql.contains("JOIN g_node c ON c.nid = k.nid WHERE INSTR(JSON_SERIALIZE(c.labels), '\"Shelf\"') > 0"), sql)
        assertTrue(sql.contains("(2 - k.distance) / 2 AS score"), sql)
        assertTrue(sql.contains("JSON_TRANSFORM(c.props, SET '$.labels' = c.labels, SET '$.\"__drivine.stamp\"' = c.stamp) AS doc"), sql)
        assertEquals(mapOf<String, Any?>("_p0" to listOf(0.5f), "_p1" to 4), select.bindings())
    }

    @Test
    fun `a text search without an index counts each term on its own and keeps the required and excluded`() {
        val select = select(Shelf::class.java)
        val sql = select.matching(listOf("title"), TextQuery.parse("graph +data -noise")).squashed()
        assertTrue(sql.contains("REGEXP_COUNT(s.text, \$_p0) + REGEXP_COUNT(s.text, \$_p1) AS score"), sql)
        assertTrue(sql.contains("LOWER(JSON_VALUE(c.props, '$.title' RETURNING CLOB)) AS text"), sql)
        assertTrue(sql.contains(") s WHERE REGEXP_COUNT(s.text, \$_p2) > 0 AND REGEXP_COUNT(s.text, \$_p3) = 0 ) h WHERE h.score > 0"), sql)
        assertTrue(sql.contains("h.score / MAX(h.score) OVER () AS score"), sql)
        assertTrue(sql.contains("MATCH (c IS node WHERE JSON_EXISTS(c.labels, '$[*]?(@ == \"Shelf\")'))"), sql)
        val bound = select.bindings()
        assertEquals(listOf("_p0", "_p1", "_p2", "_p3"), bound.keys.toList())
        assertEquals("(^|[^[:alnum:]_])graph($|[^[:alnum:]_])", bound["_p0"])
        assertEquals(bound["_p1"], bound["_p2"])
        assertEquals("(^|[^[:alnum:]_])noise($|[^[:alnum:]_])", bound["_p3"])
    }

    @Test
    fun `a text search with several properties joins their text`() {
        val sql = select(Shelf::class.java).matching(listOf("title", "body"), TextQuery.parse("graph")).squashed()
        assertTrue(sql.contains("LOWER(JSON_VALUE(c.props, '$.title' RETURNING CLOB)) || ' ' || LOWER(JSON_VALUE(c.props, '$.body' RETURNING CLOB)) AS text"), sql)
        assertFalse(sql.contains(") s WHERE"), sql)
    }

    @Test
    fun `a text search through an index asks Oracle Text for each property and adds their scores`() {
        val select = select(Shelf::class.java)
        val sql = select.matchingThrough("g_t_shelf", listOf("title", "body"), TextQuery.parse("graph +data -noise")).squashed()
        assertTrue(sql.contains("SELECT s.nid, SCORE(1) + SCORE(2) AS score FROM g_t_shelf s WHERE JSON_TEXTCONTAINS(s.doc, '$.title', \$_p0, 1) OR JSON_TEXTCONTAINS(s.doc, '$.body', \$_p0, 2)) k"), sql)
        assertTrue(sql.contains("JOIN g_node c ON c.nid = k.nid WHERE INSTR(JSON_SERIALIZE(c.labels), '\"Shelf\"') > 0"), sql)
        assertTrue(sql.endsWith(") h WHERE h.score > 0"), sql)
        assertEquals(mapOf<String, Any?>("_p0" to "({graph} ACCUM {data}) AND {data} NOT {noise}"), select.bindings())
    }
}
