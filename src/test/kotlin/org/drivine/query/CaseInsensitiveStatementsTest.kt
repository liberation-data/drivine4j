package org.drivine.query

import org.drivine.DrivineException
import org.drivine.annotation.CaseInsensitive
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphBackends
import org.drivine.backend.LowerCopyLookups
import org.drivine.backend.oracle.LabelledIndex
import org.drivine.backend.oracle.LabelledIndexes
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleLowerCopies
import org.drivine.backend.oracle.OracleSelect
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresLowerCopies
import org.drivine.backend.postgres.PostgresSelect
import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.model.LowerCopies
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.containsIgnoreCase
import org.drivine.query.dsl.eqIgnoreCase
import org.drivine.query.dsl.not
import org.drivine.query.dsl.query
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.schema.CypherLowerCopies
import org.drivine.schema.CypherStoreStamp
import org.drivine.schema.FalkorDbSchemaGrammar
import org.drivine.schema.FragmentSchemaScanner
import org.drivine.schema.LowerCopyCatalog
import org.drivine.schema.LowerCopyStatements
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.made
import org.drivine.store.StoreIdentityResolver
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.caseinsensitive.CiAccount
import sample.caseinsensitive.CiAccountQueryDsl
import sample.caseinsensitive.CiAccountView
import sample.caseinsensitive.CiAccountViewQueryDsl
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun `once a copy is recorded, a lookup on the root compares with it, within anyOf and not too, and one on a related node does not`() {
        val store = RecordingStore(DatabaseType.NEO4J)
        val cypher = assertIs<LowerCopyLookups>(GraphBackends.of(store, Neo4jObjectMapper.instance)).backend
        // The record read on the caller's thread, so the first lookup is written with it.
        fun recording(vararg labels: String): GraphBackend = LowerCopyLookups(cypher) {
            LowerCopyCatalog(store, { labels.map { LowerCopyStatements.entry(it, "email") } }, refresh = Runnable::run)
        }
        fun account(backend: GraphBackend): String = backend.loadAll(
            CiAccount::class.java,
            GraphQuerySpec(CiAccountQueryDsl.INSTANCE).apply {
                where {
                    anyOf { query.email eqIgnoreCase "Ann@X.com"; query.name eq "Ann" }
                    not { query.email containsIgnoreCase "BOB" }
                }
            },
        ).statement.statement
        fun view(backend: GraphBackend): String = backend.loadAll(
            CiAccountView::class.java,
            GraphQuerySpec(CiAccountViewQueryDsl.INSTANCE).apply {
                where {
                    query.account.email eqIgnoreCase "Ann@X.com"
                    query.friends.email eqIgnoreCase "Bob@X.com"
                }
            },
        ).statement.statement

        val recorded = account(recording("CiAccount"))
        assertContains(recorded, "n.$copy = toLower(")
        assertContains(recorded, "n.$copy CONTAINS toLower(")
        assertFalse("toLower(n.email)" in recorded, recorded)
        // A record of another label is not one of this fragment's.
        assertFalse(copy in account(recording("CiOther")))

        val viewed = view(recording("CiAccount"))
        assertContains(viewed, "account.$copy = toLower(")
        assertContains(viewed, "toLower(friends.email)")
        assertFalse("friends.$copy" in viewed, viewed)
    }

    @Test
    fun `once a copy is recorded, a count, a delete, a grouped read and a patch's predicate compare with it too`() {
        val store = RecordingStore(DatabaseType.NEO4J)
        val cypher = assertIs<LowerCopyLookups>(GraphBackends.of(store, Neo4jObjectMapper.instance)).backend
        val backend = LowerCopyLookups(cypher) {
            LowerCopyCatalog(store, { listOf(LowerCopyStatements.entry("CiAccount", "email")) }, refresh = Runnable::run)
        }
        fun named() = GraphQuerySpec(CiAccountQueryDsl.INSTANCE).apply { where { query.email eqIgnoreCase "Ann@X.com" } }

        val statements = mapOf(
            "count" to backend.count(CiAccount::class.java, named()).statement,
            "deleteAll" to backend.deleteAll(CiAccount::class.java, named()).statement,
            "groups" to backend.groups(
                CiAccount::class.java,
                GroupQuerySpec(CiAccountQueryDsl.INSTANCE).apply {
                    where { query.email eqIgnoreCase "Ann@X.com" }
                    keyOrNull(query.name)
                    count()
                },
            ).statement,
            "patch" to backend.patch(
                CiAccount::class.java, "a1", mapOf("name" to "Ann"),
                listOf(PropertyCondition("n.email", ComparisonOperator.EQUALS_IGNORE_CASE, "Ann@X.com")),
            ).statement,
        )
        statements.forEach { (name, statement) ->
            assertContains(statement, "n.$copy = toLower(", message = name)
            assertFalse("toLower(n.email) =" in statement, "$name: $statement")
        }
    }

    @Test
    fun `a field stored under a GraphProperty name is copied, recorded and looked up by that name`() {
        val store = RecordingStore(DatabaseType.NEO4J)
        val cypher = assertIs<LowerCopyLookups>(GraphBackends.of(store, Neo4jObjectMapper.instance)).backend
        val backend = LowerCopyLookups(cypher) {
            LowerCopyCatalog(store, { listOf(LowerCopyStatements.entry("Renamed", "e_mail")) }, refresh = Runnable::run)
        }
        val statement = backend.loadAll(
            Renamed::class.java,
            GraphQuerySpec(RenamedQueryDsl()).apply { where { query.email eqIgnoreCase "Ann@X.com" } },
        ).statement.statement

        assertEquals(listOf("e_mail"), FragmentModel.from(Renamed::class.java).lowerCopied)
        assertTrue(RangeIndexSpec("Renamed", "e_mail", ignoreCase = true) in FragmentSchemaScanner.scan(Renamed::class.java, null))
        assertContains(statement, "n.`__drivine.lower.e_mail` = toLower(")
    }

    @Test
    fun `an item that ignores case is refused by a manager with no store to fill its copies in through`() {
        val store = RecordingStore(DatabaseType.NEO4J)

        val refused = assertFailsWith<DrivineException> { store.indexes.ensure(RangeIndexSpec("CiAccount", "email", ignoreCase = true)) }
        assertContains(refused.message!!, "no store to fill the lowercased copies in through")
        assertTrue(store.schema.isEmpty(), "${store.schema}")
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
    fun `a copy is filled in batch after batch until one writes fewer, then recorded once, and known to this process at once`() {
        val statements = CypherLowerCopies()
        val fill = statements.fill("CiAccount", "email")
        val written = ArrayDeque(listOf(LowerCopyCatalog.BATCH, LowerCopyCatalog.BATCH, 3))
        val store = RecordingStore(DatabaseType.NEO4J, supportsSchemaManagement = false) { ran ->
            when (ran.statement) {
                fill -> listOf(written.removeFirst())
                CypherStoreStamp("DrivineStore", "key").readStamps -> listOf(StoreIdentityResolver.Stamp("s", "t"))
                else -> emptyList<Any?>()
            }
        }
        // The store's record is never read: what this process recorded is known without it.
        val catalog = LowerCopyCatalog(store, { emptyList() }, refresh = {})
        assertFalse(catalog.filledIn("CiAccount", "email"))

        catalog.fillIn("CiAccount", listOf("email"))
        assertEquals(3, store.queried.count { it.statement == fill })
        assertEquals(
            listOf<Any?>(LowerCopyStatements.entry("CiAccount", "email")),
            store.executed.filter { it.statement == statements.record }.map { it.parameters["entry"] },
        )
        assertTrue(catalog.filledIn("CiAccount", "email"))
    }

    @Test
    fun `a copy the store records is not filled in again, unless asked`() {
        val fill = CypherLowerCopies().fill("CiAccount", "email")
        val store = RecordingStore(DatabaseType.NEO4J, supportsSchemaManagement = false) { ran ->
            when (ran.statement) {
                fill -> listOf(0)
                CypherStoreStamp("DrivineStore", "key").readStamps -> listOf(StoreIdentityResolver.Stamp("s", "t"))
                else -> emptyList<Any?>()
            }
        }
        val catalog = LowerCopyCatalog(store, { listOf(LowerCopyStatements.entry("CiAccount", "email")) }, refresh = Runnable::run)

        catalog.fillIn("CiAccount", listOf("email"))
        assertTrue(store.queried.none { it.statement == fill })
        catalog.fillIn("CiAccount", listOf("email"), again = true)
        assertEquals(1, store.queried.count { it.statement == fill })
    }

    @Test
    fun `what another process recorded is read elsewhere, once at a time, kept when a read fails, and read again when old`() {
        val pending = ArrayDeque<Runnable>()
        var failing = false
        var reads = 0
        var now = 0L
        val catalog = LowerCopyCatalog(
            RecordingStore(DatabaseType.NEO4J),
            { reads++; if (failing) throw IllegalStateException("pool exhausted") else listOf(LowerCopyStatements.entry("CiAccount", "email")) },
            maxAge = 1000, clock = { now }, refresh = { pending.add(it) },
        )

        // Until the first read is done, none is known; and asking again begins no second read.
        assertFalse(catalog.filledIn("CiAccount", "email"))
        assertFalse(catalog.filledIn("CiAccount", "email"))
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        assertTrue(catalog.filledIn("CiAccount", "email"))
        assertTrue(pending.isEmpty())

        // Once old, it is read again; a read that fails leaves what was read before.
        now = 1000
        failing = true
        assertTrue(catalog.filledIn("CiAccount", "email"))
        pending.removeFirst().run()
        assertEquals(2, reads)
        assertTrue(catalog.filledIn("CiAccount", "email"))
        assertTrue(pending.isEmpty())
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
    fun `a catalog keeps an item that ignores case apart from one over the property itself`() {
        val plain = RangeIndexSpec("CiAccount", "email")
        val lowered = RangeIndexSpec("CiAccount", "email", ignoreCase = true)
        assertEquals(listOf(plain, lowered), SchemaCatalog.of(plain, lowered, lowered).indexes)
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

    @NodeFragment(labels = ["Renamed"])
    data class Renamed(@NodeId val id: String, @CaseInsensitive @GraphProperty("e_mail") @RangeIndex(ignoreCase = true) val email: String? = null)

    class RenamedQueryDsl(override val nodeAlias: String = "n") : NodeReference {
        val email = StringPropertyReference(nodeAlias, "e_mail")
    }
}
