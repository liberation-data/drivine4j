package org.drivine.oracle

import org.drivine.manager.Replace
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * The workloads of `ManagerBenchmarkNeo4jTest` through [StatelessGraphObjectManager] on Oracle, on a
 * graph of `DRIVINE_BENCHMARK_CLAIMS` claims (20,000 unless set), as many humans, and three MENTIONS
 * from each claim, with the id of each label constrained unique. Not part of the suite: set
 * `DRIVINE_BENCHMARK=true` as well as `ORACLE_TESTS=true`. It prints the median time of each.
 */
@OracleTests
@EnabledIfEnvironmentVariable(named = "DRIVINE_BENCHMARK", matches = "true")
class OracleManagerBenchmarkTest {

    private val pm = OracleTestDatabase.graphManager("bench")
    private val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val size = System.getenv("DRIVINE_BENCHMARK_CLAIMS")?.toInt() ?: 20_000

    private fun run(sql: String, vararg bound: Pair<String, Any?>) = pm.execute(QuerySpecification.withStatement(sql.trimIndent()).bind(mapOf(*bound)))

    private fun fresh() = "new-" + UUID.randomUUID()
    private fun existing() = "c" + ThreadLocalRandom.current().nextInt(size)
    private fun humans(n: Int) = List(n) { Human(fresh(), "person $it") }

    private fun change(view: ClaimView) = view.copy(
        claim = view.claim.copy(text = "changed " + fresh()),
        people = view.people.drop(1) + Human(fresh(), "newcomer"),
    )

    private fun seed() {
        OracleTestDatabase.clear(pm)
        for (label in listOf("Claim", "Human", "Company")) pm.constraints.ensure(UniquenessConstraintSpec(label, "id"))
        run(
            """
            INSERT INTO bench_node (labels, props)
            SELECT '["Claim"]', JSON_OBJECT('id' VALUE 'c' || (LEVEL - 1), 'text' VALUE 'claim ' || (LEVEL - 1), 'note' VALUE 'seed') FROM dual CONNECT BY LEVEL <= ${'$'}n
            """,
            "n" to size,
        )
        run(
            """
            INSERT INTO bench_node (labels, props)
            SELECT '["Human"]', JSON_OBJECT('id' VALUE 'h' || (LEVEL - 1), 'name' VALUE 'human ' || (LEVEL - 1)) FROM dual CONNECT BY LEVEL <= ${'$'}n
            """,
            "n" to size,
        )
        for (offset in 0..2) {
            run(
                """
                INSERT INTO bench_edge (src, dst, type)
                SELECT c.nid, h.nid, 'MENTIONS'
                FROM bench_node c JOIN bench_node h
                  ON JSON_VALUE(h.props, '$."id"') = 'h' || MOD(TO_NUMBER(SUBSTR(JSON_VALUE(c.props, '$."id"'), 2)) + ${'$'}offset, ${'$'}n)
                WHERE JSON_EXISTS(c.labels, '$[*]?(@ == "Claim")') AND JSON_VALUE(c.props, '$."id"') LIKE 'c%'
                """,
                "offset" to offset, "n" to size,
            )
        }
        run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCH_NODE'); DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCH_EDGE'); END;")
    }

    private fun median(name: String, iterations: Int, work: () -> Unit) {
        repeat(3) { work() }
        val millis = List(iterations) { System.nanoTime().also { work() }.let { (System.nanoTime() - it) / 1_000_000.0 } }.sorted()
        println("BENCH | %-52s | %8.1f ms".format(name, millis[millis.size / 2]))
    }

    @Test
    fun benchmark() {
        seed()
        println("BENCH | graph: $size claims, $size humans, ${size * 3} relationships")
        median("create a fragment", 40) { stateless.save(Claim(fresh(), "t")) }
        median("overwrite an existing fragment, untracked", 40) { stateless.save(Claim(existing(), "t " + fresh())) }
        median("load a fragment by id", 40) { stateless.load(existing(), Claim::class.java)!! }
        median("load a view (3 related nodes)", 40) { stateless.load(existing(), ClaimView::class.java)!! }
        median("load, change, save a view: update", 30) { stateless.update(existing(), ClaimView::class.java) { change(it) } }
        median("load, change, save a view: Replace", 30) { stateless.save(change(stateless.load(existing(), ClaimView::class.java)!!), Replace(ClaimView::people)) }
        median("save a new view with 10 new related nodes", 30) { stateless.save(ClaimView(Claim(fresh(), "t"), people = humans(10))) }
        median("save a new view with 100 new related nodes", 15) { stateless.save(ClaimView(Claim(fresh(), "t"), people = humans(100))) }
        median("save a new view with 1000 new related nodes", 5) { stateless.save(ClaimView(Claim(fresh(), "t"), people = humans(1000))) }
        median("saveAll 1000 new fragments", 5) { stateless.saveAll(List(1000) { Claim(fresh(), "t") }) }
        median("saveAll 100 new views of 10 related nodes", 5) { stateless.saveAll(List(100) { ClaimView(Claim(fresh(), "t"), people = humans(10)) }) }
        median("count the claims", 10) { stateless.count(Claim::class.java) }
    }
}
