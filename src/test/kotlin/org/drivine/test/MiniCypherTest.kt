package org.drivine.test

import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture interpreter over an in-memory store: what it reads of a fixture's Cypher, what it
 * writes back, and what it refuses rather than guesses at.
 */
class MiniCypherTest {

    private val store = FakeGraphStore()

    private fun run(cypher: String, vararg parameters: Pair<String, Any?>): List<Any?> =
        MiniCypher(store, cypher, mapOf(*parameters)).run()

    private fun refused(cypher: String, vararg parameters: Pair<String, Any?>): String =
        assertFailsWith<IllegalArgumentException> { run(cypher, *parameters) }.message!!

    private fun stored(key: String): Any? = store.nodes.values.single().props[key]

    private val inserts get() = store.writes("DECLARE")
    private val updates get() = store.writes("UPDATE")
    private val deletes get() = store.writes("DELETE")

    // ----- Literals -----

    @Test
    fun `a string literal reads Cypher's escapes, both quotes, and any character as it is`() {
        run("""CREATE (n:T {a: 'it\'s', b: 'a\\b', c: "dq", d: 'a\nb', e: 'é'})""")
        assertEquals("it's", stored("a"))
        assertEquals("a\\b", stored("b"))
        assertEquals("dq", stored("c"))
        assertEquals("a\nb", stored("d"))
        assertEquals("é", stored("e"))
    }

    @Test
    fun `a whole number is a Long, one with a point or an exponent a Double, and a sign is read`() {
        assertEquals(listOf(listOf(-1L, 3L, 3.0, 1000.0)), run("RETURN [-1, 3, 3.0, 1e3]"))
    }

    @Test
    fun `true, false and null are themselves`() {
        assertEquals(listOf(listOf(true, false, null)), run("RETURN [true, false, null]"))
    }

    @Test
    fun `a name in backticks may hold spaces and dots`() {
        run("CREATE (n:T {`a b.c`: 1})")
        assertEquals(1L, stored("a b.c"))
        assertEquals(listOf(1L), run("MATCH (n:T) RETURN n.`a b.c`"))
    }

    @Test
    fun `a parameter gives the value bound to it`() {
        run("CREATE (n:T {id: \$p})", "p" to "bound")
        assertEquals("bound", stored("id"))
        assertEquals(listOf(listOf(1, 2)), run("RETURN \$p", "p" to listOf(1, 2)))
    }

    @Test
    fun `a parameter with no value bound is refused by name`() {
        val message = refused("CREATE (n:T {id: \$missing})", "other" to 1)
        assertContains(message, "\$missing")
    }

    @Test
    fun `lists and maps nest`() {
        assertEquals(
            listOf(listOf(1L, listOf(2L, 3L), mapOf("a" to mapOf("b" to "c")), emptyList<Any?>(), emptyMap<String, Any?>())),
            run("RETURN [1, [2, 3], {a: {b: 'c'}}, [], {}]"),
        )
    }

    @Test
    fun `plus adds whole numbers to a Long, whatever width the store read them back as`() {
        assertEquals(listOf(3L), run("RETURN 1 + 2"))
        store.node("T", props = mapOf("count" to 1))
        val sum = run("MATCH (n:T) RETURN n.count + 1").single()
        assertEquals(2L, sum)
        assertTrue(sum is Long, "a sum of whole numbers is a Long, not ${sum!!::class.simpleName}")
        assertEquals(listOf(2.5), run("RETURN 1 + 1.5"))
    }

    @Test
    fun `plus joins strings, and a string to a number`() {
        assertEquals(listOf("ab"), run("RETURN 'a' + 'b'"))
        assertEquals(listOf("a1"), run("RETURN 'a' + 1"))
        assertEquals(listOf("1->2"), run("RETURN toString(1) + '->' + 2"))
    }

    // ----- CREATE -----

    @Test
    fun `a node's labels are stored sorted`() {
        run("CREATE (n:B:A:C {id: 'x'})")
        assertEquals(listOf("A", "B", "C"), store.nodes.values.single().labels)
        assertEquals(listOf("A", "B", "C"), inserts.single().parameters["labels"])
    }

