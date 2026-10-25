package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern

/**
 * A procedure the compiler takes, `CALL db.labels()`, read as the query that gives its rows: a SQL store keeps
 * no catalog of labels or types beside its nodes and relationships, so they are read from those, each once, as
 * Neo4j lists only the labels and types that something has now. Its one [field] is what it yields.
 *
 * The query's own variables are named with `#`, which a statement cannot write, so they meet none of the
 * statement's: a `CALL` within a query reads the variables around it, for a `WHERE` after its `YIELD`.
 */
internal enum class Procedure(val qualifiedName: String, val field: String) {

    /** `db.labels()`: each label a node has. */
    LABELS("db.labels", "label") {
        override fun reading(): List<Clause> = listOf(
            Clause.Match(false, listOf(Pattern(null, listOf(NodePattern(ROW, emptyList(), null)), emptyList())), null),
            Clause.Unwind(Expression.Function("labels", false, listOf(Expression.Variable(ROW))), VALUE),
        )

        override val value: Expression get() = Expression.Variable(VALUE)
    },

    /** `db.relationshipTypes()`: each type a relationship has. */
    RELATIONSHIP_TYPES("db.relationshipTypes", "relationshipType") {
        override fun reading(): List<Clause> = listOf(
            Clause.Match(
                false,
                listOf(
                    Pattern(
                        null,
                        listOf(NodePattern(null, emptyList(), null), NodePattern(null, emptyList(), null)),
                        listOf(RelationshipPattern(ROW, emptyList(), RelationshipPattern.Direction.OUTGOING, null, null)),
                    )
                ),
                null,
            ),
        )

        override val value: Expression get() = Expression.Function("type", false, listOf(Expression.Variable(ROW)))
    };

    /** The clauses that read each label or type, once for each node or relationship that has it. */
    protected abstract fun reading(): List<Clause>

    /** The label or type, in the scope [reading] gives. */
    protected abstract val value: Expression

    /**
     * The procedure's rows, each value once, as the query [Query] that returns them: each field yielded under its
     * variable, [yields] pairs of the two, and with [where], only the rows that meet it.
     */
    fun query(arguments: List<Expression>, yields: List<Pair<String, String>>, where: Expression?): Query {
        if (arguments.isNotEmpty()) throw DrivineException("$qualifiedName() takes no argument.")
        yields.forEach { (yielded, _) ->
            if (yielded != field) throw DrivineException("$qualifiedName() yields $field, not $yielded.")
        }
        val variables = yields.map { (_, variable) -> variable }
        fun projection(distinct: Boolean, items: List<Projection.Item>) = Projection(distinct, false, items, emptyList(), null, null)
        val distinct = projection(true, variables.map { Projection.Item(value, it) })
        val clauses = if (where == null) {
            reading() + Clause.Return(distinct)
        } else {
            reading() + Clause.With(distinct, where) + Clause.Return(projection(false, variables.map { Projection.Item(Expression.Variable(it), it) }))
        }
        return Query(clauses)
    }

    companion object {
        private const val ROW = "#procedure"
        private const val VALUE = "#procedureValue"

        /** The procedure named [name], as Neo4j names it, case and all; null for one the compiler does not take. */
        fun named(name: String): Procedure? = entries.firstOrNull { it.qualifiedName == name }
    }
}
