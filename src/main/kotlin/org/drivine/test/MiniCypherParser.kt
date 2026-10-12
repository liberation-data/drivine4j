package org.drivine.test

import org.drivine.test.MiniCypherAst.Branch
import org.drivine.test.MiniCypherAst.Clause
import org.drivine.test.MiniCypherAst.Comparison
import org.drivine.test.MiniCypherAst.Direction
import org.drivine.test.MiniCypherAst.EdgePattern
import org.drivine.test.MiniCypherAst.Expression
import org.drivine.test.MiniCypherAst.NodePattern
import org.drivine.test.MiniCypherAst.Order
import org.drivine.test.MiniCypherAst.Pattern
import org.drivine.test.MiniCypherAst.SetItem
import org.drivine.test.MiniCypherAst.Statement
import org.drivine.test.MiniCypherLexer.Kind
import org.drivine.test.MiniCypherLexer.Token

/**
 * Reads a fixture's text once, into the [Statement] it says, or refuses it. Whatever the
 * interpreter does not read is refused here, before the graph is loaded and before anything is
 * worked out, with the place in the text it stands at: a clause or an operator outside the subset
 * [MiniCypher] lists, a function not known or given the wrong number of arguments, a parameter not
 * among [parameters], a variable no clause before has bound, and a `CREATE` of something that
 * cannot be made.
 *
 * It is a recursive descent, one function for each thing the grammar names:
 *
 * ```
 * statement  = { clause [";"] }
 * clause     = "MATCH" patterns [ "WHERE" condition ]
 *            | "CREATE" patterns
 *            | "MERGE" pattern
 *            | "SET" item { "," item }
 *            | "REMOVE" variable "." name
 *            | "DETACH" "DELETE" variable
 *            | "UNWIND" expression "AS" name
 *            | "WITH" variable { "," variable } [ "WHERE" condition ]
 *            | "RETURN" "count" "(" condition ")" [ "AS" name ]
 *            | "RETURN" condition [ "AS" name ] [ "ORDER" "BY" expression [ "ASC" | "DESC" ] ]
 * item       = variable "." name "=" expression | variable ":" name { ":" name }
 * patterns   = pattern { "," pattern }
 * pattern    = node { ( "-" | "<-" ) [ "[" [name] [ ":" name ] [properties] "]" ] ( "-" | "->" ) node }
 * node       = "(" [name] { ":" name } [properties] ")"
 * properties = "{" [ name ":" expression { "," name ":" expression } ] "}"
 * condition  = comparison { "AND" comparison }
 * comparison = expression [ "IS" ["NOT"] "NULL" | "STARTS" "WITH" expression | "IN" expression
 *                         | ( "=" | "<>" | "<" | "<=" | ">" | ">=" ) expression ]
 * expression = term { "+" term }
 * term       = string | ["-"] number | "true" | "false" | "null" | "$" name
 *            | "[" [ expression { "," expression } ] "]"
 *            | "{" [ name ":" condition { "," name ":" condition } ] "}"
 *            | "CASE" { "WHEN" condition "THEN" expression } [ "ELSE" expression ] "END"
 *            | name "(" [ expression { "," expression } ] ")"
 *            | variable [ "." name | "[" expression "]" ]
 * ```
 *
 * A keyword is a word in upper case, and only where the grammar looks for one: nothing is
 * reserved, so a variable may be called `type`. A name is a word, or anything in
 * backticks. There are no brackets around an expression, and a comparison is of two expressions
 * and no more.
 *
 * The parser keeps the variables bound so far, as the clauses bind them: a pattern's, the one an
 * `UNWIND` names, only those a `WITH` lists after it, and a `RETURN`'s alias for its `ORDER BY`.
 */
internal class MiniCypherParser(private val text: String, private val parameters: Set<String> = emptySet()) {

    private val tokens = MiniCypherLexer(text).tokens()
    private var index = 0

    /** The variables bound where the parser stands. */
    private var scope = setOf<String>()

    /** Whether the parser stands in the properties of a pattern, where no variable can be named. */
    private var inPattern = false

    private val token: Token get() = tokens[index]

    /** The statement the text says. It is read to its end: what follows the last clause is refused as no clause. */
    fun statement(): Statement {
        val clauses = mutableListOf<Clause>()
        while (token.kind != Kind.END) {
            clauses += clause()
            symbol(";")
        }
        return Statement(clauses)
    }

    private fun clause(): Clause = when {
        word("MATCH") -> Clause.Match(patterns(), where())
        word("CREATE") -> Clause.Create(patterns(creating = true))
        word("MERGE") -> Clause.Merge(pattern())
        word("SET") -> Clause.Set(several(::item))
        word("REMOVE") -> {
            val variable = bound()
            expect(".")
            Clause.Remove(variable, name() ?: expected("a property name"))
        }
        word("DETACH") -> {
            if (!word("DELETE")) expected("DELETE")
            Clause.DetachDelete(bound())
        }
        word("UNWIND") -> {
            val list = expression()
            if (!word("AS")) expected("AS")
            val variable = name() ?: expected("a variable")
            scope = scope + variable
            Clause.Unwind(list, variable)
        }
        word("WITH") -> {
            val kept = several(::bound)
            scope = kept.toSet()
            Clause.With(kept, where())
        }
        word("RETURN") -> returned()
        else -> expected("a clause")
    }