    @Test
    fun `an arrow pointing left stores the relationship from the right node to the left`() {
        run("CREATE (a:T {id: 'a'})<-[:X {w: 1}]-(b:T {id: 'b'})")
        val edge = store.edges.values.single()
        assertEquals(store.find("T", "id", "b").nid, edge.src)
        assertEquals(store.find("T", "id", "a").nid, edge.dst)
        assertEquals("X", edge.type)
        assertEquals(mapOf("w" to 1L), edge.props)
    }

    @Test
    fun `a relationship created without a direction is refused`() {
        assertContains(refused("CREATE (a:T)-[:X]-(b:T)"), "direction")
    }

    @Test
    fun `a relationship created without a type is refused`() {
        assertContains(refused("CREATE (a:T)-[r]->(b:T)"), "type")
        assertContains(refused("CREATE (a:T)-->(b:T)"), "type")
    }

    @Test
    fun `a property set to null in CREATE is no property`() {
        assertEquals(listOf(listOf("id")), run("CREATE (n:T {id: 'x', gone: null}) RETURN keys(n)"))
        assertEquals(mapOf<String, Any?>("id" to "x"), store.nodes.values.single().props)
    }

    @Test
    fun `the stamp lands in the node's stamp column and not among its properties`() {
        run("CREATE (n:T {id: 'x', ${Stamps.QUOTED}: 's1'})")
        val row = store.nodes.values.single()
        assertEquals("s1", row.stamp)
        assertEquals(mapOf<String, Any?>("id" to "x"), row.props)
        assertEquals("s1", inserts.single().parameters["stamp"])
        // Read back, the stamp is a property as the graph engines have it.
        assertEquals(listOf(listOf("id", Stamps.PROPERTY)), run("MATCH (n:T) RETURN keys(n)"))
        assertEquals(listOf(mapOf("id" to "x", Stamps.PROPERTY to "s1")), run("MATCH (n:T) RETURN properties(n)"))
    }

    @Test
    fun `a variable already bound cannot be given labels or properties again`() {
        assertContains(refused("CREATE (a:T {id: 'a'}) CREATE (a:T {id: 'b'})"), "already bound")
        store.node("T")
        assertContains(refused("MATCH (a:T) CREATE (a:Other)"), "already bound")
    }

    @Test
    fun `a bound variable is joined by a later CREATE without being described again`() {
        store.node("T", props = mapOf("id" to "a"))
        run("MATCH (a:T {id: 'a'}) CREATE (a)-[:X]->(b:T {id: 'b'}) CREATE (b)-[:Y]->(a)")
        assertEquals(2, store.nodes.size)
        assertEquals(listOf("X", "Y"), store.edges.values.map { it.type })
    }

    @Test
    fun `a pattern's property cannot name a variable`() {
        store.node("T", props = mapOf("id" to "c"))
        val message = refused("MATCH (c:T {id: 'c'}) CREATE (o:O {owner: c.id})")
        assertContains(message, "pattern")
        assertContains(message, "c")
    }

    // ----- MERGE -----

    @Test
    fun `MERGE reuses a node that is there and makes one that is not`() {
        store.node("T", props = mapOf("id" to "a"))
        run("MERGE (n:T {id: 'a'})")
        assertEquals(1, store.nodes.size)
        assertTrue(store.written.isEmpty(), "nothing was written: ${store.written}")

        run("MERGE (n:T {id: 'z'}) SET n.made = true")
        assertEquals(2, store.nodes.size)
        assertEquals(1, inserts.size)
        assertEquals(true, store.find("T", "id", "z").props["made"])
    }

    @Test
    fun `MERGE of a relationship between bound nodes makes it once`() {
        store.node("T", props = mapOf("id" to "a"))
        store.node("T", props = mapOf("id" to "b"))
        run("MATCH (a:T {id: 'a'}), (b:T {id: 'b'}) MERGE (a)-[:X]->(b) MERGE (a)-[:X]->(b)")
        assertEquals(1, store.edges.size)
        run("MATCH (a:T {id: 'a'}), (b:T {id: 'b'}) MERGE (a)-[:X]->(b)")
        assertEquals(1, store.edges.size)
        assertEquals(1, store.writes("INSERT").size)
    }

