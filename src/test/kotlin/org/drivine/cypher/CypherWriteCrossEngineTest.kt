package org.drivine.cypher

import org.drivine.backend.oracle.OracleGraph
import org.drivine.connection.DatabaseType
import org.drivine.cypher.spec.CypherSpec
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertTrue

/**
 * A Cypher statement that writes runs on a SQL store, compiled to a procedure of its SQL, and leaves the
 * graph a graph engine leaves: the statements of the spec's `cypher/writes.yaml` are run on Neo4j, Postgres,
 * Oracle and SQLite, and read back.
 */
abstract class CypherWriteContract {

    abstract val pm: PersistenceManager

    /** Removes the graph the tests wrote. */
    abstract fun clear()

    /** Whether the statement under test is compiled to SQL: what only compiling refuses is asked of it alone. */
    open val compiles: Boolean = false

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    @BeforeEach
    fun graph() = clear()

    @TestFactory
    fun spec() = CypherSpec.load("writes.yaml").tests(CypherSpec.storeOf(pm.type), before = ::clear) { step -> query(step.cypher, step.parameters) }

    @Test
    fun `a MERGE that makes a null property fails with Neo4j's words, on the first row that makes one`() {
        // #543: the spec says only that it fails, as each store wraps the message in its own; the words are Neo4j's, which names the pattern after them.
        query("CREATE (:CyWrite {id: 1})")
        for ((cypher, words) in listOf(
            "UNWIND [2, null] AS v MERGE (:CyWrite {id: v})" to "Cannot merge the following node because of null property value for 'id'",
            "MATCH (s:CyWrite {id: 1}) MERGE (s)-[:R {x: s.none}]->(:CyWrite {id: 2})" to
                "Cannot merge the following relationship because of null property value for 'x'",
        )) {
            val refused = assertFailsWith<Exception> { query(cypher) }
            assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { words in it.message.orEmpty() }, refused.toString())
        }
        assertEquals(listOf(1L), query("MATCH (n) RETURN count(n)"))
    }

    @Test
    fun `a MERGE run again with its parameter bound to null fails, though it made a node with another value`() {
        // #543: what the compiled MERGE tests depends on the value bound, so the same statement is compiled again for each.
        val cypher = "MERGE (n:CyWrite {x: \$p}) RETURN n.x"
        assertEquals(listOf(2L), query(cypher, mapOf("p" to 2L)))
        assertFailsWith<Exception> { query(cypher, mapOf("p" to null)) }
        assertEquals(listOf(2L), query(cypher, mapOf("p" to 2L)))
        assertEquals(listOf(1L), query("MATCH (n) RETURN count(n)"))
    }

    @Test
    fun `a MERGE that works its property out as NaN fails and writes nothing`() {
        // #543 with #563: NaN worked out in the statement, by a float divided by zero or toFloat of Java's name for it, as Neo4j gives it.
        query("CREATE (:CyWrite {id: 1, zero: 0.0})")
        for (cypher in listOf(
            "MERGE (n:CyWrite {id: 2, x: 0.0 / 0.0})",
            "MERGE (n:CyWrite {id: 2, x: toFloat('NaN')})",
            "MATCH (s:CyWrite {id: 1}) MERGE (s)-[:R {x: s.zero / s.zero}]->(:CyWrite {id: 2})",
        )) assertFailsWith<Exception>(cypher) { query(cypher) }
        assertEquals(listOf(1L), query("MATCH (n) RETURN count(n)"))
    }

    @Test
    fun `a write's rows group a key's value that is no null as before`() {
        // #500: the spec holds a map's null and a missing key, grouped as one null and kept once. The rows are Neo4j's (2026.10).
        assertEquals(listOf(listOf(1L, 2L)), query("UNWIND [{a: 1}, {a: 1}] AS r CREATE (:CyWrite {b: 9}) RETURN r.a AS a, count(*) AS c"))
    }

    @Test
    fun `a path a WITH passes on gives each row's write its own nodes and relationships`() {
        // On Oracle, each row read the first row's path after the WITH (#537); the spec has these since v0.23.0, on its own graph.
        query("CREATE (a:CyWrite {i: 0})-[:NEXT {i: 0}]->(b:CyWrite {i: 1})-[:NEXT {i: 1}]->(a)")
        query("MATCH p = (a:CyWrite)-[:NEXT]->() WITH p, a SET a.r = relationships(p)[0].i, a.n = [x IN nodes(p) | x.i]")
        assertEquals(listOf(listOf(0L, 0L, listOf(0L, 1L)), listOf(1L, 1L, listOf(1L, 0L))), query("MATCH (a:CyWrite) RETURN [a.i, a.r, a.n] ORDER BY a.i"))
    }

    @Test
    fun `a relationship found either way round is set and deleted, one from a node to itself once`() {
        // On Postgres and Oracle the write's MATCH reads the relationships both ways round (#570). The rows are Neo4j's.
        query("CREATE (a:CyWrite {k: 1}), (b:CyWrite {k: 2}), (a)-[:UN {i: 1}]->(b), (b)-[:UN {i: 2}]->(a), (a)-[:UN {i: 3}]->(a)")
        assertEquals(listOf(1L, 2L, 3L), query("MATCH (:CyWrite {k: 1})-[r:UN]-() SET r.seen = true RETURN r.i ORDER BY r.i"))
        assertEquals(listOf(1L, 1L, 2L, 2L, 3L), query("MATCH (:CyWrite)-[r:UN]-() WHERE r.seen RETURN r.i ORDER BY r.i"))
        query("MATCH (:CyWrite {k: 2})-[r:UN]-(:CyWrite {k: 1}) DELETE r")
        assertEquals(listOf(listOf(3L, true)), query("MATCH ()-[r:UN]->() RETURN [r.i, r.seen]"))
    }

    @Test
    fun `a MERGE either way finds one stored the other way round, one from a node to itself once, and fails as it makes a null property`() {
        // #570 with #543: the MERGE's match reads the relationships both ways round on Postgres and Oracle; only what it makes is refused.
        query("CREATE (a:CyWrite {k: 1}), (b:CyWrite {k: 2}), (b)-[:UN {i: 1}]->(a), (a)-[:UN {i: 2}]->(a)")
        assertEquals(listOf(1L), query("MATCH (a:CyWrite {k: 1}), (b:CyWrite {k: 2}) MERGE (a)-[r:UN {i: 1}]-(b) ON MATCH SET r.seen = true RETURN r.i"))
        assertEquals(listOf(2L), query("MATCH (a:CyWrite {k: 1}) MERGE (a)-[r:UN]-(a) ON MATCH SET r.seen = true RETURN r.i"))
        val refused = assertFailsWith<Exception> { query("MATCH (a:CyWrite {k: 1}), (b:CyWrite {k: 2}) MERGE (a)-[:UN {i: a.none}]-(b)") }
        assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { "Cannot merge the following relationship because of null property value for 'i'" in it.message.orEmpty() }, refused.toString())
        assertEquals(listOf(listOf(1L, true), listOf(2L, true)), query("MATCH ()-[r:UN]->() RETURN [r.i, r.seen] ORDER BY r.i"))
    }

    @Test
    fun `CALLs that write, nested, give each row its own variables with the nodes they return`() {
        // Not in the spec yet: a caller row at each depth, merged with a node returned from below it.
        assertEquals(
            listOf(listOf(1L, 1L, 10L), listOf(2L, 2L, 20L)),
            query(
                "UNWIND [1, 2] AS x CALL { WITH x CREATE (a:CyWrite {x: x}) WITH a, x " +
                    "CALL { WITH a, x CREATE (b:CyWrite {y: x * 10}) RETURN b } RETURN a, b } RETURN [x, a.x, b.y]"
            ),
        )
        assertEquals(
            listOf(listOf(1L, 1L, 10L), listOf(2L, 2L, 20L)),
            query("UNWIND [1, 2] AS i CALL { WITH i CREATE (n:CyWrite {i: i}) RETURN n } SET n.j = i * 10 RETURN [i, n.i, n.j] ORDER BY i"),
        )
        assertEquals(
            listOf(listOf(2020L, 3L)),
            query("WITH date('2020-01-02') AS d CALL { CREATE (n:CyWrite) RETURN date('2021-03-04') AS e } RETURN [d.year, e.month]"),
        )
    }

    @Test
    fun `a CALL that writes sees only the variables it imports, so a CREATE of a name it does not import makes a new node`() {
        // Not in the spec yet (#410): the row's node of that name was joined, so no node was made. The rows are Neo4j's (2026.10).
        query("CREATE (:CyWrite {id: 1}), (:CyWrite {id: 2})")
        assertEquals(
            listOf(listOf(1L, true), listOf(2L, true)),
            query("MATCH (a:CyWrite) CALL () { CREATE (a:CyWrite {made: true}) RETURN a.made AS m } RETURN a.id, m ORDER BY a.id"),
        )
        assertEquals(listOf(listOf(1L, 1L)), query("MATCH (a:CyWrite {id: 1}) CALL { CREATE (a:CyWrite {made: false}) RETURN 1 AS x } RETURN [a.id, x]"))
        assertEquals(listOf(listOf(2L, 3L)), query("MATCH (n:CyWrite) RETURN [count(n.id), count(n.made)]"))
        // CALL (a) imports a alone: b in it is new, and the row's b is as it was after it.
        query("MATCH (a:CyWrite {id: 1}), (b:CyWrite {id: 2}) CALL (a) { CREATE (a)-[:R]->(b:CyWrite {id: 3}) } RETURN 1")
        assertEquals(listOf(listOf(1L, 3L)), query("MATCH (a:CyWrite)-[:R]->(b) RETURN [a.id, b.id]"))
        assertEquals(
            listOf(listOf(2L, 4L)),
            query("MATCH (a:CyWrite {id: 1}), (b:CyWrite {id: 2}) CALL (a) { CREATE (b:CyWrite {id: 4}) RETURN b.id AS made } RETURN [b.id, made]"),
        )
        // What brackets import is seen after a leading WITH that does not pass it on, as Neo4j has it.
        assertEquals(listOf(true), query("MATCH (a:CyWrite {id: 1}), (b:CyWrite {id: 2}) CALL (a, b) { WITH a SET b.seen = true } RETURN b.seen"))
        // ... and after any WITH: a CREATE of it joins the row's node, and a SET writes it.
        query("MATCH (a:CyWrite {id: 1}) CALL (a) { WITH 1 AS one CREATE (a)-[:S]->(:CyWrite {id: 5}) } RETURN 1")
        assertEquals(listOf(listOf(1L, 5L)), query("MATCH (a:CyWrite)-[:S]->(b) RETURN [a.id, b.id]"))
        assertEquals(listOf(5L), query("MATCH (a:CyWrite {id: 1}) CALL (a) { MATCH (c:CyWrite {id: 5}) WITH c SET a.next = c.id RETURN a.next AS n } RETURN n"))
        assertEquals(listOf(1L), query("MATCH (a:CyWrite {id: 2}) CALL (*) { WITH 1 AS one SET a.one = one } RETURN a.one"))
        // An aggregate over no rows still gives its row, and the WITH's WHERE reads what brackets import.
        assertEquals(listOf(0L), query("MATCH (a:CyWrite {id: 1}) CALL (a) { MATCH (c:CyNone) WITH count(c) AS n SET a.none = n RETURN a.none AS v } RETURN v"))
        assertEquals(listOf(listOf(1L, 5L)), query("MATCH (a:CyWrite {id: 1}) CALL (a) { MATCH (c:CyWrite) WITH c WHERE c.id = a.next SET c.prev = a.id RETURN c.prev AS p, c.id AS i } RETURN [p, i]"))
        // Reading the imports again after a WITH that aggregates, or keeps rows distinct, gives one row for each row of the call.
        assertEquals(
            listOf(listOf(1L, 8L, 8L), listOf(2L, 8L, 8L)),
            query("MATCH (a:CyWrite) WHERE a.id IN [1, 2] CALL (a) { MATCH (c:CyWrite) WITH count(*) AS n SET a.count = n RETURN n } RETURN [a.id, n, a.count] ORDER BY a.id"),
        )
        assertEquals(
            listOf(listOf(1L, 1L), listOf(2L, 1L)),
            query("MATCH (a:CyWrite) WHERE a.id IN [1, 2] CALL (a) { MATCH (c:CyWrite) WITH DISTINCT 1 AS one SET a.d = one RETURN one } RETURN [a.id, a.d] ORDER BY a.id"),
        )
    }

    @Test
    fun `a statement of hundreds of creates, each naming nodes made before it, makes them all`() {
        val clauses = (0 until 300).flatMap { i ->
            listOf("CREATE (n$i:CyWrite {i: $i})") + if (i > 0) listOf("CREATE (n${i - 1})-[:NEXT]->(n$i)") else emptyList()
        }
        query(clauses.joinToString("\n"))
        assertEquals(listOf(listOf(300L, 299L)), query("MATCH (n:CyWrite) OPTIONAL MATCH (n)-[r:NEXT]->() RETURN [count(DISTINCT n), count(r)]"))
    }

    @Test
    fun `drivine's stamp is written as a node's property is, and read back as one`() {
        val stamp = Stamps.QUOTED
        query("CREATE (:CyWrite {id: 1, $stamp: 'aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb'})")
        assertEquals(listOf("aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
        query("MATCH (n:CyWrite {id: 1}) SET ${Stamps.setClause("n")}")
        val (token, links) = (query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp").single() as String).split(':')
        assertTrue(token.length == 16 && token != "aaaaaaaaaaaaaaaa" && links == "bbbbbbbbbbbbbbbb", "$token:$links")
        assertEquals(listOf(mapOf("id" to 1L, Stamps.PROPERTY to "$token:$links")), query("MATCH (n:CyWrite {id: 1}) RETURN properties(n)"))
        query("MATCH (n:CyWrite {id: 1}) REMOVE n.$stamp")
        assertEquals(listOf(null), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
    }

    @Test
    fun `the tokens the stamp clauses make are random, so a stale object's stamp does not come round again`() {
        // On Oracle a token was the first 16 hex digits of a SYS_GUID(), of which a counter's 4 changed (#401).
        val stamp = Stamps.QUOTED
        query("UNWIND range(1, 1000) AS i CREATE (:CyWrite {id: i})")
        query("MATCH (n:CyWrite) SET ${Stamps.setClause("n")}")
        query("MATCH (n:CyWrite) SET ${Stamps.linksClause("n")}")
        val stamps = query("MATCH (n:CyWrite) RETURN n.$stamp").map { it as String }
        val tokens = stamps.flatMap { it.split(':') }
        assertEquals(1000, stamps.toSet().size)
        assertEquals(2000, tokens.toSet().size)
        // Each hex digit but the 13th, a version 4 UUID's version, takes nearly every value across the tokens.
        (0 until Stamps.TOKEN).filter { it != 12 }.forEach { position ->
            val digits = tokens.map { it[position] }.toSet()
            assertTrue(digits.size >= 12, "position $position of the tokens took only $digits")
        }
        // Saved one after another, a node's tokens seldom share a long prefix, where SYS_GUID()'s always did.
        val saved = (1..20).map {
            query("MATCH (n:CyWrite {id: 1}) SET ${Stamps.setClause("n")}")
            (query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp").single() as String).substringBefore(':')
        }
        // Two random tokens share 5 digits once in about a million; three such pairs in 19, about once in 1e15 runs.
        assertTrue(saved.zipWithNext().count { (a, b) -> a.commonPrefixWith(b).length > 4 } <= 2, "$saved share long prefixes")
    }

    @Test
    fun `an unwound item's text, short or past 32 KB, is written only to the nodes a labelled pattern matches`() {
        // On Oracle such a text is read in a CASE on its type (#309), and such a CASE in a read made Oracle drop a label test
        // (see OracleCypherCompiler.itemProperty): each node a label rules out, on either end of the pattern, is left alone.
        for (text in listOf("s", "x".repeat(40_000))) {
            clear()
            query(
                "UNWIND [1, 2, 3] AS id CREATE (:CyWrite {id: id, o: id * 2})-[:R]->(:CyOther {name: 'k'}), " +
                    "(:CyWrite {id: id, o: id * 2 + 1})-[:R]->(:CyDecoy {name: 'k'}), (:CyNope {id: id})-[:R]->(:CyOther {name: 'k'})",
            )
            val given = mapOf("texts" to (1..3).map { mapOf("id" to it, "a" to text, "s" to "s", "k" to "k") })
            query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id})-[r]->(k:CyOther) SET n.a = t.a", given)
            // A value of the item's worked out beside the node the pattern reached. The `+` is of the short text: on Oracle a
            // `+` of a text past 4,000 bytes leaves that text out, with or without #309's change (#314 fixes it).
            query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id})-[r]->(k:CyOther) SET n.b = CASE k.name WHEN 'k' THEN t.a END, n.e = t.s + k.name", given)
            query("UNWIND \$texts AS t MERGE (n:CyWrite {id: t.id, o: t.id * 2}) SET n.c = t.a", given)
            // A labelled pattern in a subquery of the statement that works the value out, beside the item's text in the CASE.
            query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id}) SET n.f = COUNT { (n)-->(:CyOther {name: t.k}) }", given)
            val matched = { value: String -> (1..3).flatMap { listOf(value, null) } }
            assertEquals(matched(text), query("MATCH (n:CyWrite) RETURN n.a ORDER BY n.o"))
            assertEquals(matched(text), query("MATCH (n:CyWrite) RETURN n.b ORDER BY n.o"))
            assertEquals(matched("sk"), query("MATCH (n:CyWrite) RETURN n.e ORDER BY n.o"))
            assertEquals(matched(text), query("MATCH (n:CyWrite) RETURN n.c ORDER BY n.o"))
            assertEquals((1..3).flatMap { listOf(1L, 0L) }, query("MATCH (n:CyWrite) RETURN n.f ORDER BY n.o"))
            assertEquals(listOf(6L), query("MATCH (n:CyWrite) RETURN count(n)"))
            assertEquals(listOf(0L), query("MATCH (n:CyNope) WHERE n.a IS NOT NULL OR n.b IS NOT NULL OR n.c IS NOT NULL OR n.e IS NOT NULL RETURN count(n)"))
        }
    }

    @Test
    fun `an unwound parameter's items are written as they are given, by SET, CREATE, MERGE and SET +=`() {
        // On Oracle such a value is read through its text (#309): a whole float stays a float, a null or a missing key
        // writes no property, and a list, a key of a map in the item and a short text are written as they were.
        val items = listOf(
            mapOf("id" to 1, "s" to "short", "f" to 5.0, "i" to 5, "l" to listOf(1, 2), "fl" to listOf(1.0, 2.5), "m" to mapOf("k" to 2.0), "z" to null),
            mapOf("id" to 2, "s" to "x'\"y", "f" to -0.5, "i" to 0, "l" to emptyList<Any>(), "fl" to listOf(3.0), "m" to mapOf("k" to "v")),
        )
        val given = mapOf("items" to items)
        query("UNWIND \$items AS t CREATE (:CyWrite {id: t.id, s: t.s, f: t.f, i: t.i, l: t.l, fl: t.fl, k: t.m.k, z: t.z, w: t.nothing})", given)
        query("UNWIND \$items AS t MATCH (n:CyWrite {id: t.id}) SET n.s2 = t.s, n.f2 = t.f, n.z2 = t.z, n.w2 = t.nothing", given)
        query("UNWIND \$items AS t MERGE (n:CyWrite {id: t.id}) SET n.f3 = t.f", given)
        query("UNWIND \$items AS t MERGE (n:CyWrite {id: t.id + 10}) ON CREATE SET n.f = t.f, n.m = t.m.k", given)
        query("UNWIND \$added AS t MATCH (n:CyWrite {id: t.id}) SET n += t", mapOf("added" to listOf(mapOf("id" to 1, "g" to 7.0, "s" to null), mapOf("id" to 2, "g" to 8))))
        query("UNWIND \$floats AS f CREATE (:CyWrite {id: 20, f: f})", mapOf("floats" to listOf(6.0)))
        assertEquals(
            listOf(
                mapOf("id" to 1L, "f" to 5.0, "i" to 5L, "l" to listOf(1L, 2L), "fl" to listOf(1.0, 2.5), "k" to 2.0, "s2" to "short", "f2" to 5.0, "f3" to 5.0, "g" to 7.0),
                mapOf("id" to 2L, "s" to "x'\"y", "f" to -0.5, "i" to 0L, "l" to emptyList<Any>(), "fl" to listOf(3.0), "k" to "v", "s2" to "x'\"y", "f2" to -0.5, "f3" to -0.5, "g" to 8L),
                mapOf("id" to 11L, "f" to 5.0, "m" to 2.0),
                mapOf("id" to 12L, "f" to -0.5, "m" to "v"),
                mapOf("id" to 20L, "f" to 6.0),
            ),
            query("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.id"),
        )
    }

    @Test
    fun `an unwound parameter's values returned or passed on by a RETURN or a WITH come back as they are given`() {
        // On Oracle such a value is read through the item's text where it is a text (#322): a whole float stays a float, a
        // list, a map, an empty text, a null and a missing key come back as they were, returned, passed on, or after a write.
        val items = listOf(
            mapOf("id" to 1, "s" to "short", "f" to 5.0, "i" to 5, "l" to listOf(1, 2), "fl" to listOf(1.0, 2.5), "m" to mapOf("k" to 2.0), "z" to null, "e" to ""),
            mapOf("id" to 2, "s" to "x'\"y", "f" to -0.5, "i" to 0, "l" to emptyList<Any>(), "fl" to listOf(3.0), "m" to mapOf("k" to "v"), "e" to "e"),
        )
        val given = mapOf("items" to items)
        query("CREATE (:CyWrite {id: 1}), (:CyWrite {id: 2})")
        val expected = mapOf(
            "s" to listOf("short", "x'\"y"), "f" to listOf(5.0, -0.5), "i" to listOf(5L, 0L), "l" to listOf(listOf(1L, 2L), emptyList()),
            "fl" to listOf(listOf(1.0, 2.5), listOf(3.0)), "m" to listOf(mapOf("k" to 2.0), mapOf("k" to "v")), "z" to listOf(null, null),
            "nothing" to listOf(null, null), "e" to listOf("", "e"),
        )
        expected.forEach { (key, values) ->
            assertEquals(values, query("UNWIND \$items AS t RETURN t.$key", given), key)
            assertEquals(values, query("UNWIND \$items AS t WITH t.$key AS v RETURN v", given), key)
            assertEquals(values, query("UNWIND \$items AS t WITH t RETURN t.$key", given), key)
            assertEquals(values, query("UNWIND \$items AS t MATCH (n:CyWrite {id: t.id}) SET n.seen = true RETURN t.$key ORDER BY t.id", given), key)
            assertEquals(values, query("CALL { UNWIND \$items AS t RETURN t } RETURN t.$key", given), key)
        }
        // An OPTIONAL CALL that unwinds nothing gives a null item, and its value is null.
        assertEquals(listOf(null), query("UNWIND [1] AS x OPTIONAL CALL { WITH x UNWIND [] AS t RETURN t } RETURN t.s"))
        query("UNWIND \$items AS t WITH t.id AS id, t.s AS s, t.f AS f, t.l AS l, t.m.k AS k CREATE (:CyMade {id: id, s: s, f: f, l: l, k: k})", given)
        assertEquals(
            listOf(
                mapOf("id" to 1L, "s" to "short", "f" to 5.0, "l" to listOf(1L, 2L), "k" to 2.0),
                mapOf("id" to 2L, "s" to "x'\"y", "f" to -0.5, "l" to emptyList<Any>(), "k" to "v"),
            ),
            query("MATCH (n:CyMade) RETURN properties(n) ORDER BY n.id"),
        )
        // A map with a key as extended JSON's comes back the map it is, and not the number extended JSON writes.
        val keyed = listOf(mapOf("d" to mapOf("\$numberLong" to "5")), mapOf("d" to mapOf("\$numberDouble" to "2.0")))
        assertEquals(keyed.map { it["d"] }, query("UNWIND \$items AS t RETURN t.d", mapOf("items" to keyed)))
        assertEquals(keyed.map { it["d"] }, query("UNWIND \$items AS t WITH t RETURN t.d", mapOf("items" to keyed)))
    }

    @Test
    fun `an unwound item's text, short or past 32 KB, is returned only for the nodes a labelled pattern matches`() {
        // On Oracle such a text is read in a CASE on its type (#322), and such a CASE on a node's properties made Oracle drop a
        // label test (see OracleCypherCompiler.itemProperty): each node a label rules out, on either end of the pattern, is left out.
        query(
            "UNWIND [1, 2, 3] AS id CREATE (:CyWrite {id: id, o: id * 2})-[:R]->(:CyOther {name: 'k'}), " +
                "(:CyWrite {id: id, o: id * 2 + 1})-[:R]->(:CyDecoy {name: 'k'}), (:CyNope {id: id})-[:R]->(:CyOther {name: 'k'})",
        )
        for (text in listOf("s", "x".repeat(40_000))) {
            val given = mapOf("texts" to (1..3).map { mapOf("id" to it, "a" to "$text$it", "k" to "k") })
            val texts = (1..3).map { "$text$it" }
            assertEquals(texts, query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id})-[r]->(k:CyOther) RETURN t.a ORDER BY n.o", given))
            assertEquals(texts, query("UNWIND \$texts AS t MATCH (k:CyOther {name: t.k})<-[r]-(n:CyWrite {id: t.id}) RETURN t.a ORDER BY n.o", given))
            assertEquals(
                (1..3).map { listOf("$text$it", it * 2L) },
                query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id}) WHERE (n)-->(:CyOther) WITH t.a AS a, n RETURN [a, n.o] ORDER BY n.o", given),
            )
            assertEquals(texts, query("UNWIND \$texts AS t MATCH (n:CyWrite {id: t.id})-[r]->(k:CyOther) SET n.seen = true RETURN t.a ORDER BY n.o", given))
            assertEquals(texts.flatMap { listOf(it, it) }, query("UNWIND \$texts AS t MATCH (k:CyOther)<--(n {id: t.id}) RETURN t.a ORDER BY t.id", given))
        }
        // So is a value of an item of a list made of a node's properties, unwound beside the labelled pattern.
        assertEquals(listOf(2L, 4L, 6L), query("MATCH (n:CyWrite)-[r]->(k:CyOther) UNWIND [n {.o}] AS m RETURN m.o ORDER BY m.o"))
        assertEquals(listOf(0L), query("MATCH (n) WHERE n.seen AND NOT n:CyWrite OR n:CyWrite AND n.seen AND n.o % 2 = 1 RETURN count(n)"))
    }

    @Test
    fun `a value a write's subquery takes from its row is read from what a UNION or an OPTIONAL CALL gives`() {
        // #336: a property of a value a write reads from its row is read from the row (#309). x is t.m in one query of the
        // UNION and t.l in the other, so its property is read from what the UNION gives; u is null where the OPTIONAL CALL
        // finds nothing, and not t.
        query("CREATE (:CyWrite {id: 1}), (:CyWrite {k: 'p'}), (:CyWrite {k: 'q'}), (:CyWrite {k: 'q'})")
        val given = mapOf("ts" to listOf(mapOf("id" to 1, "a" to "q", "f" to false, "m" to mapOf("a" to "p"), "l" to mapOf("a" to "q"))))
        fun set(property: String, value: String) = query("UNWIND \$ts AS t MATCH (n:CyWrite {id: t.id}) SET n.$property = $value", given)
        fun counted(subquery: String) = "COUNT { $subquery MATCH (y:CyWrite) WHERE y.k = %s RETURN y }"
        set("union", counted("CALL { WITH t RETURN t.m AS x UNION ALL WITH t RETURN t.l AS x }").format("x.a"))
        // Where each query takes it from the same keys, or a CALL, a CASE, a comprehension, a WITH or a FOREACH passes on the
        // row's value, it is read as it is given.
        set("same", counted("CALL { WITH t RETURN t.l AS x UNION ALL WITH t RETURN t.l AS x }").format("x.a"))
        set("called", counted("CALL { WITH t RETURN t.l AS x }").format("x.a"))
        set("cased", counted("").format("(CASE WHEN t.f THEN t.m ELSE t.l END).a"))
        set("comprehended", counted("UNWIND [x IN [t.m, t.l] WHERE x.a = 'q' | x] AS x").format("x.a"))
        query("UNWIND \$ts AS t WITH t.l AS t, t.id AS id MATCH (n:CyWrite {id: id}) SET n.rebound = ${counted("").format("t.a")}", given)
        query("UNWIND \$ts AS t MATCH (n:CyWrite {id: t.id}) FOREACH (x IN [t.m, t.l] | FOREACH (z IN [x] | SET n.each = coalesce(n.each, '') + z.a))", given)
        assertEquals(
            listOf(listOf(3L, 4L, 2L, 2L, 2L, 2L, "pq")),
            query("MATCH (n:CyWrite {id: 1}) RETURN [n.union, n.same, n.called, n.cased, n.comprehended, n.rebound, n.each]"),
        )
        // On Oracle, an OPTIONAL CALL in a write's subquery was refused (ORA-06550, ORA-00907) until it was joined on 1 = 1 (#338).
        set("optional", counted("OPTIONAL CALL { WITH t MATCH (z:Nope) RETURN t AS u }").format("u.a"))
        set("found", counted("OPTIONAL CALL { WITH t RETURN t.l AS u }").format("u.a"))
        // With no WHERE … AND … after the join, it closed the session there.
        set("bare", "COUNT { OPTIONAL CALL { WITH t MATCH (z:Nope) RETURN t AS u } RETURN u }")
        assertEquals(listOf(listOf(0L, 2L, 1L)), query("MATCH (n:CyWrite {id: 1}) RETURN [n.optional, n.found, n.bare]"))
    }

    @Test
    fun `datetime's epoch milliseconds and timestamp are the moment the statement runs`() {
        val before = System.currentTimeMillis() - 60_000
        val (millis, stamp) = query("CREATE (n:CyWrite) SET n.at = datetime().epochMillis RETURN [n.at, timestamp()]").single() as List<*>
        val after = System.currentTimeMillis() + 60_000
        assertTrue((millis as Long) in before..after, "$millis")
        assertTrue((stamp as Long) in before..after, "$stamp")
    }

    @Test
    fun `values that may hold a null are null where written alike only when one holds one, and walked where written otherwise`() {
        // #270: on Oracle two written as the same JSON text are null with no walk, so the text must be written alike only where one holds a null.
        assertEquals(
            listOf(listOf(true, null, null, null, null, null)),
            query(
                "CREATE (n:CyWrite) WITH n, CASE WHEN n.x IS NULL THEN [1, 2] ELSE [null] END AS a, CASE WHEN n.x IS NULL THEN [1, null] ELSE [2] END AS c " +
                    "RETURN [a = [1, 2], c = [1, null], c = [1.0, null], {a: 1, b: c[1]} = {b: null, a: 1}, ['é', c[1]] = ['\\u00e9', null], " +
                    "[[1, 2], c] = [[1, 2], [1, null]]]"
            ),
        )
    }

    /** Four nodes, `k` 1 to 4: 1, 2 and 3 in a ring of relationships `i` 1 to 3, 1 with a loop `i` 4 too, and 4 with no relationship. */
    private fun ring() = query(
        "CREATE (a:CyWrite {k: 1}), (b:CyWrite {k: 2}), (c:CyWrite {k: 3}), (:CyWrite {k: 4}), " +
            "(a)-[:R {i: 1}]->(b), (b)-[:R {i: 2}]->(c), (c)-[:R {i: 3}]->(a), (a)-[:R {i: 4}]->(a)",
    )

    private fun ks() = query("MATCH (n:CyWrite) RETURN n.k ORDER BY n.k")

    private fun rs() = query("MATCH ()-[r]->() RETURN r.i ORDER BY r.i")

    @Test
    fun `a DETACH DELETE deletes each node once, however many rows match it, with its relationships`() {
        // #587: the nodes of all the rows are deleted at once. The rows are Neo4j's (2026.10).
        ring()
        query("MATCH (n:CyWrite)-[]-() DETACH DELETE n, n")
        assertEquals(listOf(4L), ks())
        assertEquals(emptyList(), rs())
        // A node read by its label alone: on Oracle the delete's subquery over the node table found none (#587).
        query("MATCH (n:CyWrite) DETACH DELETE n")
        assertEquals(emptyList(), ks())
        ring()
        query("MATCH (n) DETACH DELETE n")
        assertEquals(listOf(0L, 0L), listOf(query("MATCH (n) RETURN count(n)").single(), query("MATCH ()-[r]->() RETURN count(r)").single()))
    }

    @Test
    fun `the rows after a DETACH DELETE are those it matched, with what was read before it`() {
        // #587: a value worked out before the delete is read after it, and a row is given for each match. The rows are Neo4j's (2026.10).
        ring()
        assertEquals(listOf(1L, 1L, 2L, 3L), query("MATCH (n:CyWrite)-[r]->() WITH n, n.k AS k DETACH DELETE n RETURN k ORDER BY k"))
        assertEquals(listOf(4L), ks())
        assertEquals(listOf(1L), query("MATCH (n:CyWrite) DETACH DELETE n RETURN count(*)"))
        assertEquals(emptyList(), ks())
    }

    @Test
    fun `a DETACH DELETE after a WITH that orders and limits deletes the nodes it keeps, and an optional match's null is no node`() {
        // #587: the nodes are read from the WITH's rows. The rows are Neo4j's (2026.10).
        ring()
        query("MATCH (n:CyWrite) WITH n ORDER BY n.k DESC LIMIT 2 DETACH DELETE n")
        assertEquals(listOf(1L, 2L), ks())
        assertEquals(listOf(1L, 4L), rs())
        query("MATCH (n:CyWrite {k: 2}) OPTIONAL MATCH (n)-[:NONE]->(m) DETACH DELETE m")
        assertEquals(listOf(1L, 2L), ks())
        query("OPTIONAL MATCH ()-[r:NONE]->() DELETE r")
        assertEquals(listOf(1L, 4L), rs())
    }

    @Test
    fun `a DETACH DELETE that reads its ids from a match of two nodes deletes only the node the match binds`() {
        // #587: the delete's subquery reads the node table twice, a property of each two ways, as #415's did. The rows are Neo4j's (2026.10).
        query("CREATE (:CyWrite:N1 {k: 1, n: 1}), (:CyWrite:N2 {k: 2, n: 2})")
        query("MATCH (a:N1), (b:N2) WHERE a.n + b.n > 0 AND a.n - b.n < 5 DETACH DELETE a")
        assertEquals(listOf(2L), ks())
        query("CREATE (:CyWrite:N1 {k: 3, n: 1})-[:R {i: 1}]->(:CyWrite:N2 {k: 4, n: 2})")
        query("MATCH (a:N1)-[r]->(b:N2) WHERE a.n + b.n > 0 AND a.n - b.n < 5 DELETE r")
        assertEquals(emptyList(), rs())
        assertEquals(listOf(2L, 3L, 4L), ks())
    }

    @Test
    fun `a DELETE of relationships deletes each once, one found both ways round included`() {
        // #587: the relationships of all the rows are deleted at once. The rows are Neo4j's (2026.10).
        ring()
        assertEquals(listOf(5L), query("MATCH (:CyWrite)-[r]-(:CyWrite) WHERE r.i <> 2 DELETE r RETURN count(*)"))
        assertEquals(listOf(2L), rs())
        query("MATCH ()-[r]->() DELETE r")
        assertEquals(emptyList(), rs())
        assertEquals(listOf(1L, 2L, 3L, 4L), ks())
    }

    @Test
    fun `a DELETE of nodes fails where one keeps a relationship no row deletes, and deletes nothing, and deletes them where the rows delete them all`() {
        // #587: the nodes are deleted once every row's relationships are. The rows are Neo4j's (2026.10).
        ring()
        // Node 1 keeps the relationship from 3.
        assertFailsWith<Exception> { query("MATCH (a:CyWrite {k: 1})-[r]->() DELETE r, a") }
        assertEquals(listOf(1L, 2L, 3L, 4L), ks())
        assertEquals(listOf(1L, 2L, 3L, 4L), rs())
        // Each row deletes one of node 1's relationships, and node 1 is deleted once.
        query("MATCH (a:CyWrite {k: 1})-[r]-() DELETE r, a")
        assertEquals(listOf(2L, 3L, 4L), ks())
        assertEquals(listOf(2L), rs())
        // A node on each row of a cross product, deleted once.
        query("MATCH (a:CyWrite {k: 4}), (b:CyWrite) DELETE a")
        assertEquals(listOf(2L, 3L), ks())
    }

    @Test
    fun `a DETACH DELETE beside a DELETE in one statement, in a CALL and in a FOREACH, deletes what Neo4j does`() {
        // #587: in a CALL { … } and a FOREACH, each row is written alone. The rows are Neo4j's (2026.10).
        ring()
        // The relationship from 1 to 2 goes with node 1, and the DELETE after it finds it gone.
        query("MATCH (a:CyWrite {k: 1})-[r]->(:CyWrite {k: 2}) DETACH DELETE a DELETE r")
        assertEquals(listOf(2L, 3L, 4L), ks())
        assertEquals(listOf(2L), rs())
        assertEquals(listOf(2L), query("MATCH (n:CyWrite) WHERE n.k > 2 CALL (n) { DETACH DELETE n } RETURN count(*)"))
        assertEquals(listOf(2L), ks())
        query("CREATE (:CyWrite {k: 5})-[:R {i: 5}]->(:CyWrite {k: 6})")
        query("MATCH (n:CyWrite) WITH collect(n) AS ns FOREACH (n IN ns | DETACH DELETE n)")
        assertEquals(emptyList(), ks())
        assertEquals(emptyList(), rs())
    }

    @Test
    fun `a DELETE of an optional match's variable, null on some rows, deletes those of the rest, and one of no rows deletes nothing`() {
        // #587: a null id is in the set a DELETE reads, and deletes nothing. The rows are Neo4j's (2026.10).
        ring()
        query("MATCH (a:CyWrite) OPTIONAL MATCH (a)-[r:R]->(:CyWrite {k: 2}) DELETE r")
        assertEquals(listOf(2L, 3L, 4L), rs())
        query("MATCH (a:CyWrite) OPTIONAL MATCH (a)-[:R]->(m:CyWrite {k: 3}) DETACH DELETE m")
        assertEquals(listOf(1L, 2L, 4L), ks())
        assertEquals(listOf(4L), rs())
        // Node 4 has no relationship, so its row's is null.
        query("MATCH (a:CyWrite) WHERE a.k IN [1, 4] OPTIONAL MATCH (a)-[r]-() DELETE r, a")
        assertEquals(listOf(2L), ks())
        assertEquals(emptyList(), rs())
        query("MATCH (n:None) DELETE n")
        assertEquals(listOf(0L), query("MATCH (n:None) DETACH DELETE n RETURN count(*)"))
        assertEquals(listOf(2L), ks())
    }

    @Test
    fun `the rows a DELETE leaves are written by the clauses after it, and a DELETE in a FOREACH deletes each item alone`() {
        // #587: a DELETE leaves its rows as they were. The rows are Neo4j's (2026.10).
        ring()
        query("MATCH (n:CyWrite) WHERE n.k > 2 DETACH DELETE n WITH count(*) AS c CREATE (:CyWrite {k: 10 + c})")
        assertEquals(listOf(1L, 2L, 12L), ks())
        assertEquals(listOf(1L, 4L), rs())
        query("MATCH (a:CyWrite {k: 1})-[r]-() WITH a, collect(r) AS rs FOREACH (r IN rs | DELETE r) WITH a DELETE a")
        assertEquals(listOf(2L, 12L), ks())
        assertEquals(emptyList(), rs())
    }
}

