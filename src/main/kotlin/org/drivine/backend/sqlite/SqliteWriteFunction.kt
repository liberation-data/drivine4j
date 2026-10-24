package org.drivine.backend.sqlite

import org.drivine.backend.sql.WriteReturns
import org.sqlite.Function
import java.sql.Connection

/**
 * `drivine_write(graph, plan, shapes, mark, returns, patched_if)`: carries out a plan of steps (see
 * `WritePlan`) on the graph, as `<graph>_write` does on Postgres and the PL/SQL block on Oracle: the plan
 * is the same, and so is what each step does (see [SqlitePlanWriter]). It gives back a JSON array of
 * what [WriteReturns] asks: the stamps a save hands back, a stamp for each node of a batch, or the
 * number of relationships the steps counted.
 *
 * It is a function SQLite calls from a statement, and runs the steps' SQL on the connection the
 * statement runs on, in its transaction. The steps run within a savepoint of their own: a plan that
 * fails, or finds its root stale, is rolled back to where it began, so a save is applied whole or not
 * at all, with or without a transaction around it. A stale root gives nothing back; a node a `ref` step
 * needs that is not there counts nothing.
 *
 * SQLite has one writer at a time, so a node is not locked as it is found, as it is on Postgres. A
 * statement outside a transaction reads before it writes, and if another connection wrote in between,
 * SQLite refuses the write as busy: the save is then run again, as on any store that turns a write
 * away as contended.
 */
internal class SqliteWriteFunction(private val connection: Connection) : Function() {

    /** The statements the steps run, prepared once each on the connection. */
    private val statements = SqliteStatements(connection)

    override fun xFunc() {
        val graph = SqliteGraph(requireNotNull(value_text(0)) { "drivine_write names no graph." })
        val writer = SqlitePlanWriter(
            graph, statements,
            plan = requireNotNull(value_text(1)) { "drivine_write has no plan." },
            shapes = value_text(2) ?: "[]",
            mark = requireNotNull(value_text(3)) { "drivine_write has no mark." },
            patchedIf = value_text(5),
        )
        val returns = WriteReturns.valueOf(requireNotNull(value_text(4)))
        statements.run("SAVEPOINT $SAVEPOINT")
        val given = try {
            writer.write(returns).also { statements.run("RELEASE $SAVEPOINT") }
        } catch (stopped: SqlitePlanWriter.Stopped) {
            // Rolled back: nothing was written.
            statements.run("ROLLBACK TO $SAVEPOINT")
            statements.run("RELEASE $SAVEPOINT")
            if (returns == WriteReturns.COUNT) listOf(stopped.count) else emptyList()
        } catch (failure: Throwable) {
            runCatching { statements.run("ROLLBACK TO $SAVEPOINT") }
            runCatching { statements.run("RELEASE $SAVEPOINT") }
            throw failure
        }
        result(SqlitePlanWriter.json.writeValueAsString(given))
    }

    private companion object {
        const val SAVEPOINT = "drivine_write"
    }
}
