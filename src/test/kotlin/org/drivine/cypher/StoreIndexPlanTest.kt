package org.drivine.cypher

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.StoreYaml
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.made
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each index `store.yaml` pairs with a read, made on the store through the schema API, answers that read: an index whose
 * DDL is right and that no read is planned through is worth nothing. The spec's own test checks the compiled read tests
 * the index's expressions where a planner reads an index by them; this asks the planner (drivine-cypher-spec#105).
 *
 * For each case with a `read`: the graph is emptied, a few hundred nodes of the case's label are written beside as many
 * of another label, each with a value of its own for each of the index's properties, so that the label and the value
 * both narrow the read; the index or constraint is ensured, which on a spec that ignores case fills in the lowercased
 * copies the read compares; the store's statistics are gathered; and the read, compiled as the store compiles it (on
 * Oracle, knowing the case's `indexedProperties`), is planned. The plan must use the index the case's `holds` are the
 * expressions of, by its name: the one of the statements drivine4j's grammar makes for the case that is made over
 * every one of them. What an earlier case made is dropped first (see [dropMade]), so a case that failed, or whose drop
 * did, leaves the next no index to be planned through or blocked by, and no drop hides the failure of its own case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class StoreIndexPlanContract {

    /** `postgres` or `oracle`, as `store.yaml` names the store. */
    abstract val store: String

    abstract val pm: NonTransactionalPersistenceManager
    abstract val cypher: PersistenceManager

    /** The grammar of the graph [pm] speaks to, whose statements name the index a read must use. */
    abstract val grammar: SchemaGrammar

    /** The read as the store compiles it, knowing [indexedProperties] where the store is told of them. */
    abstract fun compiled(read: StoreYaml.Read): CompiledCypher

    /** The plan the store makes for [compiled], as it writes it. */
    abstract fun plan(compiled: CompiledCypher): String

    /** Whether [plan] reads through the index [name]. */
    abstract fun uses(plan: String, name: String): Boolean

    /** Gathers the statistics of the graph's tables, so the planner knows how few nodes a value finds. */
    abstract fun analyze()

    @TestFactory
    fun `each read store yaml pairs with an index is planned through that index`(): List<DynamicTest> =
        StoreYaml.reads.map { read -> DynamicTest.dynamicTest(read.case) { planned(read) } }

    private fun planned(read: StoreYaml.Read) {
        val spec = read.spec
        org.drivine.test.SqlCypherFixtures.clear(pm)
        dropMade()
        // Written before the index is made: a spec that ignores case has its lowercased copies filled in as it is made.
        for (label in listOf(spec.label, DECOY)) {
            val properties = spec.properties.joinToString(", ") { "${SchemaGrammar.quoted(it)}: 'v' + toString(k)" }
            query("UNWIND range(1, $NODES) AS k CREATE (:${SchemaGrammar.quoted(label)} {$properties})")
        }
        when (spec) {
            is IndexSpec -> pm.indexes.ensure(spec)
            is ConstraintSpec -> pm.constraints.ensure(spec)
        }
        analyze()
        val index = index(read)
        val plan = plan(compiled(read))
        println("${read.case} on $store, through $index\n${read.cypher}\n$plan\n")
        assertTrue(uses(plan, index), "${read.case}: ${read.cypher} on $store is not planned through $index:\n$plan")
    }

    /**
     * Drops each constraint and range index the graph's catalog lists, which only this test makes: on Oracle the range
     * index a constraint brings among them, which stays when the constraint is dropped.
     */
    private fun dropMade() {
        pm.constraints.list().forEach { pm.constraints.drop(UniquenessConstraintSpec(it.label, it.properties)) }
        pm.indexes.list().filter { it.kind == SchemaItemKind.RANGE_INDEX }.forEach { pm.indexes.drop(RangeIndexSpec(it.label, it.properties)) }
    }

    /**
     * The name of the index the read's `holds` are the expressions of: of the statements the grammar makes for the case,
     * the `CREATE INDEX` over each of them, as it is written or within a PL/SQL literal, its quotes doubled.
     */
    private fun index(read: StoreYaml.Read): String {
        val made = read.spec.made()
        val statements = when (made) {
            is IndexSpec -> grammar.createIndex(made)
            is ConstraintSpec -> grammar.createConstraint(made)
        }.map { (it as SchemaStatement.Cypher).statement }
        val holds = read.holds[store] ?: fail("${read.case}: no holds on $store")
        val creates = statements.flatMap { statement -> CREATE.findAll(statement).map { it.groupValues[1] to it.value }.toList() }
        val over = creates.filter { (_, create) -> holds.all { it in create || it.replace("'", "''") in create } }
        return over.singleOrNull()?.first ?: fail("${read.case}: not one index on $store is made over $holds, of:\n${creates.joinToString("\n") { it.second }}")
    }

    private fun query(statement: String) = cypher.query(QuerySpecification.withStatement(CypherStatement(statement)))

    companion object {
        const val GRAPH = "storeplan"

        /** The nodes of the case's label, and as many of [DECOY]. */
        const val NODES = 400

        /** A label of nodes with the same properties and values, which an index over the case's label leaves out. */
        const val DECOY = "Decoy"

        /** An index made, its name and the line that makes it. */
        private val CREATE = Regex("""CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+) ON [^\n]*""")
    }
}

@PostgresTests
class PostgresStoreIndexPlanTest : StoreIndexPlanContract() {
    override val store = "postgres"
    override val pm by lazy { PostgresTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { PostgresTestDatabase.fixtures(pm) }
    override val grammar: SchemaGrammar by lazy { PostgresSchemaGrammar(PostgresGraph(GRAPH)) }

    override fun compiled(read: StoreYaml.Read) = PostgresCypherCompiler(GRAPH).compile(read.cypher, read.parameters)

    override fun plan(compiled: CompiledCypher): String {
        val connection = PostgresTestDatabase.provider.connect()
        return try {
            connection.query(QuerySpecification.withStatement("EXPLAIN ${compiled.sql}").bind(compiled.parameters)).joinToString("\n")
        } finally {
            connection.release()
        }
    }

    /** By an index scan of it, or a bitmap one: Postgres keeps the bare name in lower case. */
    override fun uses(plan: String, name: String) =
        Regex("""(Index Scan|Index Only Scan) using ${name.lowercase()} on ${GRAPH}_node|Bitmap Index Scan on ${name.lowercase()}\b""").containsMatchIn(plan)

    override fun analyze() = PostgresTestDatabase.run("ANALYZE ${GRAPH}_node")
}

@OracleTests
class OracleStoreIndexPlanTest : StoreIndexPlanContract() {
    override val store = "oracle"
    override val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { OracleTestDatabase.fixtures(pm) }
    override val grammar: SchemaGrammar by lazy { OracleSchemaGrammar(OracleGraph(GRAPH)) }

    /** Knowing the labelled indexes the case's index gives, as drivine4j tells the compiler of those the catalog lists. */
    override fun compiled(read: StoreYaml.Read) =
        OracleCypherCompiler(GRAPH, indexedProperties = read.indexedProperties).compile(read.cypher, read.parameters)

    override fun plan(compiled: CompiledCypher) = OracleTestDatabase.plan(pm, compiled.sql, compiled.parameters, "BASIC +PREDICATE")

    /** By a scan of it: Oracle keeps the bare name in upper case. */
    override fun uses(plan: String, name: String) =
        Regex("""INDEX (UNIQUE|RANGE) SCAN\s*\|\s*${name.uppercase()}\b""").containsMatchIn(plan)

    override fun analyze() = OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE'); END;")
}