    // ----- MATCH -----

    @Test
    fun `a pattern's labels are a subset of the node's`() {
        store.node("A", "B", props = mapOf("id" to "ab"))
        assertEquals(listOf("ab"), run("MATCH (n:A) RETURN n.id"))
        assertEquals(listOf("ab"), run("MATCH (n:B:A) RETURN n.id"))
        assertEquals(listOf("ab"), run("MATCH (n) RETURN n.id"))
        assertEquals(emptyList<Any?>(), run("MATCH (n:C) RETURN n.id"))
    }

    @Test
    fun `a number in a pattern matches a stored one whatever its width`() {
        store.node("T", props = mapOf("n" to 1.0))
        assertEquals(listOf(1L), run("MATCH (x:T {n: 1}) RETURN count(x)"))
        assertEquals(listOf(0L), run("MATCH (x:T {n: 2}) RETURN count(x)"))
    }

    @Test
    fun `WHERE joins comparisons, IN, STARTS WITH and IS NOT NULL by AND`() {
        store.node("T", props = mapOf("id" to "a", "age" to 20, "name" to "Ada", "email" to "a@x"))
        store.node("T", props = mapOf("id" to "b", "age" to 20, "name" to "Ann"))
        store.node("T", props = mapOf("id" to "c", "age" to 12, "name" to "Abe", "email" to "c@x"))
        store.node("T", props = mapOf("id" to "d", "age" to 30, "name" to "Bob", "email" to "d@x"))
        store.node("T", props = mapOf("id" to "e", "age" to 30, "name" to "Amy", "email" to "e@x"))
        val found = run(
            "MATCH (n:T) WHERE n.age >= 18 AND n.id IN ['a', 'b', 'c', 'd'] AND n.name STARTS WITH 'A' AND n.email IS NOT NULL RETURN n.id",
        )
        assertEquals(listOf("a"), found)
        assertEquals(listOf("b"), run("MATCH (n:T) WHERE n.email IS NULL RETURN n.id"))
        assertEquals(listOf("c"), run("MATCH (n:T) WHERE n.age < 18 AND n.age <> 20 RETURN n.id"))
    }

    @Test
    fun `an undirected pattern takes a relationship from either end`() {
        val a = store.node("T", props = mapOf("id" to "a"))
        val b = store.node("T", props = mapOf("id" to "b"))
        store.edge(a, b, "X")
        assertEquals(listOf("a"), run("MATCH (x:T {id: 'b'})-[:X]-(y) RETURN y.id"))
        assertEquals(listOf("b"), run("MATCH (x:T {id: 'a'})-[:X]-(y) RETURN y.id"))
        assertEquals(listOf("b"), run("MATCH (x:T {id: 'a'})-[:X]->(y) RETURN y.id"))
        assertEquals(emptyList<Any?>(), run("MATCH (x:T {id: 'a'})<-[:X]-(y) RETURN y.id"))
        assertEquals(listOf("X"), run("MATCH (x:T {id: 'a'})-[r]->(y) RETURN type(r)"))
    }

    @Test
    fun `a node deleted earlier in the statement is not matched again`() {
        store.node("T", props = mapOf("id" to "a"))
        store.node("T", props = mapOf("id" to "b"))
        assertEquals(listOf("b"), run("MATCH (n:T {id: 'a'}) DETACH DELETE n MATCH (m:T) RETURN m.id"))
        assertEquals(listOf("b"), run("MATCH (m:T) RETURN m.id"))
    }

    @Test
    fun `a property read by a bound key reaches the stamp`() {
        store.node("T", props = mapOf("id" to "a"), stamp = "s1")
        assertEquals(listOf("s1"), run("MATCH (n:T) RETURN n[\$key]", "key" to Stamps.PROPERTY))
        assertEquals(listOf("a"), run("MATCH (n:T) RETURN n[\$key]", "key" to "id"))
        assertEquals(listOf("s1"), run("MATCH (n:T) RETURN n.${Stamps.QUOTED}"))
    }

    // ----- SET and REMOVE -----