class CypherWriteNeo4jTest : CypherWriteContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-cypher-write", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

@PostgresTests
class CypherWritePostgresTest : CypherWriteContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = PostgresTestDatabase.clear(manager)

    @Test
    fun `a write in a transaction rolled back leaves nothing, and runs again after`() {
        val connection = PostgresTestDatabase.connections(manager).connect()
        fun query(cypher: String) = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        connection.startTransaction()
        try {
            query("CREATE (:CyWrite {name: 'Ada'})")
            assertEquals(listOf("Ada"), query("MATCH (n:CyWrite) RETURN n.name"))
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
        assertEquals(listOf(0L), pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN count(n)"))))
        // The function the write was compiled to went with the transaction, and is made again.
        pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (:CyWrite {name: 'Ada'})")))
        assertEquals(listOf(1L), pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN count(n)"))))
    }
}

@OracleTests
class CypherWriteOracleTest : CypherWriteContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = OracleTestDatabase.clear(manager)

    @Test
    fun `a store without the function that compares values holding a null compares them as before, until the graph is ensured again`() {
        // A store an earlier version made, or whose user may not make a function (#270): a read declares the function itself.
        val graph = "cy_no_fn"
        val noFunction = OracleTestDatabase.graphManager(graph)
        OracleTestDatabase.drop("FUNCTION ${OracleGraph(graph).nullsEqual}")
        fun query(cypher: String) = noFunction.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        val write = "CREATE (n:CyWrite) WITH n, [null] AS a, [1] AS b, [null] AS c RETURN [coalesce(a = b, 'null'), coalesce(a = c, 'null')]"
        try {
            assertEquals(listOf(listOf(false, "null")), query(write))
            assertEquals(listOf(listOf("null", "null")), query("WITH [null] AS a, [1] AS b, [null] AS c RETURN [coalesce(a = b, 'null'), coalesce(a = c, 'null')]"))
            // The store keeps its era functions (drivine#572): a statement that calls them too is compiled again to call them and not nulls_equal.
            assertEquals(listOf(listOf(false, "-0044-03-16")), query("CREATE (n:CyWrite {day: date('-0044-03-15') + duration('P1D')}) WITH n, [null] AS a, [1] AS b RETURN [coalesce(a = b, 'null'), toString(n.day)]"))
            OracleGraph(graph).ensure(noFunction)
            assertEquals(listOf(listOf("null", "null")), query(write))
        } finally {
            OracleTestDatabase.clear(noFunction)
        }
    }

    @Test
    fun `nodes of two labels a transaction has made and not yet committed are found by both`() {
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String) = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        connection.startTransaction()
        try {
            query("CREATE (:CyWrite:Dog {name: 'Buddy'}), (:CyWrite:Cat {name: 'Tom'}), (:CyWrite:Dog {name: 'Max'})")
            assertEquals(listOf("Buddy", "Max"), query("MATCH (d:CyWrite:Dog) RETURN d.name ORDER BY d.name"))
            assertEquals(listOf("Tom"), query("MATCH (c) WHERE c:Cat:CyWrite RETURN c.name"))
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
    }

    @Test
    fun `a walk either way around a cycle finds its rows in a second session too`() {
        // Oracle adapted the plan of the walk's step as the first session ran it, and the second then found no rows (#182).
        pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (a:CyWrite {id: 'a'})-[:R]->(:CyWrite {id: 'b'})-[:R]->(:CyWrite {id: 'c'})-[:R]->(a)")))
        val walk = QuerySpecification.withStatement(CypherStatement("MATCH (:CyWrite {id: 'a'})-[rs:R*]-(n) RETURN [n.id, size(rs)] ORDER BY size(rs), n.id"))
        val rows = listOf(listOf("b", 1L), listOf("c", 1L), listOf("b", 2L), listOf("c", 2L), listOf("a", 3L), listOf("a", 3L))
        val connections = OracleTestDatabase.connections(manager).let { provider -> listOf(provider.connect(), provider.connect()) }
        try {
            val sessions = connections.map { connection ->
                assertEquals<List<Any?>>(rows, connection.query(walk))
                connection.query(QuerySpecification.withStatement("SELECT SYS_CONTEXT('USERENV', 'SID') FROM DUAL")).single()
            }
            assertTrue(sessions[0] != sessions[1], "$sessions")
        } finally {
            connections.forEach { it.release() }
        }
    }

    @Test
    fun `a text past 32 KB set on two nodes, beside a whole float or not, leaves the session open`() {
        // Oracle crashed the session as the block freed its parameters, held in a JSON variable it had read once for each
        // row (#250). The crash could come after the rows did, so the session is asked again; and a block parsed before
        // rarely crashed, so each statement is new. The nodes are read back whole: see "A parameter past 32 KB" in the spec.
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
            connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        try {
            query("CREATE (:CyWrite {id: 1}), (:CyWrite {id: 2})")
            val text = "x".repeat(40_000)
            val written = mutableMapOf<String, Any?>()
            repeat(3) { i ->
                query("MATCH (n:CyWrite) SET n.a$i = \$a$i", mapOf("a$i" to text))
                query("MATCH (n:CyWrite) SET n.b$i = \$b$i, n.f$i = \$f$i", mapOf("b$i" to text, "f$i" to 2.0 + i))
                written += mapOf("a$i" to text, "b$i" to text, "f$i" to 2.0 + i)
                assertEquals(listOf(1L, 2L).map { mapOf("id" to it) + written }, query("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.id"))
            }
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a property past 32 KB read on two nodes leaves the session open`() {
        // Oracle crashed the session where a statement took a text past 32 KB from the properties with JSON_QUERY as JSON,
        // and fed it on into further SQL, for each of two rows (#255). The compiler reads a property a RETURN or a WITH
        // gives through its text to work round it: see OracleCypherCompiler.itemProperty. As above, each statement is
        // new, and the session is asked again after it.
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
            connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        try {
            val texts = listOf("x", "y", "z").map { it.repeat(40_000) }
            query("UNWIND range(1, 3) AS id CREATE (:CyWrite {id: id})")
            // Each set by a statement of its own: a write that reads a long text from an unwound parameter, for each of two
            // rows, is not what this test is about.
            texts.forEachIndexed { i, a -> query("MATCH (n:CyWrite {id: \$id}) SET n.a = \$a", mapOf("id" to i + 1, "a" to a)) }
            query("MATCH (a:CyWrite {id: 1}), (b:CyWrite {id: 2}) CREATE (a)-[:CY_LONG {a: \$a}]->(b), (b)-[:CY_LONG {a: \$a}]->(a)", mapOf("a" to texts[0]))
            repeat(3) { i ->
                assertEquals(texts.toSet(), query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN n.a").toSet())
                assertEquals(texts, query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN n.a ORDER BY n.id"))
                // Not worked around: Oracle sorts and groups JSON only up to about 32 KB. It refuses the statement (ORA-40180),
                // and the session stays open for the statements after it.
                listOf("RETURN n.a ORDER BY n.a", "RETURN DISTINCT n.a").forEach { rest ->
                    val refused = assertFailsWith<Exception> { query("MATCH (n:CyWrite) WHERE n.id > -$i $rest") }
                    assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { "ORA-40180" in it.message.orEmpty() }, refused.toString())
                }
                assertEquals(
                    texts.mapIndexed { j, a -> listOf((j + 1).toLong(), a) },
                    query("MATCH (n:CyWrite) WITH n.id AS id, n.a AS a WHERE id > -$i RETURN [id, a] ORDER BY id"),
                )
                assertEquals(listOf(texts[0], texts[0]), query("MATCH ()-[r:CY_LONG]->() RETURN r.a AS a$i"))
                assertEquals(listOf(3L), query("MATCH (n:CyWrite) RETURN count(n) AS c$i"))
            }
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a text past 32 KB set from an unwound parameter on two nodes leaves the session open`() {
        // Oracle crashed the session where the block took a text past 32 KB from an item of an unwound parameter, as JSON,
        // for each of two rows (#309). The compiler reads a text a write takes from its row through the row's text to work
        // round it: see OracleCypherCompiler.rowValue. As above, each statement is new, and the session is asked again after it.
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
            connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        try {
            val texts = listOf("x", "y", "z").map { it.repeat(40_000) }
            val items = mapOf("items" to texts.mapIndexed { i, a -> mapOf("id" to i + 1, "a" to a, "f" to 5.0) })
            query("UNWIND range(1, 3) AS id CREATE (:CyWrite {id: id})")
            val written = mutableListOf<String>()
            repeat(3) { i ->
                query("UNWIND \$items AS t MATCH (n:CyWrite {id: t.id}) SET n.a$i = t.a", items)
                query("UNWIND \$items AS t MERGE (n:CyWrite {id: t.id}) SET n.m$i = t.a", items)
                query("UNWIND \$items AS t CREATE (:CyMade {id: t.id, a: t.a, i: $i})", items)
                query("UNWIND \$texts AS t CREATE (:CyBare {a: t, i: $i})", mapOf("texts" to texts))
                written += listOf("a$i", "m$i")
                assertEquals(
                    texts.mapIndexed { j, a -> mapOf("id" to (j + 1).toLong()) + written.associateWith { a } },
                    query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN properties(n) ORDER BY n.id"),
                )
                // Each read as an item of its own: one inside a list isn't worked around (see itemProperty).
                assertEquals(texts, query("MATCH (n:CyMade {i: $i}) RETURN n.a ORDER BY n.id"))
                assertEquals(texts.toSet(), query("MATCH (n:CyBare {i: $i}) RETURN n.a").toSet())
                assertEquals(listOf(3L), query("MATCH (n:CyWrite) RETURN count(n) AS c$i"))
            }
            // A map item that holds the long text, set whole: its whole float stays a float.
            query("UNWIND \$items AS t MATCH (n:CyWrite {id: t.id}) SET n += t", items)
            assertEquals(texts, query("MATCH (n:CyWrite) RETURN n.a ORDER BY n.id"))
            assertEquals(listOf(5.0, 5.0, 5.0), query("MATCH (n:CyWrite) RETURN n.f ORDER BY n.id"))
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a text past 32 KB from an unwound parameter returned or passed on, on two rows, leaves the session open`() {
        // Oracle crashed the session where a statement took a text past 32 KB from an item of an unwound parameter as JSON, and
        // returned it or passed it on, for each of two rows (#322). The compiler reads such a value through the item's text to
        // work round it: see OracleCypherCompiler.itemValue. As above, each statement is new, and the session is asked again after it.
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
            connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        try {
            val texts = listOf("x", "y", "z").map { it.repeat(40_000) }
            val items = mapOf("items" to texts.mapIndexed { i, a -> mapOf("id" to i + 1, "a" to a) })
            query("UNWIND range(1, 3) AS id CREATE (:CyWrite {id: id})")
            repeat(3) { i ->
                fun alive() = assertEquals(listOf(1L), query("RETURN 1 AS alive$i"))
                assertEquals(texts, query("UNWIND \$items AS t RETURN t.a AS a$i", items)).also { alive() }
                assertEquals(texts, query("UNWIND \$items AS t WITH t.a AS a RETURN a AS a$i", items)).also { alive() }
                assertEquals(texts, query("UNWIND \$items AS t WITH t RETURN t.a AS a$i", items)).also { alive() }
                query("UNWIND \$items AS t WITH t.a AS a, t.id AS id CREATE (:CyMade {id: id, a: a, i: $i})", items).also { alive() }
                assertEquals(texts, query("UNWIND \$items AS t MATCH (n:CyWrite {id: t.id}) SET n.a$i = t.a RETURN t.a AS a$i", items)).also { alive() }
                assertEquals(texts, query("MATCH (n:CyMade {i: $i}) RETURN n.a ORDER BY n.id"))
                assertEquals(texts, query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN n.a$i ORDER BY n.id"))
            }
            // Not worked around: a pattern that matches on such a text compares it as JSON, which Oracle refuses past about 32 KB
            // (ORA-40180), as it refuses to sort or group it, and the session stays open.
            val refused = assertFailsWith<Exception> { query("UNWIND \$items AS t MERGE (n:CyMerged {a: t.a})", items) }
            assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { "ORA-40180" in it.message.orEmpty() }, refused.toString())
            assertEquals(listOf(3L), query("MATCH (n:CyWrite) RETURN count(n)"))
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a map an unwound item holds, with a key as extended JSON's, is written as the map it is`() {
        // Only a text a write takes from its row is read through the row's text (#309): a map is read as JSON, as before, and
        // not from extended JSON, which would read {"${'$'}numberLong": "5"} as the number 5.
        val items = listOf(mapOf("id" to 1, "m" to mapOf("\$numberLong" to "5")), mapOf("id" to 2, "m" to mapOf("\$numberDouble" to "2.0")))
        pm.query(QuerySpecification.withStatement(CypherStatement("UNWIND \$items AS t CREATE (:CyWrite {id: t.id, m: t.m})")).bind(mapOf("items" to items)))
        assertEquals(
            items.map { mapOf("id" to (it["id"] as Int).toLong(), "m" to it["m"]) },
            pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.id"))),
        )
    }

    @Test
    fun `a parameter's property past 32 KB read and set on two nodes leaves the session open`() {
        // Oracle crashed the session where a statement read a property past 32 KB of a parameter, for each of two rows,
        // taking the parameter as JSON first and then the property from it (#256), by a property or by a subscript.
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
            connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        try {
            query("CREATE (:CyWrite {id: 1}), (:CyWrite {id: 2})")
            val text = "x".repeat(40_000)
            val p = mapOf("p" to mapOf("text" to text))
            val written = mutableMapOf<String, Any?>()
            repeat(3) { i ->
                assertEquals(listOf(text, text), query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN \$p.text ORDER BY n.id", p))
                assertEquals(listOf(text, text), query("MATCH (n:CyWrite) WHERE n.id > -$i RETURN \$p['text'] ORDER BY n.id", p))
                query("MATCH (n:CyWrite) SET n.t$i = \$p.text", p)
                written += mapOf("t$i" to text)
                assertEquals(listOf(1L, 2L).map { mapOf("id" to it) + written }, query("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.id"))
            }
        } finally {
            connection.release()
        }
    }
}

class CypherWriteSqliteTest : CypherWriteContract() {
    private val manager = SqliteTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = SqliteTestDatabase.clear(manager)

    @Test
    fun `a write in a transaction rolled back leaves nothing, and a write that fails leaves nothing of what it wrote`() {
        val connection = SqliteTestDatabase.connections(manager).connect()
        fun query(cypher: String) = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        connection.startTransaction()
        try {
            query("CREATE (:CyWrite {name: 'Ada'})")
            assertEquals(listOf("Ada"), query("MATCH (n:CyWrite) RETURN n.name"))
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
        fun count() = pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN count(n)")))
        assertEquals(listOf(0L), count())
        // The second row's delete fails, and the first row's node goes back with it.
        pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (:CyWrite {name: 'Ada'})-[:KNOWS]->(:CyWrite {name: 'Bob'})")))
        assertFailsWith<Exception> { pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) CREATE (:CyWrite {name: 'Copy'}) DELETE n"))) }
        assertEquals(listOf(2L), count())
    }
}
