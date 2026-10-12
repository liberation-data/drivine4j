package org.drivine.test

/**
 * What a fixture says, once [MiniCypherParser] has read it: a statement of clauses, each holding
 * the patterns and expressions it is written with. It is the whole of the subset [MiniCypher]
 * lists and nothing more, so a statement that has a tree is one the interpreter can work out, and
 * [MiniCypherEvaluator] answers for every kind of clause and expression there is.
 *
 * The tree is values alone. It holds no position in the text and nothing of the graph: what is
 * refused for where it stands is refused by the parser, before there is a tree.
 */
internal object MiniCypherAst {

    /** A fixture: its clauses in the order they are written, each working on the rows the one before left. */
    data class Statement(val clauses: List<Clause>)

    /** One clause of a statement. */
    sealed interface Clause {

        /** `MATCH` of [patterns], each row joined with every way they are found, and kept if [where] holds for it. */
        data class Match(val patterns: List<Pattern>, val where: Expression?) : Clause

        /** `CREATE` of [patterns], made once for each row. */
        data class Create(val patterns: List<Pattern>) : Clause

        /** `MERGE` of one [pattern]: found for a row, or made for it where it is not there. */
        data class Merge(val pattern: Pattern) : Clause

        /** `SET` of [items], each applied to every row in the order written. */
        data class Set(val items: List<SetItem>) : Clause

        /** `REMOVE` of the property [key] from what [variable] is bound to. */
        data class Remove(val variable: String, val key: String) : Clause

        /** `DETACH DELETE` of the node [variable] is bound to, its relationships with it. */
        data class DetachDelete(val variable: String) : Clause

        /** `UNWIND` of [list], a row for each of its items with the item bound to [variable]. */
        data class Unwind(val list: Expression, val variable: String) : Clause

        /** `WITH` of [variables], the only ones carried on, and the rows [where] holds for. */
        data class With(val variables: List<String>, val where: Expression?) : Clause

        /**
         * `RETURN` of one [value] for each row, sorted by [order] when there is one. The [alias]
         * names the value to the `ORDER BY` and to nothing else: a row that is returned is its value.
         */
        data class Return(val value: Expression, val alias: String?, val order: Order?) : Clause

        /** `RETURN count(…)`: one row, the number of rows [counted] is not null for. */
        data class ReturnCount(val counted: Expression) : Clause
    }

    /** One item of a `SET`. */
    sealed interface SetItem {

        /** `n.key = value`. A null removes the property. */
        data class Property(val variable: String, val key: String, val value: Expression) : SetItem

        /** `n:A:B`: the labels are added to the node. */
        data class Labels(val variable: String, val labels: List<String>) : SetItem
    }

    /** The `ORDER BY` of a `RETURN`: the one [key] rows are sorted by, and which way. */
    data class Order(val key: Expression, val descending: Boolean)

    /**
     * A path as a pattern writes it: [nodes], and the relationship between each and the next, so
     * there is one more node than there are [edges].
     */
    data class Pattern(val nodes: List<NodePattern>, val edges: List<EdgePattern> = emptyList())

    /**
     * A node of a pattern: the variable it binds, and the labels and properties a node must have
     * to match, or is made with. A property's expression names no variable: the parser refuses one.
     */
    data class NodePattern(val variable: String? = null, val labels: List<String> = emptyList(), val properties: Map<String, Expression> = emptyMap())

    /** A relationship of a pattern: the variable it binds, its one type if it names one, its properties, and which way it runs. */
    data class EdgePattern(val variable: String? = null, val type: String? = null, val properties: Map<String, Expression> = emptyMap(), val direction: Direction)

    /** Which way a relationship runs between the node written before it and the one written after. */
    enum class Direction {
        /** `(a)-[]->(b)`: from the node before to the node after. */
        OUTGOING,

        /** `(a)<-[]-(b)`: from the node after to the node before. */
        INCOMING,

        /** `(a)-[]-(b)`: taken from either end when it is matched, and not something that can be created. */
        EITHER,
    }

    /** Something with a value for a row. */
    sealed interface Expression {

        /** A string, a number, `true`, `false` or `null`, as written. */
        data class Literal(val value: Any?) : Expression

        /** `$name`: the value bound to the statement under [name]. The parser has seen that one is. */
        data class Parameter(val name: String) : Expression

        /** What a row binds [name] to. */
        data class Variable(val name: String) : Expression

        /** `n.key`: the property [key] of what [variable] is bound to. */
        data class Property(val variable: String, val key: String) : Expression

        /** `n[key]`: the property of what [variable] is bound to that [key] gives the name of. */
        data class Lookup(val variable: String, val key: Expression) : Expression

        /** `[a, b]`. */
        data class ListOf(val items: List<Expression>) : Expression

        /** `{a: x, b: y}`, its entries in the order written. */
        data class MapOf(val entries: Map<String, Expression>) : Expression

        /** A call of one of the functions the interpreter knows, with as many arguments as it takes. */
        data class Call(val function: MiniCypherFunction, val arguments: List<Expression>) : Expression

        /** `a + b`: a sum of numbers, or the two as text joined. */
        data class Plus(val left: Expression, val right: Expression) : Expression

        /** `a = b` and the other comparisons of two values. */
        data class Compare(val comparison: Comparison, val left: Expression, val right: Expression) : Expression

        /** `a IS NULL`, or `a IS NOT NULL` when [negated]. */
        data class IsNull(val operand: Expression, val negated: Boolean) : Expression

        /** `a STARTS WITH b`. */
        data class StartsWith(val value: Expression, val prefix: Expression) : Expression

        /** `a IN list`. */
        data class In(val value: Expression, val list: Expression) : Expression

        /** `a AND b`. */
        data class And(val left: Expression, val right: Expression) : Expression

        /** The searched `CASE`: the value of the first of [branches] whose condition holds, or of [otherwise], or null. */
        data class Case(val branches: List<Branch>, val otherwise: Expression?) : Expression
    }

    /** One `WHEN condition THEN value` of a `CASE`. */
    data class Branch(val condition: Expression, val value: Expression)

    /** A comparison of two values, and the [symbol] it is written with. */
    enum class Comparison(val symbol: String) {
        EQUAL("="),
        NOT_EQUAL("<>"),
        AT_LEAST(">="),
        AT_MOST("<="),
        GREATER(">"),
        LESS("<"),
    }
}