    private fun where(): Expression? = if (word("WHERE")) condition() else null

    private fun returned(): Clause {
        if (token.isWord("count") && tokens[index + 1].isSymbol("(")) {
            index += 2
            val counted = condition()
            expect(")")
            if (word("AS")) name() ?: expected("an alias")
            return Clause.ReturnCount(counted)
        }
        val value = condition()
        val alias = if (word("AS")) name() ?: expected("an alias") else null
        if (!word("ORDER")) return Clause.Return(value, alias, null)
        if (!word("BY")) expected("BY")
        // The alias names the value in the ORDER BY, and nowhere after it.
        val outer = scope
        alias?.let { scope = scope + it }
        val key = expression()
        scope = outer
        val descending = word("DESC")
        if (!descending) word("ASC")
        return Clause.Return(value, alias, Order(key, descending))
    }

    private fun item(): SetItem {
        val variable = bound()
        if (token.isSymbol(":")) {
            val labels = mutableListOf<String>()
            while (symbol(":")) labels += name() ?: expected("a label")
            return SetItem.Labels(variable, labels)
        }
        expect(".")
        val key = name() ?: expected("a property name")
        expect("=")
        return SetItem.Property(variable, key, expression())
    }

    // ----- Patterns -----

    private fun patterns(creating: Boolean = false): List<Pattern> = several { pattern(creating) }

    /**
     * A path of nodes and the relationships between them. One that is [creating] is held to what a
     * `CREATE` can make: a relationship with a direction and a type, and no node that is bound
     * already described again. A `MERGE` is not held to it here, for it makes its pattern only for
     * a row it is not found for, and is refused then.
     */
    private fun pattern(creating: Boolean = false): Pattern {
        val nodes = mutableListOf(node(creating))
        val edges = mutableListOf<EdgePattern>()
        while (true) {
            val start = token
            // An arrow's head and its dash stand together; apart, the `<` is a comparison's.
            val incoming = start.isSymbol("<") && tokens[index + 1].let { it.isSymbol("-") && it.position == start.position + 1 }
            if (!incoming && !start.isSymbol("-")) break
            index += if (incoming) 2 else 1
            var variable: String? = null
            var type: String? = null
            var properties = emptyMap<String, Expression>()
            if (symbol("[")) {
                variable = name()
                if (symbol(":")) type = name() ?: expected("a relationship type")
                if (token.isSymbol("{")) properties = properties()
                expect("]")
            }
            expect("-")
            val outgoing = symbol(">")
            // Neither arrow, or both: the relationship is taken either way.
            val direction = if (outgoing == incoming) Direction.EITHER else if (outgoing) Direction.OUTGOING else Direction.INCOMING
            if (creating && direction == Direction.EITHER) refuse("A relationship that is created needs a direction", start)
            if (creating && type == null) refuse("A relationship that is created needs a type", start)
            variable?.let { scope = scope + it }
            edges += EdgePattern(variable, type, properties, direction)
            nodes += node(creating)
        }
        return Pattern(nodes, edges)
    }

    private fun node(creating: Boolean): NodePattern {
        expect("(")
        val named = token
        val variable = name()
        val labels = mutableListOf<String>()
        while (symbol(":")) labels += name() ?: expected("a label")
        val properties = if (token.isSymbol("{")) properties() else emptyMap()
        expect(")")
        // A node bound before is joined, and not described again, as Cypher has it.
        if (creating && variable != null && variable in scope && (labels.isNotEmpty() || properties.isNotEmpty())) {
            refuse("The variable $variable is already bound, and cannot be given labels or properties again", named)
        }
        variable?.let { scope = scope + it }
        return NodePattern(variable, labels, properties)
    }

    /**
     * The properties of a node or a relationship in a pattern. They are read once for the clause,
     * before any row is, so their values can name no variable.
     */
    private fun properties(): Map<String, Expression> {
        inPattern = true
        return entries("a property name", ::expression).also { inPattern = false }
    }

    // ----- Expressions -----

    /** Comparisons joined by `AND`. */
    private fun condition(): Expression {
        var left = comparison()
        while (word("AND")) left = Expression.And(left, comparison())
        return left
    }

    private fun comparison(): Expression {
        val left = expression()
        if (word("IS")) {
            val negated = word("NOT")
            if (!word("NULL")) expected("NULL")
            return Expression.IsNull(left, negated)
        }
        if (word("STARTS")) {
            if (!word("WITH")) expected("WITH")
            return Expression.StartsWith(left, expression())
        }
        if (word("IN")) return Expression.In(left, expression())
        val comparison = Comparison.entries.firstOrNull { symbol(it.symbol) } ?: return left
        return Expression.Compare(comparison, left, expression())
    }

