package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
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
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertTrue

/**
 * A Cypher statement that writes runs on a SQL store, compiled to a procedure of its SQL, and leaves the
 * graph a graph engine leaves: the same statements are run on Neo4j, Postgres and Oracle, and read back.
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

    @Test
    fun `a create makes its nodes and relationships, and returns what it made`() {
        assertEquals(
            listOf(listOf("Ada", "Bob")),
            query("CREATE (a:CyWrite:Person {name: 'Ada', tags: ['x', 'y'], none: null})-[:KNOWS {since: 2020}]->(b:CyWrite {name: 'Bob'}) RETURN a.name, b.name"),
        )
        assertEquals(
            listOf(mapOf("from" to "Ada", "since" to 2020L, "to" to "Bob", "tags" to listOf("x", "y"), "person" to true, "none" to true)),
            query(
                """
                MATCH (a:CyWrite)-[r:KNOWS]->(b:CyWrite)
                RETURN {from: a.name, since: r.since, to: b.name, tags: a.tags, person: a:Person, none: a.none IS NULL}
                """
            ),
        )
        // A pattern of nodes bound before it makes only its relationship.
        query("MATCH (a:CyWrite {name: 'Ada'}), (b:CyWrite {name: 'Bob'}) CREATE (b)-[:KNOWS]->(a)")
        assertEquals(listOf("Ada", "Bob"), query("MATCH (n:CyWrite)-[:KNOWS]->(:CyWrite) RETURN n.name ORDER BY n.name"))
        assertEquals(listOf(2L), query("MATCH (n:CyWrite) RETURN count(n)"))
    }

    @Test
    fun `a create of a node from a map leaves out its nulls, and a write with no return returns no rows`() {
        assertEquals(
            emptyList(),
            query("CREATE (n:CyWrite \$props)", mapOf("props" to mapOf("name" to "Ada", "age" to 36, "gone" to null))),
        )
        assertEquals(listOf(mapOf("name" to "Ada", "age" to 36L)), query("MATCH (n:CyWrite) RETURN properties(n)"))
    }

    @Test
    fun `a merge finds what is there, or what an earlier row made, and makes it once`() {
        assertEquals(
            listOf("x", "y", "x"),
            query(
                """
                UNWIND ['x', 'y', 'x'] AS k
                MERGE (n:CyWrite {key: k})
                ON CREATE SET n.made = true
                ON MATCH SET n.seen = coalesce(n.seen, 0) + 1
                RETURN n.key
                """
            ),
        )
        assertEquals(
            listOf(mapOf("key" to "x", "made" to true, "seen" to 1L), mapOf("key" to "y", "made" to true)),
            query("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.key"),
        )
        // A relationship merged between two nodes is made once.
        repeat(2) { query("MATCH (x:CyWrite {key: 'x'}), (y:CyWrite {key: 'y'}) MERGE (x)-[r:NEXT]->(y) ON CREATE SET r.made = true") }
        assertEquals(listOf(true), query("MATCH (:CyWrite {key: 'x'})-[r:NEXT]->(:CyWrite {key: 'y'}) RETURN r.made"))
        // One merged either way is found either way, and made from the pattern's earlier node.
        query("MATCH (x:CyWrite {key: 'x'}), (y:CyWrite {key: 'y'}) MERGE (y)-[:NEXT]-(x)")
        assertEquals(listOf(1L), query("MATCH (:CyWrite)-[r:NEXT]->(:CyWrite) RETURN count(r)"))
        query("MATCH (x:CyWrite {key: 'x'}), (y:CyWrite {key: 'y'}) MERGE (y)-[:BACK]-(x)")
        assertEquals(listOf("y"), query("MATCH (a:CyWrite)-[:BACK]->(:CyWrite) RETURN a.key"))
    }

    @Test
    fun `a merge of a node keyed by a parameter's property, then set from the parameter, as drivine's repositories write`() {
        val task = mapOf("id" to "t1", "title" to "Write", "tags" to listOf("a"), "done" to false)
        assertEquals(
            listOf(task),
            query("MERGE (t:CyWrite {id: \$task.id}) SET t += \$task RETURN properties(t)", mapOf("task" to task)),
        )
        assertEquals(
            listOf(mapOf("id" to "t1", "title" to "Written", "tags" to listOf("a"), "done" to true)),
            query("MERGE (t:CyWrite {id: \$task.id}) SET t += \$task RETURN properties(t)", mapOf("task" to mapOf("id" to "t1", "title" to "Written", "done" to true))),
        )
        assertEquals(listOf(1L), query("MATCH (t:CyWrite) RETURN count(t)"))
    }

    @Test
    fun `rows of a parameter are merged, related, read back as a view reads them, and detach deleted, as drivine's users write them`() {
        val rows = listOf(
            mapOf("id" to "a", "props" to mapOf("id" to "a", "name" to "O'Brien \"q\" ünï 😀", "score" to 1.5, "tags" to listOf("x", "y"), "ok" to true)),
            mapOf("id" to "b", "props" to mapOf("id" to "b", "name" to "Bea", "score" to 2L, "tags" to emptyList<String>(), "ok" to false)),
        )
        assertEquals(
            listOf("a", "b"),
            query("UNWIND \$rows AS row MERGE (n:CyWrite {id: row.id}) SET n += row.props RETURN n.id ORDER BY n.id", mapOf("rows" to rows)),
        )
        assertEquals(
            listOf(rows[0]["props"]),
            query("MATCH (n:CyWrite {id: \$id}) RETURN properties(n)", mapOf("id" to "a")),
        )
        assertEquals(
            listOf(mapOf("id" to "c", "name" to "C")),
            query("CREATE (p:CyWrite) SET p = \$props RETURN properties(p)", mapOf("props" to mapOf("id" to "c", "name" to "C", "gone" to null))),
        )
        assertEquals(
            listOf(1L),
            query("MATCH (a:CyWrite {id: \$a}), (b:CyWrite {id: \$b}) MERGE (a)-[r:CyKNOWS]->(b) ON CREATE SET r.since = \$s RETURN count(r)", mapOf("a" to "a", "b" to "b", "s" to 2020)),
        )
        query("MATCH (a:CyWrite {id: \$a}), (b:CyWrite {id: \$b}) MERGE (a)-[r:CyKNOWS]->(b) ON MATCH SET r.again = true", mapOf("a" to "a", "b" to "b"))
        assertEquals(
            listOf(mapOf("person" to mapOf("id" to "a", "name" to "O'Brien \"q\" ünï 😀", "score" to 1.5, "tags" to listOf("x", "y"), "ok" to true),
                "friends" to listOf(mapOf("id" to "b", "name" to "Bea", "since" to 2020L, "again" to true)), "labels" to listOf("CyWrite"))),
            query(
                """
                MATCH (person:CyWrite {id: ${'$'}id})
                WITH person, [(person)-[r:CyKNOWS]->(f:CyWrite) | f {.id, .name, since: r.since, again: r.again}] AS friends
                RETURN { person: properties(person), friends: friends, labels: labels(person) }
                """,
                mapOf("id" to "a"),
            ),
        )
        assertEquals(listOf("a"), query("MATCH (n:CyWrite) WHERE 'x' IN n.tags AND toLower(n.name) CONTAINS toLower(\$q) RETURN n.id", mapOf("q" to "o'b")))
        assertEquals(listOf(null), query("OPTIONAL MATCH (n:CyWrite {id: 'zz'}) RETURN n"))
        assertEquals(listOf(mapOf("total" to 3L, "best" to 2L)), query("MATCH (n:CyWrite) RETURN {total: count(n), best: max(n.score)}"))
        assertEquals(listOf("b"), query("MATCH (n:CyWrite) RETURN n.id ORDER BY n.id SKIP \$s LIMIT \$l", mapOf("s" to 1, "l" to 1)))
        assertEquals(listOf(mapOf("n" to "Bea", "c" to "none")), query("MATCH (n:CyWrite {id: 'b'}) RETURN {n: coalesce(n.missing, n.name), c: CASE WHEN size(n.tags) = 0 THEN 'none' ELSE 'some' END}"))
        assertEquals(listOf("a"), query("MATCH (n:CyWrite) WHERE EXISTS { (n)-[:CyKNOWS]->(:CyWrite) } RETURN n.id"))
        assertEquals(listOf(listOf("O'Brien \"q\" ünï 😀", "Bea")), query("MATCH (n:CyWrite) WITH n ORDER BY n.id LIMIT 2 RETURN collect(n.name)"))
        query("UNWIND \$rels AS rel MATCH (a:CyWrite {id: rel.from}), (b:CyWrite {id: rel.to}) MERGE (a)-[:CyLIKES {w: rel.w}]->(b)", mapOf("rels" to listOf(mapOf("from" to "b", "to" to "c", "w" to 1), mapOf("from" to "c", "to" to "a", "w" to 2))))
        assertEquals(listOf(listOf("b", "c", 1L), listOf("c", "a", 2L)), query("MATCH (a)-[r:CyLIKES]->(b) RETURN [a.id, b.id, r.w] ORDER BY r.w"))
        query("MATCH (n:CyWrite) WHERE n.id IN \$ids DETACH DELETE n", mapOf("ids" to listOf("a", "b")))
        assertEquals(listOf("c"), query("MATCH (n:CyWrite) RETURN n.id"))
        assertEquals(listOf(0L), query("MATCH ()-[r:CyKNOWS]->() RETURN count(r)"))
        val big = mapOf("id" to "big", "n" to 9007199254740993L, "f" to 0.1, "neg" to -42L, "s" to "1e3", "long" to "x".repeat(3000), "e" to "")
        assertEquals(listOf(big), query("CREATE (n:CyWrite) SET n = \$p RETURN properties(n)", mapOf("p" to big)))
        assertEquals(listOf(9007199254740994L), query("MATCH (n:CyWrite {id: 'big'}) RETURN n.n + 1"))
        assertEquals(listOf("big"), query("MATCH (n:CyWrite) WHERE n.n = \$n RETURN n.id", mapOf("n" to 9007199254740993L)))
    }

    @Test
    fun `set writes a property, replaces or adds a map, a null removing its key, and a label, and remove takes them away`() {
        query("CREATE (:CyWrite {id: 1, a: 'a', b: 'b'})")
        assertEquals(
            listOf(mapOf("id" to 1L, "a" to "A", "c" to 3L)),
            query("MATCH (n:CyWrite {id: 1}) SET n.a = toUpper(n.a), n.c = 3, n.b = null RETURN properties(n)"),
        )
        assertEquals(
            listOf(mapOf("id" to 1L, "a" to "A", "d" to "d")),
            query("MATCH (n:CyWrite {id: 1}) SET n += \$more RETURN properties(n)", mapOf("more" to mapOf("c" to null, "d" to "d"))),
        )
        assertEquals(
            listOf(mapOf("id" to 2L)),
            query("MATCH (n:CyWrite {id: 1}) SET n = \$whole RETURN properties(n)", mapOf("whole" to mapOf("id" to 2, "gone" to null))),
        )
        assertEquals(listOf(listOf(true, true)), query("MATCH (n:CyWrite {id: 2}) SET n:Done:Seen RETURN n:Done, n:Seen"))
        assertEquals(listOf(true), query("MATCH (n:CyWrite {id: 2}) SET n:Done RETURN n:Done"))
        assertEquals(
            listOf(listOf(false, true, null)),
            query("MATCH (n:CyWrite {id: 2}) REMOVE n:Done, n.id SET n.x = 1 REMOVE n.x RETURN n:Done, n:Seen, n.id"),
        )
    }

    @Test
    fun `set and remove write a relationship's properties, and a node's may be set from a relationship's properties`() {
        query("CREATE (:CyWrite {name: 'a'})-[:R {x: 1, y: 2}]->(:CyWrite {name: 'b'})")
        assertEquals(listOf(mapOf("x" to 1L, "z" to 3L)), query("MATCH (:CyWrite)-[r:R]->() SET r += {y: null, z: 3} RETURN properties(r)"))
        assertEquals(listOf(mapOf("w" to 4L)), query("MATCH (:CyWrite)-[r:R]->() SET r = {w: 4, gone: null} RETURN properties(r)"))
        assertEquals(listOf(mapOf("v" to 5L)), query("MATCH (:CyWrite)-[r:R]->() REMOVE r.w SET r.v = 5 RETURN properties(r)"))
        assertEquals(listOf(mapOf("v" to 5L)), query("MATCH (a:CyWrite {name: 'a'})-[r:R]->() SET a = properties(r) RETURN properties(a)"))
    }

    @Test
    fun `a write's return is paged by parameters, and a delete of what an optional match did not find deletes nothing`() {
        assertEquals(
            listOf(2L),
            query("UNWIND [3, 1, 2] AS i CREATE (n:CyWrite {i: i}) RETURN n.i ORDER BY n.i SKIP \$from LIMIT \$most", mapOf("from" to 1, "most" to 1)),
        )
        assertEquals(listOf(1L), query("MATCH (n:CyWrite {i: 1}) OPTIONAL MATCH (n)-[r:NONE]->() DELETE r RETURN n.i"))
        assertEquals(listOf(3L), query("MATCH (n:CyWrite) RETURN count(n)"))
    }

    @Test
    fun `delete takes a relationship, and a node with no relationships, and detach delete a node with its relationships`() {
        query("CREATE (:CyWrite {name: 'a'})-[:R]->(:CyWrite {name: 'b'})-[:R]->(:CyWrite {name: 'c'})")
        // A node with relationships is not deleted, and the statement leaves nothing it did behind.
        assertThrows<Exception> { query("CREATE (:CyWrite {name: 'temp'}) WITH 1 AS one MATCH (n:CyWrite {name: 'b'}) DELETE n") }
        assertEquals(listOf("a", "b", "c"), query("MATCH (n:CyWrite) RETURN n.name ORDER BY n.name"))
        // The relationships a clause deletes are gone before its nodes are.
        query("MATCH (a:CyWrite {name: 'a'})-[r:R]->(b) DELETE r, a")
        assertEquals(listOf("b", "c"), query("MATCH (n:CyWrite) RETURN n.name ORDER BY n.name"))
        query("MATCH (n:CyWrite {name: 'b'}) DETACH DELETE n")
        assertEquals(listOf("c"), query("MATCH (n:CyWrite) RETURN n.name"))
        assertEquals(emptyList(), query("MATCH (:CyWrite)-[r]-() RETURN r"))
    }

    @Test
    fun `a read after a write sees what it wrote`() {
        query("CREATE (:CyWrite {name: 'Ada'})")
        assertEquals(
            listOf(listOf("Notes", 1L)),
            query(
                """
                MATCH (a:CyWrite {name: 'Ada'})
                CREATE (a)-[:WROTE]->(:CyWrite:Paper {title: ${'$'}title})
                WITH a
                MATCH (a)-[:WROTE]->(p:Paper)
                RETURN p.title, count(*)
                """,
                mapOf("title" to "Notes"),
            ),
        )
    }

    @Test
    fun `rows keep their order through writes, and a with between writes orders, filters and counts them`() {
        assertEquals(listOf("c", "a", "b"), query("UNWIND ['c', 'a', 'b'] AS k CREATE (n:CyWrite {k: k}) SET n.seen = true RETURN n.k"))
        assertEquals(
            listOf("c", "b"),
            query("MATCH (n:CyWrite) WITH n ORDER BY n.k DESC WHERE n.k <> 'a' SET n.last = true RETURN n.k"),
        )
        assertEquals(
            listOf(listOf(3L, 2L)),
            query("MATCH (n:CyWrite) SET n.x = 1 WITH count(n) AS c CREATE (m:CyWrite {c: c}) WITH m MATCH (l:CyWrite {last: true}) WITH m.c AS c, count(l) AS l RETURN [c, l]"),
        )
        // A match that finds nothing leaves no row to write for.
        assertEquals(emptyList(), query("MATCH (n:CyWrite {k: 'none'}) CREATE (n)-[:R]->(:CyWrite) RETURN n"))
        assertEquals(listOf(4L), query("MATCH (n:CyWrite) RETURN count(n)"))
    }

    @Test
    fun `a write before or after a projection that groups by a key of a parameter or a subquery`() {
        assertEquals(
            listOf(listOf("a!", 2L), listOf("b!", 1L)),
            query("UNWIND ['a', 'b', 'a'] AS k CREATE (n:CyWrite {k: k}) WITH n RETURN n.k + \$x AS k, count(*) AS c ORDER BY k", mapOf("x" to "!")),
        )
        query("MATCH (a:CyWrite {k: 'b'}) CREATE (a)-[:R]->(:CyWrite {k: 'c'})")
        assertEquals(
            listOf(listOf(listOf<Any?>(), 3L), listOf(listOf("c"), 1L)),
            query(
                """
                MATCH (n:CyWrite)
                WITH [(n)-[:R]->(m) | m.k] AS ks, count(*) AS c
                CREATE (t:CyWrite:Tally {c: c})
                RETURN ks, t.c ORDER BY t.c DESC
                """
            ),
        )
    }

    @Test
    fun `a merge of a whole pattern makes all of it once, and one found twice is a row for each`() {
        repeat(2) { query("MERGE (a:CyWrite {k: 'a'})-[:R]->(b:CyWrite {k: 'b'}) ON CREATE SET a.made = 1 ON MATCH SET a.made = a.made + 1") }
        assertEquals(listOf(listOf(2L, 1L)), query("MATCH (a:CyWrite {k: 'a'})-[r:R]->(:CyWrite {k: 'b'}) WITH a.made AS made, count(r) AS r RETURN [made, r]"))
        query("MATCH (a:CyWrite {k: 'a'}) CREATE (a)-[:R]->(:CyWrite {k: 'b'})")
        assertEquals(
            listOf(listOf(4L, 2L)),
            query("MERGE (a:CyWrite {k: 'a'})-[r:R]->(b:CyWrite {k: 'b'}) ON MATCH SET a.made = a.made + 1, b.hit = true WITH a.made AS made, count(b) AS found RETURN [made, found]"),
        )
        assertEquals(listOf(2L), query("MATCH (b:CyWrite {hit: true}) RETURN count(b)"))
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
    fun `drivine's stamp in a map a node is made from, replaced by or added from is written as the node's property is`() {
        val stamp = Stamps.QUOTED
        query("CREATE (:CyWrite \$props)", mapOf("props" to mapOf("id" to 1, Stamps.PROPERTY to "a:b")))
        assertEquals(listOf("a:b"), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
        query("MATCH (n:CyWrite {id: 1}) SET n += \$more", mapOf("more" to mapOf("x" to 1)))
        assertEquals(listOf("a:b"), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
        query("MATCH (n:CyWrite {id: 1}) SET n += \$more", mapOf("more" to mapOf(Stamps.PROPERTY to "c:d")))
        assertEquals(listOf(mapOf("id" to 1L, "x" to 1L, Stamps.PROPERTY to "c:d")), query("MATCH (n:CyWrite {id: 1}) RETURN properties(n)"))
        query("MATCH (n:CyWrite {id: 1}) SET n = {id: 1}")
        assertEquals(listOf(mapOf("id" to 1L)), query("MATCH (n:CyWrite {id: 1}) RETURN properties(n)"))
        assertEquals(listOf(null), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
    }

    @Test
    fun `foreach writes its clauses for each item, and leaves the rows as they were`() {
        query("CREATE (:CyWrite {id: 'root'}), (:CyWrite {id: 'other'})")
        assertEquals(
            listOf("root"),
            query("MATCH (r:CyWrite {id: 'root'}) FOREACH (name IN ['a', 'b'] | CREATE (r)-[:HAS]->(:CyWrite {id: name})) RETURN r.id"),
        )
        assertEquals(listOf("a", "b"), query("MATCH (:CyWrite {id: 'root'})-[:HAS]->(c) RETURN c.id ORDER BY c.id"))
        // Merged once for each item, whichever row writes it first.
        assertEquals(
            listOf(2L),
            query("MATCH (n:CyWrite) WHERE n.id IN ['root', 'other'] FOREACH (x IN [1, 2] | MERGE (:CyWrite {id: 'm' + toString(x)})) RETURN count(*)"),
        )
        assertEquals(listOf("m1", "m2"), query("MATCH (n:CyWrite) WHERE n.id STARTS WITH 'm' RETURN n.id ORDER BY n.id"))
        // A condition written as a list of one item or none, and a list that is null.
        query("MATCH (n:CyWrite) WHERE n.id IN ['root', 'other'] FOREACH (x IN CASE WHEN n.id = 'root' THEN [1] ELSE [] END | SET n.flag = true)")
        query("MATCH (n:CyWrite {id: 'other'}) FOREACH (x IN null | SET n.flag = false)")
        assertEquals(listOf(listOf("other", null), listOf("root", true)), query("MATCH (n:CyWrite) WHERE n.id IN ['root', 'other'] RETURN [n.id, n.flag] ORDER BY n.id"))
    }

    @Test
    fun `a call that writes is made for each row, leaves the rows as they were, or adds what it returns`() {
        assertEquals(
            listOf(2L),
            query("UNWIND [{id: 'a', n: 1}, {id: 'b', n: 2}] AS row CALL { WITH row MERGE (x:CyWrite {id: row.id}) SET x.n = row.n } RETURN count(*)"),
        )
        query("UNWIND \$rows AS row CALL (row) { MERGE (x:CyWrite {id: row.id}) SET x.n = row.n + 10 }", mapOf("rows" to listOf(mapOf("id" to "a", "n" to 1), mapOf("id" to "c", "n" to 3))))
        assertEquals(listOf(listOf("a", 11L), listOf("b", 2L), listOf("c", 13L)), query("MATCH (x:CyWrite) RETURN [x.id, x.n] ORDER BY x.id"))
        assertEquals(listOf("d!", "e!"), query("UNWIND ['d', 'e'] AS id CALL (id) { CREATE (x:CyWrite {id: id}) RETURN x.id + '!' AS made } RETURN made ORDER BY made"))
        if (!compiles) return
        // On a graph engine a CALL in transactions commits as it goes, outside any transaction of the caller's; here the statement is one.
        query("UNWIND [1, 2, 3] AS i CALL (i) { CREATE (:CyWrite {id: 'batch' + toString(i)}) } IN TRANSACTIONS OF 2 ROWS")
        assertEquals(listOf(3L), query("MATCH (x:CyWrite) WHERE x.id STARTS WITH 'batch' RETURN count(x)"))
    }

    @Test
    fun `a list of nodes or relationships is of them, so a write to an item of it reaches them`() {
        query("CREATE (a:CyWrite {id: 'a'})-[:NEXT]->(b:CyWrite {id: 'b'})-[:NEXT]->(c:CyWrite {id: 'c'})")
        // Gathered, then unwound and written to.
        query("MATCH (n:CyWrite) WITH collect(n) AS ns UNWIND ns AS n SET n.seen = true")
        assertEquals(listOf(3L), query("MATCH (n:CyWrite) WHERE n.seen = true RETURN count(n)"))
        // A path's nodes and relationships, each written to in a FOREACH.
        query("MATCH p = (:CyWrite {id: 'a'})-[:NEXT*]->(:CyWrite {id: 'c'}) FOREACH (n IN nodes(p) | SET n.onPath = true) FOREACH (r IN relationships(p) | SET r.onPath = true)")
        assertEquals(listOf(listOf(3L, 2L)), query("MATCH (n:CyWrite {onPath: true}) WITH count(n) AS nodes MATCH ()-[r:NEXT {onPath: true}]->() WITH nodes, count(r) AS rels RETURN [nodes, rels]"))
        // Given back, such a list is its items' properties, as before.
        assertEquals(
            listOf(listOf("a", "b", "c")),
            query("MATCH (n:CyWrite) WITH n ORDER BY n.id WITH collect(n) AS ns RETURN [x IN ns | x.id]"),
        )
        // Carried past a write, and written to after it.
        query("MATCH (n:CyWrite) WITH collect(n) AS ns CREATE (:CyWrite {id: 'd'}) WITH ns UNWIND ns AS n SET n.after = true")
        assertEquals(listOf(3L), query("MATCH (n:CyWrite {after: true}) RETURN count(n)"))
        // A path, carried past a write, is read after it.
        assertEquals(
            listOf(listOf(1L, listOf("a", "b"))),
            query("MATCH p = (:CyWrite {id: 'a'})-[:NEXT]->() CREATE (:CyWrite {id: 'z'}) RETURN [length(p), [n IN nodes(p) | n.id]]"),
        )
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
    fun `a write cypher does not compile yet is refused by name`() {
        if (!compiles) return
        assertEquals(
            "A variable-length relationship in CREATE or MERGE is Cypher, and does not run on Oracle or Postgres yet.",
            assertThrows<DrivineException> { query("MATCH (a:CyWrite), (b:CyWrite) MERGE (a)-[:R*1..2]->(b)") }.message,
        )
        assertEquals(
            "A relationship that CREATE makes has a direction.",
            assertThrows<DrivineException> { query("CREATE (:CyWrite)-[:R]-(:CyWrite)") }.message,
        )
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

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n:CyWrite) DETACH DELETE n"))
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
}
