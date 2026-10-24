package org.drivine.cypher.sql

/**
 * A step of the procedure a statement that writes compiles to: see [SqlCypherCompiler]. Its SQL names the procedure's
 * variables ([SqlCypherCompiler.ROWS], [SqlCypherCompiler.ROW] …) as values it reads. A store with a procedural language
 * writes the steps in it, as Postgres's PL/pgSQL and Oracle's PL/SQL; one without carries them out one by one.
 */
sealed interface ProcedureStep {

    /** [variable] set to the value of [expression] in the first row of the query `SELECT expression` and [from], or to null where it gives none. */
    data class Select(val variable: String, val expression: String, val from: String) : ProcedureStep

    /** [variable] set to the value of [expression], which reads no table. */
    data class Let(val variable: String, val expression: String) : ProcedureStep

    /**
     * [body] for each row of [query], in its order, with the row's `item` column the value [record]`.item`: the rows
     * are the query's as it starts, whatever the body writes. A query written over lines is [multiline].
     */
    data class Loop(val record: String, val query: String, val multiline: Boolean, val body: List<ProcedureStep>) : ProcedureStep

    /** [body], where the SQL truth value [condition] holds. */
    data class If(val condition: String, val body: List<ProcedureStep>) : ProcedureStep

    /** The procedure failed, with [message]: what it wrote is undone. */
    data class Raise(val message: String) : ProcedureStep

    /** The statement [statement], which writes; with [into], the value of the column it returns set to that variable. */
    data class Execute(val statement: String, val into: String?) : ProcedureStep

    /** The rows of [query], whose one column is `result`, returned as the statement's rows. */
    data class Return(val query: String) : ProcedureStep
}