    /** Terms joined by `+`. */
    private fun expression(): Expression {
        var left = term()
        while (symbol("+")) left = Expression.Plus(left, term())
        return left
    }

    private fun term(): Expression {
        val start = token
        return when {
            start.kind == Kind.STRING -> Expression.Literal(start.text).also { index++ }
            start.kind == Kind.NUMBER -> Expression.Literal(numberOf(start.text)).also { index++ }
            start.isSymbol("-") -> {
                // A sign stands against its digits.
                val digits = tokens[index + 1].takeIf { it.kind == Kind.NUMBER && it.position == start.position + 1 } ?: expected("a number")
                index += 2
                Expression.Literal(numberOf("-" + digits.text))
            }
            symbol("$") -> {
                val parameter = name() ?: expected("a parameter name")
                if (parameter !in parameters) refuse("The statement names \$$parameter, and no value is bound to it", start)
                Expression.Parameter(parameter)
            }
            symbol("[") -> Expression.ListOf(if (symbol("]")) emptyList() else several(::expression).also { expect("]") })
            start.isSymbol("{") -> Expression.MapOf(entries("a key", ::condition))
            word("CASE") -> case()
            else -> named()
        }
    }

    private fun case(): Expression {
        val branches = mutableListOf<Branch>()
        while (word("WHEN")) {
            val condition = condition()
            if (!word("THEN")) expected("THEN")
            branches += Branch(condition, expression())
        }
        val otherwise = if (word("ELSE")) expression() else null
        if (!word("END")) expected("END: the fixture interpreter reads the searched CASE alone, `CASE WHEN … THEN … END`")
        return Expression.Case(branches, otherwise)
    }

    /** What opens with a name: a call, a property of a variable, one of `true`, `false` and `null`, or a variable. */
    private fun named(): Expression {
        val start = token
        val name = name() ?: expected("a value")
        return when {
            symbol("(") -> {
                val function = MiniCypherFunction.named(name) ?: refuse(
                    if (name == "count") "The fixture interpreter reads count as the whole of a RETURN alone, and here it is not" else "The fixture interpreter does not know the function $name",
                    start,
                )
                val arguments = if (symbol(")")) emptyList() else several(::expression).also { expect(")") }
                if (arguments.size !in function.arity) refuse("The function $name is given ${arguments.size} arguments, and takes ${taken(function.arity)}", start)
                Expression.Call(function, arguments)
            }
            symbol(".") -> (name() ?: expected("a property name")).let { key -> Expression.Property(variable(name, start), key) }
            symbol("[") -> expression().also { expect("]") }.let { key -> Expression.Lookup(variable(name, start), key) }
            start.isWord("true") -> Expression.Literal(true)
            start.isWord("false") -> Expression.Literal(false)
            start.isWord("null") -> Expression.Literal(null)
            else -> Expression.Variable(variable(name, start))
        }
    }

    private fun taken(arity: IntRange): String = when {
        arity.last == Int.MAX_VALUE -> "${arity.first} or more"
        arity.first == arity.last -> "${arity.first}"
        else -> "${arity.first} to ${arity.last}"
    }

    /** [name], read at [at] where a value is wanted, if a variable can be named there and that one is bound. */
    private fun variable(name: String, at: Token): String {
        if (inPattern) refuse("Expected a value: a pattern's property cannot name the variable $name, for it is read once, before any row is", at)
        if (name !in scope) refuse("Expected a value: the variable $name is not bound here", at)
        return name
    }

    /** A variable a clause works on, which a clause before it must have bound. */
    private fun bound(): String {
        val start = token
        val name = name() ?: expected("a variable")
        if (name !in scope) refuse("Expected a variable bound before: $name is not", start)
        return name
    }

    // ----- Tokens -----

    /** One or more of what [read] reads, with commas between. */
    private fun <T> several(read: () -> T): List<T> = buildList { do add(read()) while (symbol(",")) }

    /** `{name: value, …}`, each value read by [value]; [what] is what a refusal calls the name. */
    private fun entries(what: String, value: () -> Expression): Map<String, Expression> {
        expect("{")
        val entries = linkedMapOf<String, Expression>()
        if (!symbol("}")) {
            do {
                val key = name() ?: expected(what)
                expect(":")
                entries[key] = value()
            } while (symbol(","))
            expect("}")
        }
        return entries
    }

    private fun word(keyword: String): Boolean = token.isWord(keyword).also { if (it) index++ }

    private fun symbol(symbol: String): Boolean = token.isSymbol(symbol).also { if (it) index++ }

    private fun expect(symbol: String) {
        if (!symbol(symbol)) expected("'$symbol'")
    }

    /** A name, plain or in backticks; null when none stands here. */
    private fun name(): String? = token.takeIf { it.isName }?.also { index++ }?.text

    private fun expected(what: String): Nothing = refuse("Expected $what", token)

    private fun refuse(message: String, at: Token): Nothing = throw miniCypherRefusal(text, at.position, message)
}
