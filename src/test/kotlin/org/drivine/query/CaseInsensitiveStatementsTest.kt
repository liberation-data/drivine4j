package org.drivine.query

import org.drivine.DrivineException
import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.backend.oracle.LabelledIndex
import org.drivine.backend.oracle.LabelledIndexes
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleLowerCopies
import org.drivine.backend.oracle.OracleSelect
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresLowerCopies
import org.drivine.backend.postgres.PostgresSelect
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.model.LowerCopies
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.schema.CypherLowerCopies
import org.drivine.schema.FalkorDbSchemaGrammar
import org.drivine.schema.FragmentSchemaScanner
import org.drivine.schema.LowerCopyStatements
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.made
import org.junit.jupiter.api.Test
import sample.caseinsensitive.CiAccount
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The statements of `@CaseInsensitive`, as the other libraries of the Drivine family write them too:
 * the copy a save writes, the comparison with it, how a copy is filled in and recorded, and the item
 * an index or a constraint that ignores case is made as.
 */
class CaseInsensitiveStatementsTest {

    private val copy = "`__drivine.lower.email`"

    // ----- The copy -----

    @Test
    fun `the copy of a property is stored beside the stamp, under the property's stored name`() {
        assertEquals("__drivine.lower.email", LowerCopies.of("email"))
        assertEquals("a.$copy = toLower(a.email)", LowerCopies.setClause("a", "email"))
        assertEquals("a.`__drivine.lower.meta.context` = toLower(a.`meta.context`)", LowerCopies.setClause("a", "meta.context"))
        assertEquals(listOf("email"), FragmentModel.from(CiAccount::class.java).lowerCopied)
    }

    @Test
    fun `a save writes the copy from the property as written, in a SET of its own after the others`() {
        val statement = FragmentMergeBuilder(FragmentModel.from(CiAccount::class.java), Neo4jObjectMapper.instance)
            .buildMergeStatement(CiAccount("a1", "Ann@X.com"), null).statement

        assertEquals("SET n.$copy = toLower(n.email)", statement.lines().last())
        assertTrue(statement.lines().dropLast(1).none { copy in it }, statement)
    }

    @Test
    fun `a patch writes the copy of a node it changed`() {
        val statement = PatchStatementBuilder(Neo4j5Grammar(ApocSortMapsEmitter())).unwind(FragmentModel.from(CiAccount::class.java), listOf("name"), clears = false)

        assertContains(statement, "FOREACH (_ IN CASE WHEN _changed THEN [1] ELSE [] END | SET n.$copy = toLower(n.email))")
    }

    @Test
    fun `a fragment with no CaseInsensitive field writes no copy`() {
        val statement = FragmentMergeBuilder(FragmentModel.from(Plain::class.java), Neo4jObjectMapper.instance)
            .buildMergeStatement(Plain("p1", "Ann@X.com"), null).statement

        assertFalse(LowerCopies.PREFIX in statement, statement)
    }

    @Test
    fun `CaseInsensitive is for a String stored as a property of its own`() {
        val refused = assertFailsWith<IllegalArgumentException> { FragmentModel.from(Counted::class.java) }
        assertContains(refused.message!!, "@CaseInsensitive field 'count' on Counted")
    }

    // ----- The lookup -----

    @Test
    fun `a comparison with the copy lowercases the value in the engine, not in Kotlin`() {
        val conditions = listOf(
            PropertyCondition("n.${LowerCopies.of("email")}", ComparisonOperator.EQUALS_LOWERED, "Ann@X.com"),
            PropertyCondition("n.${LowerCopies.of("email")}", ComparisonOperator.CONTAINS_LOWERED, "NN@"),
        )
        val where = CypherGenerator.buildWhereClause(conditions).whereClause

        assertEquals(
            "n.$copy = toLower(\$param_n___drivine_lower_email_0) AND n.$copy CONTAINS toLower(\$param_n___drivine_lower_email_1)",
            where,
        )
        assertEquals(listOf("Ann@X.com", "NN@"), CypherGenerator.extractBindings(conditions).values.toList())
    }

