package org.drivine.postgres

import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.schema.EnsureResult
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An ensure builds a range index `CONCURRENTLY` (drivine-cypher-spec#381), so a write to the label goes on while it is
 * built: a node written once the build is under way commits before the build ends, where a plain `CREATE INDEX`, which
 * holds a lock that refuses writes to the table, would have held it until the end. The index is then valid, and holds
 * the node written meanwhile.
 */
@PostgresTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOnlineIndexBuildTest {

    private val graph = "online_build"
    private lateinit var pm: NonTransactionalPersistenceManager

    @BeforeAll
    fun seed() {
        PostgresTestDatabase.drop(graph)
        pm = PostgresTestDatabase.graphManager(graph)
        // Enough nodes that the build takes seconds, written in one statement.
        PostgresTestDatabase.run(
            "INSERT INTO ${graph}_node (labels, props) SELECT '[\"Big\"]'::jsonb, jsonb_build_object('id', g) FROM generate_series(1, $NODES) g",
        )
        PostgresTestDatabase.vacuum(graph)
    }

    @AfterAll
    fun drop() {
        PostgresTestDatabase.drop(graph)
    }

    /** How many index builds are under way in this database. */
    private fun building(): Long = PostgresTestDatabase.pm.query(
        QuerySpecification.withStatement(sqlStatement("SELECT count(*) FROM pg_stat_progress_create_index WHERE datname = current_database()"))
            .transform(Long::class.java),
    ).single()

    @Test
    fun `a write to the label commits while its range index is being built`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val ensure = pool.submit<EnsureResult> { pm.indexes.ensure(RangeIndexSpec("Big", "id")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (building() == 0L) {
                check(!ensure.isDone) { "the build ended before it was seen under way: ${ensure.get()}" }
                check(System.nanoTime() < deadline) { "no build was seen under way" }
                Thread.sleep(5)
            }
            val started = System.nanoTime()
            PostgresTestDatabase.fixtures(pm).execute(
                QuerySpecification.withStatement(cypherStatement("CREATE (:Big {id: \$id})")).bind(mapOf("id" to -1)),
            )
            val wrote = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val stillBuilding = building() > 0
            val result = ensure.get(5, TimeUnit.MINUTES)

            assertTrue(stillBuilding, "the write took $wrote ms and committed only once the build had ended")
            assertIs<EnsureResult.Created>(result)
            val valid = PostgresTestDatabase.pm.query(
                QuerySpecification.withStatement(
                    sqlStatement(
                        "SELECT bool_and(i.indisvalid) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                            "WHERE c.relname LIKE \$name AND i.indrelid = '${graph}_node'::regclass",
                    ),
                ).bind(mapOf("name" to "${graph}_ix%")).transform(Boolean::class.java),
            ).single()
            assertTrue(valid, "the index built online is valid")
            val found = PostgresTestDatabase.fixtures(pm).query(
                QuerySpecification.withStatement(cypherStatement("MATCH (n:Big {id: -1}) RETURN count(n)")).transform(Long::class.java),
            )
            assertEquals(listOf(1L), found)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val NODES = 1_000_000
    }
}
