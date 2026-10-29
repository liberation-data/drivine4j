package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.sqlite.Function
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * `drivine_cypher(steps, parameters)`: carries out the procedure a Cypher statement that writes compiles to (see
 * `SqliteCypherCompiler.procedure`), as Postgres runs its PL/pgSQL and Oracle its PL/SQL, step by step on the connection
 * the statement runs on, in its transaction, and gives back the rows it returns as a JSON list.
 *
 * Each step has its SQL, in which each variable it reads is a numbered parameter, and the variables bound to them in
 * turn: the parameters' object, the rows carried from clause to clause, the id a write made. A step sets a variable to
 * what its query gives, loops over a query's rows, which are read whole before the loop writes anything, writes, fails
 * the procedure, or returns rows. The steps run within a savepoint of their own: a procedure that fails is rolled back
 * to where it began, so the statement is applied whole or not at all.
 */
internal class SqliteCypherFunction(connection: Connection) : Function() {

    /** The statements the steps run, prepared once each on the connection: a loop runs its body's for each row. */
    private val statements = SqliteStatements(connection)

    override fun xFunc() {
        val steps = json.readTree(requireNotNull(value_text(0)) { "drivine_cypher has no steps." })
        val variables = hashMapOf<String, Any?>(PARAMS to value_text(1))
        val rows = mutableListOf<Any?>()
        statements.run("SAVEPOINT $SAVEPOINT")
        try {
            run(steps["steps"], variables, rows)
            statements.run("RELEASE $SAVEPOINT")
        } catch (failure: Throwable) {
            runCatching { statements.run("ROLLBACK TO $SAVEPOINT") }
            runCatching { statements.run("RELEASE $SAVEPOINT") }
            throw failure
        }
        result(rows.joinToString(",", "[", "]") { it?.toString() ?: "null" })
    }

    private fun run(steps: JsonNode, variables: MutableMap<String, Any?>, rows: MutableList<Any?>) {
        for (step in steps) {
            when (val op = step["op"].asText()) {
                "select" -> variables[step["variable"].asText()] = query(step, variables).firstOrNull()
                "loop" -> query(step, variables).forEach { item ->
                    variables[step["record"].asText()] = item
                    run(step["body"], variables, rows)
                }
                "if" -> if ((query(step, variables).firstOrNull() as? Number)?.toInt() == 1) run(step["body"], variables, rows)
                "raise" -> throw SQLException(step["message"].asText())
                "execute" -> {
                    val into = step["variable"]?.takeUnless { it.isNull }?.asText()
                    if (into != null) variables[into] = query(step, variables).firstOrNull() else statements.update(sql(step), *values(step, variables))
                }
                "return" -> rows.addAll(query(step, variables))
                else -> throw SQLException("drivine_cypher has no step $op.")
            }
        }
    }

    /** The first column of each row the step's query gives. */
    private fun query(step: JsonNode, variables: Map<String, Any?>): List<Any?> = statements.rows(sql(step), *values(step, variables)) { first(it) }

    private fun sql(step: JsonNode): String = step["sql"].asText()

    /** The values of the variables the step reads, in the order its parameters are numbered. */
    private fun values(step: JsonNode, variables: Map<String, Any?>): Array<Any?> = step["variables"].map { variables[it.asText()] }.toTypedArray()

    /** The first column of the row [results] is at: a whole number as a Long, a real one as a Double, and text as it is. */
    private fun first(results: ResultSet): Any? = when (val value = results.getObject(1)) {
        is Int -> value.toLong()
        else -> value
    }

    private companion object {
        val json = ObjectMapper()

        /** The variable the parameters' object is bound to: see `SqlCypherCompiler.PARAMS`. */
        const val PARAMS = "cy_params"

        const val SAVEPOINT = "drivine_cypher"
    }
}