    @Test
    fun `Oracle and Postgres lowercase the value too, and Oracle reads the copy through its labelled index`() {
        val equal = PropertyCondition("n.${LowerCopies.of("email")}", ComparisonOperator.EQUALS_LOWERED, "Ann@X.com")
        val contains = PropertyCondition("n.${LowerCopies.of("email")}", ComparisonOperator.CONTAINS_LOWERED, "NN@")
        val oracle = OracleGraph("g")
        val labelled = OracleSelect(oracle, CiAccount::class.java, labelled = LabelledIndexes(listOf(LabelledIndex("CiAccount", listOf(LowerCopies.of("email"))))))

        assertEquals(
            "CASE WHEN JSON_EXISTS(n_.labels, '\$[*]?(@ == \"CiAccount\")') THEN JSON_VALUE(n_.props, '\$.\"__drivine.lower.email\"') END = LOWER(\$_p0)",
            labelled.predicate(listOf(equal), indexed = true),
        )
        assertEquals("INSTR(JSON_VALUE(n_.props, '\$.\"__drivine.lower.email\"'), LOWER(\$_p0)) > 0", OracleSelect(oracle, CiAccount::class.java).predicate(listOf(contains)))
        assertEquals(
            "(n_.props ->> '__drivine.lower.email') = lower(CAST(\$_p0 AS text))",
            PostgresSelect(PostgresGraph("g"), CiAccount::class.java).predicate(listOf(equal)),
        )
    }

    // ----- Filling in and recording the copies -----

    @Test
    fun `the graph engines fill a copy in by batches, and record it on the store's node`() {
        val statements = CypherLowerCopies()

        assertEquals(
            """
            MATCH (n:CiAccount)
            WHERE NOT coalesce(n.$copy = toLower(n.email), n.$copy IS NULL AND n.email IS NULL)
            WITH n LIMIT ${'$'}batch
            SET n.$copy = toLower(n.email)
            RETURN count(n)
            """.trimIndent(),
            statements.fill("CiAccount", "email"),
        )
        assertEquals(
            """
            MATCH (s:DrivineStore {key: 'singleton'})
            SET s.`__drivine.lock` = true REMOVE s.`__drivine.lock`
            SET s.lowered = [e IN coalesce(s.lowered, []) WHERE e <> ${'$'}entry] + ${'$'}entry
            """.trimIndent(),
            statements.record,
        )
        assertEquals("MATCH (s:DrivineStore) UNWIND coalesce(s.lowered, []) AS e RETURN DISTINCT e", statements.read)
    }

    @Test
    fun `a record names its label and property as a JSON array, as every library writes it`() {
        assertEquals("[\"CiAccount\",\"email\"]", LowerCopyStatements.entry("CiAccount", "email"))
        assertEquals("[\"A\\\"b\",\"c\\\\d\"]", LowerCopyStatements.entry("A\"b", "c\\d"))
        assertEquals(setOf("CiAccount" to "email", "A\"b" to "c\\d"), CypherLowerCopies().entries(listOf(LowerCopyStatements.entry("CiAccount", "email"), LowerCopyStatements.entry("A\"b", "c\\d"), "not one")))
    }

    @Test
    fun `Oracle and Postgres fill a copy in with their own lowercasing, and record it in the graph's catalog`() {
        val oracle = OracleLowerCopies(OracleGraph("g"))
        assertContains(oracle.fill("CiAccount", "email"), "SET n.props = JSON_TRANSFORM(n.props, SET '\$.\"__drivine.lower.email\"' = LOWER(JSON_VALUE(n.props, '\$.email')) REMOVE ON NULL)")
        assertContains(oracle.fill("CiAccount", "email"), "AND ROWNUM <= \$batch")
        assertContains(oracle.record, "MERGE INTO g_schema c")
        assertEquals("SELECT JSON_OBJECT('label' VALUE label, 'properties' VALUE JSON(properties) RETURNING JSON) FROM g_schema WHERE kind = 'LOWER_COPY'", oracle.read)

        val postgres = PostgresLowerCopies(PostgresGraph("g"))
        assertContains(postgres.fill("CiAccount", "email"), "ELSE n.props || jsonb_build_object('__drivine.lower.email', lower(n.props ->> 'email')) END")
        assertContains(postgres.record, "VALUES ('LOWER_COPY', CAST(\$label AS text), CAST(\$properties AS text), NULL, NULL, '{}')")
        assertEquals(
            setOf("CiAccount" to "email"),
            postgres.entries(listOf(mapOf("label" to "CiAccount", "properties" to listOf("email")), mapOf("label" to "Other"))),
        )
    }