    @Test
    fun `SET of a label the node already has changes nothing`() {
        store.node("T", props = mapOf("id" to "a"))
        assertEquals(listOf(listOf("T", "L")), run("MATCH (n:T) SET n:L SET n:L RETURN labels(n)"))
        assertEquals(listOf("L", "T"), store.nodes.values.single().labels)
        assertEquals(1, updates.size)
        run("MATCH (n:T) SET n:L")
        assertEquals(1, updates.size, "a label already there is not written again")
    }

    @Test
    fun `randomUUID gives each row its own value`() {
        store.node("T", props = mapOf("id" to "a"))
        store.node("T", props = mapOf("id" to "b"))
        run("MATCH (n:T) SET n.x = randomUUID()")
        val values = store.nodes.values.map { it.props["x"] as String }
        assertNotEquals(values[0], values[1])
        assertEquals(2, updates.size)
    }

    @Test
    fun `REMOVE drops the property and the node is written`() {
        store.node("T", props = mapOf("id" to "a", "x" to 1))
        run("MATCH (n:T) REMOVE n.x")
        assertEquals(mapOf<String, Any?>("id" to "a"), store.nodes.values.single().props)
        assertEquals(mapOf<String, Any?>("id" to "a"), updates.single().parameters["props"])
    }

    @Test
    fun `SET of a null removes the property`() {
        store.node("T", props = mapOf("id" to "a", "x" to 1))
        assertEquals(listOf(listOf("id")), run("MATCH (n:T) SET n.x = null RETURN keys(n)"))
    }

    @Test
    fun `the set clause replaces the node token of a stamp and keeps the links token`() {
        val a = "a".repeat(16)
        val b = "b".repeat(16)
        store.node("T", props = mapOf("id" to "c"), stamp = "$a:$b")
        run("MATCH (c:T {id: 'c'}) SET c.text = 'x', ${Stamps.setClause("c")}")
        val stamp = store.nodes.values.single().stamp!!
        assertTrue(Stamps.isTwoTokens(stamp), "two tokens: $stamp")
        assertNotEquals(a, Stamps.nodeToken(stamp))
        assertEquals(b, Stamps.linksToken(stamp))
        assertEquals("x", stored("text"))
        assertEquals(stamp, updates.single().parameters["stamp"])
    }

    @Test
    fun `the links clause replaces the links token of a stamp and keeps the node token`() {
        val a = "a".repeat(16)
        val b = "b".repeat(16)
        store.node("T", props = mapOf("id" to "c"), stamp = "$a:$b")
        run("MATCH (c:T {id: 'c'}) SET ${Stamps.linksClause("c")}")
        val stamp = store.nodes.values.single().stamp!!
        assertTrue(Stamps.isTwoTokens(stamp), "two tokens: $stamp")
        assertEquals(a, Stamps.nodeToken(stamp))
        assertNotEquals(b, Stamps.linksToken(stamp))
    }

    @Test
    fun `the stamp clauses give a node with no stamp, or one of another shape, a whole new one`() {
        store.node("T", props = mapOf("id" to "none"))
        store.node("T", props = mapOf("id" to "junk"), stamp = "junk")
        run("MATCH (c:T) SET ${Stamps.setClause("c")}")
        store.nodes.values.forEach { assertTrue(Stamps.isTwoTokens(it.stamp!!), "${it.props["id"]}: ${it.stamp}") }
        val before = store.nodes.values.map { it.stamp }
        run("MATCH (c:T) SET c.${Stamps.QUOTED} = null SET ${Stamps.linksClause("c")}")
        store.nodes.values.forEach { assertTrue(Stamps.isTwoTokens(it.stamp!!), "${it.props["id"]}: ${it.stamp}") }
        assertNotEquals(before, store.nodes.values.map { it.stamp })
    }

    // ----- DETACH DELETE -----

