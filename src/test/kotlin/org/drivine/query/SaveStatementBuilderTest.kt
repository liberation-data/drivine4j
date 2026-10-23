package org.drivine.query

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.toMap
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimView
import sample.stateless.ClaimWithOptionalStatus
import sample.stateless.ClaimWithRoles
import sample.stateless.ClaimWithStatus
import sample.stateless.Company
import sample.stateless.Human
import sample.stateless.HumanClaims
import sample.stateless.Memo
import sample.stateless.Shade
import sample.stateless.Sticker
import sample.stateless.Swatch
import sample.stateless.Tagged

/** The text of a save statement: an engine plans a statement once for each text it is given. */
class SaveStatementBuilderTest {

    private val builder = SaveStatementBuilder(Neo4jObjectMapper.instance, null, null)

    private fun people(count: Int) = (1..count).map { Human("h$it", "Human $it") }

    private fun statement(view: Any, replaced: RemovedTargets? = null): String =
        builder.build(SaveRequest(view, checked = true, NullPolicy.IGNORE, replaced = { replaced })).statement

    @Test
    fun `the statement is the same text however long a list is`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2), companies = listOf(Company("acme", "Acme"))))
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500), companies = (1..40).map { Company("co$it", "Co $it") }))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a replaced list is`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2)), RemovedTargets.DELETE_UNREFERENCED)
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500)), RemovedTargets.DELETE_UNREFERENCED)

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text whatever the relationships' properties hold`() {
        val few = statement(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))))
        val many = statement(ClaimCitations(Claim("c1", "one"), cited = people(300).mapIndexed { page, human -> Citation(page, human) }))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a replaced list is, when its targets are kept`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2)), RemovedTargets.KEEP)
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500)), RemovedTargets.KEEP)

        assertEquals(few, many)
        assertNotEquals(statement(ClaimView(Claim("c1", "one"), people = people(2))), few, "a replace has a part that removes")
    }

    @Test
    fun `the statement is the same text however long a replaced list of relationships with properties is`() {
        val few = statement(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))), RemovedTargets.DELETE_UNREFERENCED)
        val many = statement(
            ClaimCitations(Claim("c1", "one"), cited = people(300).mapIndexed { page, human -> Citation(page, human) }),
            RemovedTargets.DELETE_UNREFERENCED,
        )

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a list of stamped nodes read against its direction is`() {
        fun claims(count: Int) = (1..count).map { Claim("k$it", "Claim $it") }
        val few = statement(HumanClaims(Human("ada", "Ada"), claims = claims(2)))
        val many = statement(HumanClaims(Human("ada", "Ada"), claims = claims(400)))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text for an unchecked save`() {
        fun unchecked(view: Any) = builder.build(SaveRequest(view, checked = false, NullPolicy.IGNORE)).statement

        assertEquals(
            unchecked(ClaimView(Claim("c1", "one"), people = people(2))),
            unchecked(ClaimView(Claim("c1", "one"), people = people(500))),
        )
    }

    @Test
    fun `a field that holds nothing has no part`() {
        val both = statement(ClaimView(Claim("c1", "one"), people = people(1), companies = listOf(Company("acme", "Acme"))))
        val one = statement(ClaimView(Claim("c1", "one"), people = people(1)))

        assertNotEquals(both, one)
    }

    @Test
    fun `a fragment hands its stamp back from the merge's own scope, and a view with no stamped node without a reduce`() {
        val fragment = builder.build(SaveRequest(Claim("c1", "one"), checked = true, NullPolicy.IGNORE)).statement
        assertFalse("_r0" in fragment, fragment)
        assertFalse("reduce(" in fragment, fragment)
        assertTrue(fragment.lines().last().startsWith("RETURN "), fragment)

        val view = builder.build(SaveRequest(ClaimView(Claim("c1", "one"), people = people(2)), checked = true, NullPolicy.IGNORE)).statement
        assertFalse("reduce(" in view, view)
    }

    @Test
    fun `a stamp that is always replaced is set without a test`() {
        val replacing = statement(ClaimView(Claim("c1", "one"), people = people(2)), RemovedTargets.KEEP)

        assertFalse("CASE WHEN (true)" in replacing, replacing)
        assertFalse("CASE WHEN true" in replacing, replacing)
    }

    @Test
    fun `a save that makes its root only when it is absent goes on only from a root it made`() {
        val fragment = builder.build(SaveRequest.absent(Claim("c1", "one"))).statement
        val made = "MERGE (n:Claim {id: \$p0_id})\nON CREATE SET n.`__drivine.made` = true\nWITH n WHERE n.`__drivine.made` IS NOT NULL"

        assertTrue(fragment.startsWith(made), fragment)
        assertTrue("REMOVE n.`__drivine.made`" in fragment, fragment)
        assertFalse("`__drivine.lock`" in fragment.lines().take(4).joinToString("\n"), "a node the statement made is no other writer's to lock against")

        val view = builder.build(SaveRequest.absent(ClaimView(Claim("c1", "one"), people = people(2)))).statement
        assertTrue(view.startsWith(made), view)
        assertEquals(1, view.split("WHERE n.`__drivine.made` IS NOT NULL").size - 1, "a related node is written whether it is there or not: $view")
    }

    @Test
    fun `a node with no stamp field is marked when it is made only if absent, as one with a stamp field is`() {
        val memo = builder.build(SaveRequest.absent(Memo("m1", "one"))).statement

        assertTrue("ON CREATE SET n.`__drivine.made` = true\nWITH n WHERE n.`__drivine.made` IS NOT NULL" in memo, memo)
        assertTrue("REMOVE n.`__drivine.made`" in memo, memo)
    }

    /** The save of a change to a claim loaded with [loaded] as its text and no note, built for [dialect]. */
    private fun changeSaved(dialect: CypherDialect, loaded: String = "one"): SaveStatement {
        val digests = org.drivine.session.SnapshotDigest(Neo4jObjectMapper.instance)
        val before = Claim("c1", loaded, stamp = "0123456789abcdef:fedcba9876543210")
        val after = before.copy(text = "two")
        val request = SaveRequest.changed(after, digests.of(before), digests.of(after), setOf("text"), mapOf("id" to "c1", "text" to loaded, "note" to null))
        return SaveStatementBuilder(Neo4jObjectMapper.instance, dialect.grammar(), null).build(request)
    }

    @Test
    fun `where a read takes no snapshot, the save of a change matches the node only while each field is as it was loaded`() {
        val saved = changeSaved(CypherDialect.NEO4J_5)
        val check = saved.statement.lines().first { it.startsWith("WHERE ") }

        val text = saved.bindings.entries.single { "_loaded" in it.key }
        assertEquals("one", text.value, "the text as loaded, and not as changed")
        assertTrue("left(n.`__drivine.stamp`, 16) = \$p0__expectedStamp" in check, check)
        assertTrue("n.text IS NOT NULL AND NOT coalesce(n.text <> \$${text.key}, false)" in check, check)
        assertTrue("n.note IS NULL" in check, "a field loaded as null must still be null: $check")
        assertFalse("stamp` <>" in check, "the stamp is compared as the stamp: $check")
        assertEquals(changeSaved(CypherDialect.NEO4J_5, loaded = "a longer text").statement, saved.statement, "the values are bound, not written into the text")
        assertTrue("n.note IS NULL" in changeSaved(CypherDialect.NEO4J_4).statement, "Neo4j 4 reads without a snapshot too")
    }

    @Test
    fun `where a read takes no snapshot, a field loaded as a node without it loads lets the node be without it`() {
        val stamp = "0123456789abcdef:fedcba9876543210"
        val defaulted = ClaimWithStatus("c1", "one", stamp)
        val empty = ClaimWithRoles("c1", "one", stamp, roles = emptyList())

        val status = checkOfChange(defaulted, defaulted.copy(text = "two")).first
        val roles = checkOfChange(empty, empty.copy(text = "two")).first

        assertTrue("NOT coalesce(n.status <> " in status, status)
        assertFalse("n.status IS NOT NULL" in status, "a status loaded as its @Default may have been loaded from none: $status")
        assertTrue("NOT coalesce(n.roles <> " in roles, roles)
        assertFalse("n.roles IS NOT NULL" in roles, "roles loaded empty by @EmptyWhenAbsent may have been loaded from none: $roles")
        assertTrue("n.text IS NOT NULL AND NOT coalesce(n.text <> " in status, "a field no load fills in must be there: $status")

        val closed = defaulted.copy(status = "closed")
        val admin = empty.copy(roles = listOf("admin"))
        assertTrue("n.status IS NOT NULL AND NOT coalesce(n.status <> " in checkOfChange(closed, closed.copy(text = "two")).first, "not the value a load gives a node without it")
        assertTrue("n.roles IS NOT NULL AND NOT coalesce(n.roles <> " in checkOfChange(admin, admin.copy(text = "two")).first)
    }

    @Test
    fun `where a read names each field, a constructor default is not what a node without the property loads`() {
        val loaded = ClaimWithOptionalStatus("c1", "one", "0123456789abcdef:fedcba9876543210")
        // The read names the field, so a node without it hands the mapper a null, not the default.
        assertEquals(null, Neo4jObjectMapper.instance.convertValue(mapOf("id" to "c1", "text" to "one", "status" to null), ClaimWithOptionalStatus::class.java).status)

        val check = checkOfChange(loaded, loaded.copy(text = "two")).first

        assertTrue("n.status IS NOT NULL AND NOT coalesce(n.status <> " in check, "a node without a status loads none: $check")
    }

    @Test
    fun `where a read takes a snapshot, the save of a change compares the stamp alone`() {
        listOf(CypherDialect.MEMGRAPH, CypherDialect.FALKORDB, CypherDialect.NEPTUNE).forEach { dialect ->
            val saved = changeSaved(dialect)
            assertFalse("_loaded" in saved.statement, "$dialect: ${saved.statement}")
            assertFalse(saved.bindings.keys.any { "_loaded" in it }, "$dialect: ${saved.bindings}")
        }
    }

    /** The check of the save of a change to [loaded], on Neo4j: the root's `WHERE`, and what it binds. */
    private fun <T : Any> checkOfChange(loaded: T, changed: T): Pair<String, Map<String, Any?>> {
        val mapper = Neo4jObjectMapper.instance
        val digests = org.drivine.session.SnapshotDigest(mapper)
        val request = SaveRequest.changed(changed, digests.of(loaded), digests.of(changed), null, mapper.toMap(loaded))
        val saved = SaveStatementBuilder(mapper, CypherDialect.NEO4J_5.grammar(), null).build(request)
        return saved.statement.lines().first { it.startsWith("WHERE ") } to saved.bindings
    }

    @Test
    fun `the save of a change holds a prefixed bag to the keys and values it was loaded with`() {
        val loaded = Tagged("t1", "one", meta = mapOf("owner" to "ada"), stamp = "0123456789abcdef:fedcba9876543210")
        val (check, bindings) = checkOfChange(loaded, loaded.copy(text = "two"))

        assertTrue("size([k IN keys(n) WHERE k STARTS WITH \$p0__loadedBag0Prefix AND size(k) > size(\$p0__loadedBag0Prefix)]) = size(keys(\$p0__loadedBag0))" in check, check)
        assertTrue("all(k IN keys(\$p0__loadedBag0) WHERE n[k] IS NOT NULL AND NOT coalesce(n[k] <> \$p0__loadedBag0[k], false))" in check, check)
        assertEquals(mapOf("meta.owner" to "ada"), bindings["p0__loadedBag0"], "keyed as the node stores them")
        assertEquals("meta.", bindings["p0__loadedBag0Prefix"])
    }

    @Test
    fun `the save of a change holds a flat bag and an open set of labels to what they were loaded with`() {
        val loaded = Sticker("s1", labels = setOf("Sticker", "Shiny"), extras = mapOf("size" to 1L), stamp = "0123456789abcdef:fedcba9876543210")
        val (check, bindings) = checkOfChange(loaded, loaded.copy(extras = mapOf("size" to 2L)))

        assertTrue("NOT k IN \$p0__loadedBag0Reserved AND none(p IN \$p0__loadedBag0ReservedPrefixes WHERE k STARTS WITH p)" in check, check)
        assertTrue("stamp" in (bindings["p0__loadedBag0Reserved"] as List<*>), "the stamp field's name is no key of the bag: ${bindings["p0__loadedBag0Reserved"]}")
        assertTrue("size(labels(n)) = size(\$p0__loadedLabels) AND all(l IN \$p0__loadedLabels WHERE l IN labels(n))" in check, check)
        assertEquals(setOf("Sticker", "Shiny"), (bindings["p0__loadedLabels"] as List<*>).toSet())
    }

    @Test
    fun `the save of a change holds a closed set of labels to its own members alone`() {
        val loaded = Swatch("w1", shades = setOf(Shade.Blue), stamp = "0123456789abcdef:fedcba9876543210")
        val (check, bindings) = checkOfChange(loaded, loaded.copy(shades = setOf(Shade.Red)))

        assertTrue("size([l IN labels(n) WHERE l IN \$p0__loadedLabelsClosed]) = size(\$p0__loadedLabels)" in check, check)
        assertEquals(setOf("Red", "Green", "Blue"), (bindings["p0__loadedLabelsClosed"] as List<*>).toSet())
        assertEquals(listOf("Blue"), bindings["p0__loadedLabels"])
    }
}
