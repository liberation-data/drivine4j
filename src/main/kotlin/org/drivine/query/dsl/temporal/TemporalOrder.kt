package org.drivine.query.dsl.temporal

/**
 * Marks the order functions that read a stored moment as a number, which not every backend can
 * write. A caller opts in with `@OptIn(TemporalOrder::class)`, having checked that its backend is
 * one that supports them:
 *
 * | Backend | `epochSeconds(property)` |
 * |---|---|
 * | Neo4j 5, Neo4j 4 | yes: `(p.x.epochMillis / 1000.0)` |
 * | Memgraph | yes: `(timestamp(p.x) / 1000000.0)` |
 * | Oracle | for a property a field declares as a moment; refused for any other |
 * | FalkorDB | refused: it stores a moment as its ISO-8601 text, and has no function that reads one as a number |
 * | any other grammar | refused |
 *
 * Without the opt-in, a call does not compile. The refusal is the backstop at run time: a backend that cannot write the function throws an
 * [org.drivine.query.dsl.UnsupportedOrderFunctionException] as the statement is built, before
 * anything is sent to the store. Where the backend refuses, store the moment's seconds since the
 * epoch in a property of their own and order by that, or bind a moment of the caller's with
 * `org.drivine.query.dsl.epochSeconds(instant)`, which every backend takes.
 *
 * Java callers do not see an opt-in: the same functions are static methods of `TemporalOrderExpressions`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Reading a stored moment as a number is not supported on every backend: see TemporalOrder for those that refuse it.",
)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class TemporalOrder