    @Test
    fun `DETACH DELETE takes the node's relationships with it and writes one DELETE`() {
        val a = store.node("T", props = mapOf("id" to "a"))
        val b = store.node("T", props = mapOf("id" to "b"))
        store.edge(a, b, "X")
        store.edge(b, a, "Y")
        run("MATCH (n:T {id: 'a'}) DETACH DELETE n")
        assertTrue(store.edges.isEmpty())
        assertEquals(listOf(b), store.nodes.keys.toList())
        assertEquals(1, deletes.size)
        assertEquals(1, store.written.size, "only the delete was written: ${store.written}")
        assertEquals(a, (deletes.single().parameters["nid"] as Number).toLong())
    }

    @Test
    fun `a node created and deleted in one statement is never written`() {
        run("CREATE (n:T {id: 'tmp'})-[:X]->(m:T {id: 'kept'}) DETACH DELETE n")
        assertTrue(store.written.none { it.verb == "DELETE" }, "nothing to delete: ${store.written}")
        assertEquals(listOf("kept"), store.nodes.values.map { it.props["id"] })
        assertTrue(store.edges.isEmpty())
    }

    // ----- UNWIND, WITH and RETURN -----

    @Test
    fun `UNWIND gives a row for each item`() {
        assertEquals(listOf(1L, 2L, 3L), run("UNWIND [1, 2, 3] AS x RETURN x"))
        assertEquals(listOf("a", "b"), run("UNWIND \$rows AS r RETURN r.id", "rows" to listOf(mapOf("id" to "a"), mapOf("id" to "b"))))
    }

    @Test
    fun `WITH carries variables on and keeps the rows its WHERE holds for`() {
        assertEquals(listOf("ma", "mc"), run("UNWIND ['ma', 'xb', 'mc'] AS k WITH k WHERE k STARTS WITH 'm' RETURN k"))
    }

    @Test
    fun `a count over no rows is zero`() {
        assertEquals(listOf(0L), run("MATCH (n:Nope) RETURN count(n)"))
        assertEquals(listOf(0L), run("MATCH (n:Nope) RETURN count(n) AS c"))
    }

    @Test
    fun `ORDER BY sorts a missing key last, and DESC turns the order round`() {
        store.node("T", props = mapOf("id" to "two", "v" to 2))
        store.node("T", props = mapOf("id" to "none"))
        store.node("T", props = mapOf("id" to "one", "v" to 1))
        assertEquals(listOf("one", "two", "none"), run("MATCH (n:T) RETURN n.id ORDER BY n.v"))
        assertEquals(listOf("one", "two", "none"), run("MATCH (n:T) RETURN n.id ORDER BY n.v ASC"))
        assertEquals(listOf("none", "two", "one"), run("MATCH (n:T) RETURN n.id ORDER BY n.v DESC"))
    }

    @Test
    fun `ORDER BY reads the alias RETURN gave the value`() {
        store.node("T", props = mapOf("id" to "b"))
        store.node("T", props = mapOf("id" to "c"))
        store.node("T", props = mapOf("id" to "a"))
        assertEquals(listOf("a", "b", "c"), run("MATCH (n:T) RETURN n.id AS id ORDER BY id"))
        assertEquals(listOf("c", "b", "a"), run("MATCH (n:T) RETURN n.id AS id ORDER BY id DESC"))
    }

    @Test
    fun `a searched CASE gives the first branch that holds, or its ELSE`() {
        store.node("T", props = mapOf("id" to "a", "v" to 2))
        store.node("T", props = mapOf("id" to "b", "v" to 1))
        store.node("T", props = mapOf("id" to "c"))
        assertEquals(
            listOf("big", "one", "none"),
            run("MATCH (n:T) RETURN CASE WHEN n.v > 1 THEN 'big' WHEN n.v = 1 THEN 'one' ELSE 'none' END ORDER BY n.id"),
        )
        assertEquals(listOf(null), run("RETURN CASE WHEN false THEN 1 END"))
    }

