package org.drivine.cypher.ast

/** An expression of a Cypher statement. */
sealed interface Expression {

    /** A literal: a string, a number (`Long` or `Double`), a boolean, or null. */
    data class Literal(val value: Any?) : Expression

    /** A parameter, `$name`, its value bound when the statement runs. */
    data class Parameter(val name: String) : Expression

    /** A variable a clause bound: a node, a relationship, a path or a value. */
    data class Variable(val name: String) : Expression

    /** A property of a node, a relationship or a map: `subject.key`. */
    data class Property(val subject: Expression, val key: String) : Expression

    /** Whether a node carries each of [labels]: `subject:Label`. */
    data class HasLabels(val subject: Expression, val labels: List<String>) : Expression

    /** A list, `[a, b]`. */
    data class ListLiteral(val items: List<Expression>) : Expression

    /** A map, `{key: value}`, its entries in the order written. */
    data class MapLiteral(val entries: List<Pair<String, Expression>>) : Expression

    /** A map projection, `n { .name, key: value, .*, other }`, its elements in the order written. */
    data class MapProjection(val variable: String, val elements: List<Element>) : Expression {
        sealed interface Element {
            /** `.key`: the property of that name. */
            data class Selected(val key: String) : Element

            /** `key: value`. */
            data class Entry(val key: String, val value: Expression) : Element

            /** `.*`: every property. */
            data object All : Element

            /** `other`: a variable, under its own name. */
            data class Variable(val name: String) : Element
        }
    }

    /** An operator of two operands. */
    data class Binary(val operator: Operator, val left: Expression, val right: Expression) : Expression {
        enum class Operator {
            OR, XOR, AND,
            EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL,
            ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER,
            IN, STARTS_WITH, ENDS_WITH, CONTAINS,
        }
    }

    /** An operator of one operand. */
    data class Unary(val operator: Operator, val operand: Expression) : Expression {
        enum class Operator { NOT, NEGATE, IS_NULL, IS_NOT_NULL }
    }

    /** An item of a list, `subject[index]`, or the value of a map's key, `subject["key"]`. */
    data class Index(val subject: Expression, val index: Expression) : Expression

    /** A slice of a list, `subject[from..to]`; a bound left out is open. */
    data class Slice(val subject: Expression, val from: Expression?, val to: Expression?) : Expression

    /** A function, by its name in lower case with its namespace (`toLower`, `apoc.text.join`). */
    data class Function(val name: String, val distinct: Boolean, val arguments: List<Expression>) : Expression

    /** `count(*)`. */
    data object CountStar : Expression

    /** `CASE`: with a [subject], each alternative's key is compared with it; without one, each is a condition. */
    data class Case(val subject: Expression?, val alternatives: List<Pair<Expression, Expression>>, val otherwise: Expression?) : Expression

    /** `[variable IN list WHERE filter | projection]`. */
    data class ListComprehension(val variable: String, val list: Expression, val where: Expression?, val projection: Expression?) : Expression

    /** `[(a)-[:R]->(b) WHERE filter | projection]`: a value for each way the pattern is found from what is bound. */
    data class PatternComprehension(val pattern: Pattern, val where: Expression?, val projection: Expression) : Expression

    /** A pattern as a condition, `(a)-[:R]->()`: whether it is found. */
    data class PatternPredicate(val pattern: Pattern) : Expression

    /** `EXISTS { pattern WHERE … }`. */
    data class Exists(val patterns: List<Pattern>, val where: Expression?) : Expression

    /** `all`, `any`, `none` or `single` of a list: `any(x IN list WHERE condition)`. */
    data class Quantifier(val kind: Kind, val variable: String, val list: Expression, val where: Expression?) : Expression {
        enum class Kind { ALL, ANY, NONE, SINGLE }
    }
}