    // ----- The items that ignore case -----

    @Test
    fun `an item that ignores case is made over the copies, named apart from one that does not`() {
        val range = RangeIndexSpec("CiAccount", "email", ignoreCase = true)
        assertEquals("CiAccount_email_ci_range", range.effectiveName)
        assertEquals(RangeIndexSpec("CiAccount", listOf(LowerCopies.of("email")), "CiAccount_email_ci_range"), range.made())
        assertEquals("RANGE_INDEX:CiAccount:__drivine.lower.email:CiAccount_email_ci_range", range.inventoryKey)
        assertEquals(RangeIndexSpec("CiAccount", "email"), RangeIndexSpec("CiAccount", "email").made())

        val unique = UniquenessConstraintSpec("CiAccount", "email", nodeIdProperty = "id", ignoreCase = true)
        assertEquals(UniquenessConstraintSpec("CiAccount", listOf(LowerCopies.of("email")), "CiAccount_email_ci_unique", "id"), unique.made())
        assertEquals(TextIndexSpec("CiAccount", LowerCopies.of("email"), "CiAccount_email_ci_text"), TextIndexSpec("CiAccount", "email", ignoreCase = true).made())
    }

    @Test
    fun `a Kotlin caller compiled before ignoreCase, which left a default out, still links`() {
        // The synthetic constructors such a caller links to: bit i of the mask is set where it left parameter i out.
        val text = String::class.java
        val list = List::class.java
        val mask = Int::class.javaPrimitiveType
        val marker = kotlin.jvm.internal.DefaultConstructorMarker::class.java

        assertEquals(RangeIndexSpec("L", "p"), RangeIndexSpec::class.java.getConstructor(text, list, text, mask, marker).newInstance("L", listOf("p"), null, 4, null))
        assertEquals(RangeIndexSpec("L", "p", "n"), RangeIndexSpec::class.java.getConstructor(text, text, text, mask, marker).newInstance("L", "p", "n", 0, null))
        assertEquals(
            UniquenessConstraintSpec("L", "p"),
            UniquenessConstraintSpec::class.java.getConstructor(text, list, text, text, mask, marker).newInstance("L", listOf("p"), null, null, 12, null),
        )
        assertEquals(
            UniquenessConstraintSpec("L", "p", nodeIdProperty = "id"),
            UniquenessConstraintSpec::class.java.getConstructor(text, text, text, text, mask, marker).newInstance("L", "p", null, "id", 4, null),
        )
    }

    @Test
    fun `an annotation that ignores case gives a spec that does, on a CaseInsensitive field and no other`() {
        val specs = FragmentSchemaScanner.scan(Member::class.java, null)
        assertTrue(UniquenessConstraintSpec("Member", "email", nodeIdProperty = "id", ignoreCase = true) in specs, "$specs")
        assertTrue(RangeIndexSpec("Member", "email", ignoreCase = true) in specs, "$specs")

        val refused = assertFailsWith<DrivineException> { FragmentSchemaScanner.scan(Uncopied::class.java, null) }
        assertContains(refused.message!!, "'email' is not the property of a @CaseInsensitive field")
    }

    @Test
    fun `FalkorDB is refused a constraint over a copy, which GRAPH CONSTRAINT cannot name`() {
        val refused = assertFailsWith<DrivineException> {
            FalkorDbSchemaGrammar().requireConstrainable(UniquenessConstraintSpec("CiAccount", "email", ignoreCase = true).made())
        }
        assertContains(refused.message!!, "FalkorDB/FalkorDB/issues/551")
        FalkorDbSchemaGrammar().requireConstrainable(UniquenessConstraintSpec("CiAccount", "email"))
    }

    @NodeFragment(labels = ["Plain"])
    data class Plain(@NodeId val id: String, val email: String? = null)

    @NodeFragment(labels = ["Counted"])
    data class Counted(@NodeId val id: String, @CaseInsensitive val count: Int = 0)

    @NodeFragment(labels = ["Member"])
    data class Member(@NodeId val id: String, @CaseInsensitive @Unique(ignoreCase = true) @RangeIndex(ignoreCase = true) val email: String? = null)

    @NodeFragment(labels = ["Uncopied"])
    data class Uncopied(@NodeId val id: String, @Unique(ignoreCase = true) val email: String? = null)
}