    @Test
    fun `the listed functions read a node, a relationship and a string`() {
        val a = store.node("T", props = mapOf("id" to "ada"))
        val b = store.node("T", props = mapOf("id" to "bob"))
        store.edge(a, b, "KNOWS", mapOf("since" to 2020))
        assertEquals(listOf("KNOWS"), run("MATCH (a:T {id: 'ada'})-[r]->(b) RETURN type(r)"))
        assertEquals(listOf(mapOf("since" to 2020)), run("MATCH (a:T {id: 'ada'})-[r]->(b) RETURN properties(r)"))
        assertEquals(listOf(listOf("since")), run("MATCH (a:T {id: 'ada'})-[r]->(b) RETURN keys(r)"))
        assertEquals(listOf("ada->bob"), run("MATCH (a)-[r]->(b) WHERE type(r) = \$type RETURN toString(a.id) + '->' + b.id", "type" to "KNOWS"))
        assertEquals(listOf(listOf("x", 3L, "ad", "ad", "bc", "a_a", 5L)), run("RETURN [coalesce(null, 'x'), size('abc'), substring('ada', 0, 2), left('ada', 2), right('abc', 2), replace('a-a', '-', '_'), size([1, 2, 3, 4, 5])]"))
        assertEquals(listOf("2024-01-01T00:00:00Z"), run("RETURN datetime('2024-01-01T00:00:00Z')"))
        assertEquals(listOf(true), run("RETURN datetime('2024-01-01T00:00:00Z') < datetime('2024-06-01T00:00:00+02:00')"))
    }

    // ----- Refusals -----

    @Test
    fun `OR is refused where it stands`() {
        store.node("T", props = mapOf("a" to 1))
        assertContains(refused("MATCH (n:T) WHERE n.a = 1 OR n.b = 2 RETURN n.a"), "Expected")
    }

    @Test
    fun `a NOT other than in IS NOT NULL is refused where it stands`() {
        store.node("T", props = mapOf("a" to 1))
        assertContains(refused("MATCH (n:T) WHERE NOT n.a = 1 RETURN n.a"), "Expected")
    }

    @Test
    fun `ON CREATE SET is refused where it stands`() {
        assertContains(refused("MERGE (n:T {id: 'x'}) ON CREATE SET n.a = 1"), "Expected a clause")
    }

    @Test
    fun `LIMIT is refused where it stands`() {
        store.node("T", props = mapOf("id" to "a"))
        assertContains(refused("MATCH (n:T) RETURN n.id LIMIT 1"), "Expected a clause")
    }

    @Test
    fun `a RETURN of several columns is refused where it stands`() {
        store.node("T", props = mapOf("id" to "a"))
        assertContains(refused("MATCH (a:T), (b:T) RETURN a.id, b.id"), "Expected a clause")
    }

    @Test
    fun `a function not listed is refused by name`() {
        assertContains(refused("RETURN foo(1)"), "foo")
    }

    @Test
    fun `a simple CASE is refused and the searched one named`() {
        store.node("T", props = mapOf("v" to 1))
        val message = refused("MATCH (n:T) RETURN CASE n.v WHEN 1 THEN 'a' END")
        assertContains(message, "Expected END")
        assertContains(message, "searched CASE")
    }

    @Test
    fun `a sign with no digits after it is refused as a number`() {
        assertContains(refused("RETURN -"), "Expected a number")
    }

    @Test
    fun `a string with no closing quote is refused`() {
        assertContains(refused("RETURN 'abc"), "Expected the end of a string")
    }

    // ----- reads -----

    @Test
    fun `a statement that opens with a pattern or UNWIND is a fixture, and one in SQL is not`() {
        assertFalse(MiniCypher.reads("CREATE INDEX idx FOR (n:T) ON (n.id)"))
        assertTrue(MiniCypher.reads("  MATCH (n) RETURN n"))
        assertTrue(MiniCypher.reads("MERGE (n:T {id: 'x'})"))
        assertTrue(MiniCypher.reads("CREATE (n:T)"))
        assertFalse(MiniCypher.reads("WITH x AS (SELECT 1 FROM dual) SELECT * FROM x"))
        assertFalse(MiniCypher.reads("SELECT 1 FROM dual"))
        val unwind = "UNWIND \$rows AS r CREATE (:X {id: r.id})"
        assertTrue(MiniCypher.reads(unwind))
        // Read as a fixture, and refused once it is: a pattern's property cannot name a variable.
        assertContains(refused(unwind, "rows" to listOf(mapOf("id" to 1))), "pattern")
        assertNull(store.nodes.values.firstOrNull(), "nothing was made")
    }
}
