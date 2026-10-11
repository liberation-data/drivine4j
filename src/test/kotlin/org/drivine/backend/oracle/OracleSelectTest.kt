package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Test
import sample.flatexpand.SeqWindow2View
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

    private val scansClaim = "JSON_EXISTS(v.labels, '$[*]?(@ == \"Claim\")')"
    private val carriesClaim = "INSTR(JSON_SERIALIZE(v.labels), '\"Claim\"') > 0"

    // ----- load -----

    @Test
    fun `a fragment is loaded as its document from the nodes of its label`() {
        val statement = select(Claim::class.java).load(emptyList())
        assertEquals(
            "SELECT n_.doc AS result FROM GRAPH_TABLE (g MATCH (v IS node WHERE $scansClaim) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) n_",
            statement.statement.squashed(),
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
        assertTrue(statement.contains("WHERE (JSON_VALUE(n_.props, '$.id') = \$id)"), statement)

        val all = select(Claim::class.java).load(emptyList()).statement
        assertTrue(all.contains(scansClaim), all)
        assertFalse(all.contains(carriesClaim), all)
    }

    @Test
    fun `predicates are each in parentheses and joined by AND`() {
        val statement = select(Claim::class.java).load(listOf("a = 1", "b = 2")).statement
        assertTrue(statement.contains("WHERE (a = 1)\n  AND (b = 2)"), statement)
    }

    @Test
    fun `a view with a relationship that must be there loads and counts only the roots that have it`() {
        val load = select(KeptShelfView::class.java).load(emptyList()).statement
        val required = "WHERE (EXISTS (SELECT 1 FROM GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'KEPT_BY']-> (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Keeper\"') > 0)"
        assertTrue(load.squashed().contains(required), load)
        assertTrue(load.squashed().contains(".src = shelf_.nid))"), load)

        val count = select(KeptShelfView::class.java).count(emptyList()).statement
        assertTrue(count.startsWith("SELECT COUNT(*) FROM GRAPH_TABLE (g"), count)
        assertTrue(count.squashed().contains(required), count)

        val optional = select(ShelfView::class.java).load(emptyList()).statement
        assertFalse(optional.contains("EXISTS (SELECT 1"), optional)
    }

    @Test
    fun `a count has no projection and no order`() {
        val count = select(Claim::class.java).count(listOf("x = 1"))
        assertEquals(
            "SELECT COUNT(*) FROM GRAPH_TABLE (g MATCH (v IS node WHERE $scansClaim) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) n_ WHERE (x = 1)",
            count.statement.squashed(),
        )
    }

    // ----- Projection -----

    @Test
    fun `a collection is gathered as an array in the order of the relationships`() {
        val statement = select(ClaimView::class.java).load(emptyList()).statement.squashed()
        // The fields are visited by name: companies before people.
        assertTrue(statement.startsWith("SELECT JSON_OBJECT( 'claim' VALUE claim_.doc, 'companies' VALUE COALESCE(( SELECT JSON_ARRAYAGG(t0_.doc ORDER BY t0_.eid RETURNING JSON) FROM GRAPH_TABLE (g"), statement)
        assertTrue(statement.contains("WHERE t0_.src = claim_.nid), JSON('[]')), 'people' VALUE COALESCE(( SELECT JSON_ARRAYAGG(t1_.doc ORDER BY t1_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("(b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Human\"') > 0)"), statement)
        assertTrue(statement.contains("(b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Company\"') > 0)"), statement)
        assertTrue(statement.contains("RETURNING JSON) AS result FROM GRAPH_TABLE"), statement)
    }

    @Test
    fun `a single relationship is the first related node or null`() {
        val statement = select(ShelfView::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("'keeper' VALUE ( SELECT t"), statement)
        assertTrue(Regex("ORDER BY t\\d+_\\.eid FETCH FIRST 1 ROWS ONLY\\)").containsMatchIn(statement), statement)
    }

    @Test
    fun `a relationship fragment is the relationship's properties with the node under its target field`() {
        val statement = select(ClaimCitations::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("JSON_ARRAYAGG(JSON_TRANSFORM(t0_.eprops, SET '$.target' = t0_.doc) ORDER BY t0_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("e0.type = 'CITES'"), statement)
    }

    @Test
    fun `a path reaches each node once, through the hops with their intermediate labels`() {
        val statement = select(ClaimEmployers::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("'employers' VALUE COALESCE(( SELECT JSON_ARRAYAGG(x1_.doc ORDER BY x1_.nid RETURNING JSON) FROM GRAPH_TABLE (g MATCH (v IS node) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) x1_"), statement)
        assertTrue(statement.contains("WHERE x1_.nid IN ( SELECT r0_.nid FROM GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (m0 IS node WHERE INSTR(JSON_SERIALIZE(m0.labels), '\"Human\"') > 0) -[e1 IS edge WHERE e1.type = 'WORKS_AT']-> (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Company\"') > 0)"), statement)
        assertTrue(statement.contains("WHERE r0_.src = claim_.nid)"), statement)
        // Two hops give no single relationship to carry.
        assertFalse(statement.contains("e1.eid"), statement)
    }

    @Test
    fun `a flat list read to a depth is one step taken up to that many times`() {
        val statement = select(SeqWindow2View::class.java).load(emptyList()).statement.squashed()
        assertTrue(statement.contains("-[e0 IS edge WHERE e0.type = 'NEXT']->{1,2} (b IS node"), statement)
        assertTrue(statement.contains("'following' VALUE COALESCE(( SELECT JSON_ARRAYAGG(x1_.doc ORDER BY x1_.nid RETURNING JSON)"), statement)
    }

    @Test
    fun `a recursive view nests itself to its depth, where the field is empty`() {
        val statement = select(LocationHierarchy::class.java, depths = mapOf("subLocations" to 2)).load(emptyList()).statement
        assertEquals(3, count(statement, "'subLocations' VALUE"), statement)
        assertEquals(2, count(statement, "e0.type = 'HAS_LOCATION'"), statement)
        assertEquals(1, count(statement, "'subLocations' VALUE JSON('[]')"), statement)
        assertTrue(statement.contains("WHERE t1_.src = t0_.nid"), statement)
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
        assertTrue(statement.contains("JSON_ARRAYAGG(t1_.doc ORDER BY JSON_VALUE(t1_.props, '$.name') DESC, t1_.eid RETURNING JSON)"), statement)
        // The other collection is untouched.
        assertTrue(statement.contains("JSON_ARRAYAGG(t0_.doc ORDER BY t0_.eid RETURNING JSON)"), statement)
    }

    @Test
    fun `a sort on a nested view's collection is applied under the relationship that leads to it`() {
        val statement = select(Dossier::class.java, sorts = listOf(CollectionSortSpec("claims_people", "name", true))).load(emptyList()).statement
        assertEquals(1, Regex("ORDER BY JSON_VALUE\\(t\\d+_\\.props, '\\$\\.name'\\) ASC, t\\d+_\\.eid").findAll(statement).count(), statement)
        assertTrue(statement.contains("JSON_ARRAYAGG(t2_.doc ORDER BY JSON_VALUE(t2_.props, '$.name') ASC, t2_.eid RETURNING JSON)"), statement)
        assertTrue(statement.contains("JSON_ARRAYAGG(JSON_OBJECT("), statement)
    }

    @Test
    fun `a sort on a recursive field is applied at every level, so the tree is in one order`() {
        val statement = select(LocationHierarchy::class.java, sorts = listOf(CollectionSortSpec("subLocations", "name", true))).load(emptyList()).statement
        assertEquals(3, count(statement, "e0.type = 'HAS_LOCATION'"), statement)
        assertEquals(3, Regex("ORDER BY JSON_VALUE\\(t\\d+_\\.props, '\\$\\.name'\\) ASC, t\\d+_\\.eid").findAll(statement).count(), statement)
    }

    @Test
    fun `a count field counts the relationships, and another aggregate reads the property as a number`() {
        val counted = select(ClaimStats::class.java).load(emptyList()).statement.squashed()
        assertTrue(counted.contains("'mentionCount' VALUE (SELECT COUNT(*) FROM GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (b IS node)"), counted)

        val averaged = select(UndeclaredAggregate::class.java).load(emptyList()).statement.squashed()
        assertTrue(averaged.contains("'averageWeight' VALUE (SELECT AVG(JSON_VALUE(t0_.props, '$.weight' RETURNING NUMBER)) FROM GRAPH_TABLE"), averaged)
    }

    // ----- cascaded -----

    @Test
    fun `a fragment cascades to nothing`() {
        assertNull(select(Claim::class.java).cascaded(emptyList()))
    }

    @Test
    fun `a cascade selects the nodes of each written relationship field, one SELECT a path`() {
        val select = select(ClaimView::class.java)
        val sql = assertNotNull(select.cascaded(listOf(select.hasId("id")))).squashed()
        assertEquals(1, count(sql, "UNION"), sql)
        assertTrue(sql.startsWith("SELECT c0_.nid FROM GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Company\"') > 0)"), sql)
        assertTrue(sql.contains("UNION SELECT c1_.nid FROM GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Human\"') > 0)"), sql)
        // The roots are selected as a delete selects them: by the label on the node found, and the predicates.
        assertTrue(sql.contains("WHERE c1_.src IN (SELECT claim_.nid FROM g_node claim_ WHERE INSTR(JSON_SERIALIZE(claim_.labels), '\"Claim\"') > 0 AND (JSON_VALUE(claim_.props, '$.id') = \$id))"), sql)
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
        assertTrue(nested.contains("e0.type = 'COVERS']-> (m0 IS node WHERE INSTR(JSON_SERIALIZE(m0.labels), '\"Claim\"') > 0) -[e1 IS edge WHERE e1.type = 'MENTIONS']->"), nested)

        val recursive = assertNotNull(select(LocationHierarchy::class.java).cascaded(emptyList()))
        assertFalse(recursive.contains("UNION"), recursive)
        assertTrue(recursive.contains("e0.type = 'HAS_LOCATION']->{1,3} (b IS node"), recursive)
    }

    // ----- relatedTo -----

    @Test
    fun `the nodes related to a node found by id are selected through the arrow of the direction`() {
        val out = select(Claim::class.java)
        assertEquals(
            "n_.nid IN (SELECT t0_.nid FROM GRAPH_TABLE (g MATCH (a IS node WHERE JSON_VALUE(a.props, '$.id') = \$_fromId AND INSTR(JSON_SERIALIZE(a.labels), '\"Human\"') > 0) -[e IS edge WHERE e.type = 'MENTIONS']-> (b IS node) COLUMNS (b.nid)) t0_)",
            out.relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.OUTGOING).squashed(),
        )
        assertTrue(out.bindings().isEmpty())
        // The roots are now found through the relationship, so their label is tested on what is found.
        assertTrue(out.load(emptyList()).statement.contains(carriesClaim))

        assertTrue(select(Claim::class.java).relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.INCOMING).contains("<-[e IS edge WHERE e.type = 'MENTIONS']- (b IS node)"))
        assertTrue(select(Claim::class.java).relatedTo(listOf("Human"), "id", "_fromId", "MENTIONS", Direction.UNDIRECTED).contains("-[e IS edge WHERE e.type = 'MENTIONS']- (b IS node)"))
    }

    // ----- scored search -----

    @Test
    fun `a scored search filters its candidates by the threshold and returns them best first`() {
        val select = select(Claim::class.java)
        val statement = select.scored("SELECT * FROM candidates", listOf("x = 1"), 0.7, 5)
        val text = statement.statement.squashed()
        assertTrue(text.startsWith("SELECT JSON_OBJECT('value' VALUE n_.doc, 'score' VALUE n_.score RETURNING JSON) AS result FROM ( SELECT * FROM candidates ) n_ WHERE (n_.score >= \$_p0) AND (x = 1)"), text)
        assertTrue(text.endsWith("ORDER BY n_.score DESC, n_.nid FETCH FIRST \$_p1 ROWS ONLY"), text)
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
        assertTrue(statement.contains("WHERE (EXISTS (SELECT 1 FROM GRAPH_TABLE"), statement)
        assertTrue(statement.contains("'score' VALUE shelf_.score"), statement)
    }

    @Test
    fun `the nearest by an exact measure reads every node that has the embedding`() {
        val select = select(Shelf::class.java)
        val sql = select.nearestTo("embedding", SimilarityFunction.COSINE, null, listOf(0.1f, 0.2f), 7).squashed()
        assertTrue(sql.startsWith("SELECT c.nid, c.labels, c.props, c.stamp, c.doc, (2 - VECTOR_DISTANCE(TO_VECTOR(JSON_QUERY(c.props, '$.embedding' RETURNING CLOB)), TO_VECTOR(\$_p0), COSINE)) / 2 AS score FROM GRAPH_TABLE (g MATCH (v IS node WHERE JSON_EXISTS(v.labels, '$[*]?(@ == \"Shelf\")') AND JSON_EXISTS(v.props, '$.embedding'))"), sql)
        assertTrue(sql.endsWith("ORDER BY score DESC, c.nid FETCH FIRST \$_p1 ROWS ONLY"), sql)
        assertEquals(mapOf<String, Any?>("_p0" to listOf(0.1f, 0.2f), "_p1" to 7), select.bindings())
    }

    @Test
    fun `a partition label narrows the nearest, and the Euclidean score is one over one plus the distance`() {
        val sql = select(Shelf::class.java).nearestTo("embedding", SimilarityFunction.EUCLIDEAN, "Hot", listOf(0.1f), 3).squashed()
        assertTrue(sql.contains("JSON_EXISTS(v.labels, '$[*]?(@ == \"Shelf\")') AND JSON_EXISTS(v.labels, '$[*]?(@ == \"Hot\")') AND JSON_EXISTS(v.props, '$.embedding')"), sql)
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
        assertTrue(sql.contains("JSON_EXISTS(v.labels, '$[*]?(@ == \"Shelf\")')"), sql)
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
