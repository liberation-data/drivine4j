package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.ast.SetItem

/**
 * The kind of each variable of a statement, as Neo4j works it out when it plans the statement, and the statements it
 * refuses for them: a variable used as two kinds, `MATCH (a)-[a]->()` (Neo4j's VariableTypeConflict), and one bound
 * already where a pattern makes it, `MATCH (a) CREATE (a)` (VariableAlreadyBound). Read from the statement alone, before
 * it is compiled, so a statement that compiles is compiled as it was, and one refused here is refused for what is wrong
 * with it, not for what the compiler does not take yet.
 *
 * Only a kind the statement fixes is known: a pattern's node, relationship, variable-length relationship or path, a value
 * written, and a variable a `WITH` passes on as it is. Any other value (a property, a parameter, an unwound item, a
 * function's result) may be of any kind, and is never refused here as being of another: what it is used as is left to
 * the compiler, as before.
 */
internal object VariableKinds {

    /** A kind of variable: see [VariableKinds]. [noun] names a variable of it, as a message does; a value's kind is named as in [Value.Known]. */
    private enum class Kind(val noun: String) {
        NODE("a node"), RELATIONSHIP("a relationship"), RELATIONSHIPS("a list of relationships"), PATH("a path"),
        INTEGER("an integer"), FLOAT("a float"), TEXT("a text"), BOOLEAN("a truth value"), LIST("a list"), MAP("a map"),

        /** Any kind at all: a value whose kind the statement leaves open. */
        ANY("a value"),
    }

    /** The variables in scope, by name, each of its kind. */
    private class Scope(val kinds: MutableMap<String, Kind> = LinkedHashMap()) {
        fun copy() = Scope(LinkedHashMap(kinds))
        operator fun get(name: String) = kinds[name]
        operator fun set(name: String, kind: Kind) {
            kinds[name] = kind
        }
    }

    /** Refuses [query] where a variable of it is used as two kinds, or made where it is bound already. */
    fun check(query: Query) {
        query(query, Scope())
    }

    /** [query]'s variables, from those of [outer] it reads: what each part of it returns. */
    private fun query(query: Query, outer: Scope): List<Map<String, Kind>> =
        (listOf(query.clauses) + query.unions.map { it.clauses }).map { clauses(it, outer.copy()) }

    /**
     * [clauses] in turn, over [scope]: what their `RETURN` returns, of the kinds it returns. Within a `FOREACH`, [foreach],
     * a `CREATE` or a `MERGE` may name a variable bound already, as Neo4j takes it there. [kept] stay in scope after each
     * `WITH` that does not name them, as what a `CALL`'s brackets import does.
     */
    private fun clauses(clauses: List<Clause>, scope: Scope, foreach: Boolean = false, kept: Map<String, Kind> = emptyMap()): Map<String, Kind> {
        var current = scope
        var returned = emptyMap<String, Kind>()
        for (clause in clauses) {
            when (clause) {
                is Clause.Match -> {
                    clause.patterns.forEach { matched(it, current) }
                    clause.where?.let { expression(it, current) }
                }
                is Clause.Unwind -> {
                    expression(clause.list, current)
                    current[clause.variable] = Kind.ANY
                }
                is Clause.With -> current = projected(clause.projection, current, clause.where).also { next ->
                    kept.forEach { (name, kind) -> if (next[name] == null) next[name] = kind }
                }
                is Clause.Return -> returned = projected(clause.projection, current, null).kinds
                is Clause.Create -> {
                    val made = mutableSetOf<String>()
                    clause.patterns.forEach { made(it, current, made, merging = false, foreach) }
                }
                is Clause.Merge -> {
                    made(clause.pattern, current, mutableSetOf(), merging = true, foreach)
                    (clause.onCreate + clause.onMatch).forEach { set(it, current) }
                }
                is Clause.Set -> clause.items.forEach { set(it, current) }
                is Clause.Remove -> {}
                is Clause.Delete -> clause.expressions.forEach { expression(it, current) }
                is Clause.Foreach -> {
                    expression(clause.list, current)
                    val inner = current.copy()
                    inner[clause.variable] = Kind.ANY
                    clauses(clause.clauses, inner, foreach = true)
                }
                is Clause.Call -> called(clause, current).forEach { (name, kind) -> current[name] = kind }
            }
        }
        return returned
    }

    /**
     * What [call] returns, of the kinds each of its queries returns, read from the row [scope] holds: only a variable its
     * brackets import, `CALL (a) { … }` or `CALL (*) { … }`, or where it has none its leading `WITH` imports as it is, or
     * with `*`, of the kind it is; any other of the row is a new variable there, as in Neo4j.
     */
    private fun called(call: Clause.Call, scope: Scope): Map<String, Kind> {
        val query = call.query
        val parts = (listOf(query.clauses) + query.unions.map { it.clauses }).map { clauses ->
            val imported = imports(call, clauses, scope.kinds.keys)
            val inner = scope.kinds.filterTo(LinkedHashMap()) { (name, _) -> name in imported }
            clauses(clauses, Scope(inner), kept = if (call.imports != null || call.importsAll) inner.toMap() else emptyMap())
        }
        return parts.first().mapValues { (name, kind) -> if (parts.all { it[name] == kind }) kind else Kind.ANY }
    }

    /**
     * The variables of the row, of [names], that the query [clauses] of [call] imports, as Neo4j has it: those its brackets
     * name, `CALL (a, b) { … }`, or each with `CALL (*) { … }`; with no brackets, those its leading `WITH` names as they are,
     * or each with `WITH *`; none where it has neither, `CALL () { … }` or `CALL { MATCH … }`. The compiler reads a call's
     * scope from it too, so the two agree. Refuses a bracketed name not of the row, and, as Neo4j does (its 42I28), a leading
     * `WITH` that names a variable of the row, or `*`, with anything more: such a `WITH` imports, and one that names none is
     * any `WITH`, `CALL { WITH 1 AS one WHERE … }`.
     */
    fun imports(call: Clause.Call, clauses: List<Clause>, names: Collection<String>): Set<String> {
        val all = names.toSet()
        if (call.importsAll) return all
        call.imports?.let { imports ->
            imports.firstOrNull { it !in all }?.let { throw DrivineException("$it is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.") }
            return imports.toSet()
        }
        val with = clauses.firstOrNull() as? Clause.With ?: return emptySet()
        val leading = with.projection
        if (!leading.star && leading.items.none { (it.expression as? Expression.Variable)?.name in all }) return emptySet()
        val plain = leading.items.all { (it.expression as? Expression.Variable)?.name == it.name }
        if (!plain || with.where != null || leading.distinct || leading.orderBy.isNotEmpty() || leading.skip != null || leading.limit != null) {
            throw DrivineException(
                "The importing WITH of a CALL { … } names variables of the row alone, as in Cypher: no alias, expression, DISTINCT, WHERE, ORDER BY, SKIP " +
                    "or LIMIT. Import them with CALL (a, b) { … }, and work on them in a WITH after."
            )
        }
        return if (leading.star) all else all intersect leading.items.map { it.name }.toSet()
    }

    /**
     * The scope after a `WITH` or a `RETURN` of [projection] over [scope]: each item of the kind its expression is, and with
     * `*` each variable in scope. Its `WHERE`, [where], and its order read the items, and a variable before it as of any kind.
     */
    private fun projected(projection: Projection, scope: Scope, where: Expression?): Scope {
        projection.items.forEach { expression(it.expression, scope) }
        val next = if (projection.star) scope.copy() else Scope()
        projection.items.forEach { next[it.name] = kindOf(it.expression, scope) }
        val reading = Scope(scope.kinds.mapValuesTo(LinkedHashMap()) { Kind.ANY }.apply { putAll(next.kinds) })
        where?.let { expression(it, reading) }
        projection.orderBy.forEach { expression(it.expression, reading) }
        projection.skip?.let { expression(it, scope) }
        projection.limit?.let { expression(it, scope) }
        return next
    }

    /** The kind of [expression], where the statement fixes it: see [VariableKinds]. */
    private fun kindOf(expression: Expression, scope: Scope): Kind = when (expression) {
        is Expression.Variable -> scope[expression.name] ?: Kind.ANY
        // A value's kinds are named as Value.Known names them.
        is Expression.Literal -> Value.Known.of(expression.value)?.let { Kind.valueOf(it.name) } ?: Kind.ANY
        is Expression.ListLiteral -> Kind.LIST
        is Expression.MapLiteral -> Kind.MAP
        else -> Kind.ANY
    }

    /**
     * A pattern a `MATCH` finds, over [scope]: what it names, bound as it names it, or used as it is bound. Its path
     * variable is new, but in a pattern within an expression, [within], where it is the pattern's own.
     */
    private fun matched(pattern: Pattern, scope: Scope, within: Boolean = false) {
        // In the order written, so the first of two kinds is the one the variable is bound as.
        pattern.nodes.forEachIndexed { i, node ->
            node.properties?.let { expression(it, scope) }
            node.variable?.let { use(it, Kind.NODE, scope) }
            val relationship = pattern.relationships.getOrNull(i) ?: return@forEachIndexed
            relationship.properties?.let { expression(it, scope) }
            relationship.group?.let { group -> grouped(group, scope) }
            relationship.variable?.let { use(it, if (relationship.range != null) Kind.RELATIONSHIPS else Kind.RELATIONSHIP, scope) }
        }
        pattern.variable?.let { variable ->
            if (!within && scope[variable] != null) throw DrivineException("$variable is bound already, so a pattern binds no path to it, as in Neo4j.")
            scope[variable] = Kind.PATH
        }
    }

    /** A quantified path pattern's [group]: what it names is a list outside it, of whatever it matched. */
    private fun grouped(group: RelationshipPattern.Group, scope: Scope) {
        val names = group.pattern.nodes.mapNotNull { it.variable } + group.pattern.relationships.mapNotNull { it.variable }
        names.forEach { if (scope[it] == null) scope[it] = Kind.ANY }
    }

    /** [variable] where a pattern binds it as [kind]: bound so, or used so where it is bound already as that kind. */
    private fun use(variable: String, kind: Kind, scope: Scope) {
        val bound = scope[variable] ?: return scope.set(variable, kind)
        if (!fits(bound, kind)) throw conflict(variable, bound, kind)
    }

    /** Whether a variable bound as [bound] may be used where a pattern takes [kind]. */
    private fun fits(bound: Kind, kind: Kind) = bound == kind || bound == Kind.ANY || (kind == Kind.RELATIONSHIPS && bound == Kind.LIST)

    private fun conflict(variable: String, bound: Kind, used: Kind) =
        DrivineException("$variable is ${bound.noun}, and is used as ${used.noun}: a variable is of one kind, as in Neo4j.")

    /**
     * A pattern a `CREATE` or a `MERGE` makes, over [scope]: each node of it new, or one bound already that it joins
     * up, and each relationship new: a node a pattern of the same `CREATE` made before it is bound already. [made] holds
     * the relationships the clause made before. A relationship bound before the clause, as one, is left to the compiler,
     * which refuses it as not compiled yet, as the spec has it. Within a `FOREACH`, [foreach], Neo4j takes a variable bound
     * already in each of these places, and only one of another kind is refused.
     */
    private fun made(pattern: Pattern, scope: Scope, made: MutableSet<String>, merging: Boolean, foreach: Boolean) {
        val clause = if (merging) "MERGE" else "CREATE"
        pattern.nodes.forEach { node ->
            node.properties?.let { expression(it, scope) }
            val variable = node.variable ?: return@forEach
            val bound = scope[variable]
            if (bound == null) {
                scope[variable] = Kind.NODE
                return@forEach
            }
            if (!foreach && (node.labels.isNotEmpty() || node.properties != null)) {
                throw DrivineException("$variable is bound already, so a CREATE or MERGE makes no node of it with labels or properties.")
            }
            if (pattern.relationships.isEmpty()) {
                if (!foreach) throw DrivineException("$variable is bound already, so a $clause makes no node of it, as in Neo4j.")
                return@forEach
            }
            if (!fits(bound, Kind.NODE)) throw conflict(variable, bound, Kind.NODE)
        }
        pattern.relationships.forEach { relationship ->
            relationship.properties?.let { expression(it, scope) }
            val variable = relationship.variable ?: return@forEach
            if (foreach) return@forEach scope.set(variable, scope[variable] ?: Kind.RELATIONSHIP)
            val bound = scope[variable]
            if (bound != null && (bound != Kind.RELATIONSHIP || variable in made)) {
                throw DrivineException("$variable is bound already, so a $clause makes no relationship of it, as in Neo4j.")
            }
            scope[variable] = Kind.RELATIONSHIP
            made += variable
        }
        pattern.variable?.let { variable -> if (scope[variable] == null) scope[variable] = Kind.PATH }
    }

    private fun set(item: SetItem, scope: Scope) {
        when (item) {
            is SetItem.Property -> expression(item.value, scope)
            is SetItem.Replace -> expression(item.value, scope)
            is SetItem.Add -> expression(item.value, scope)
            is SetItem.Labels -> {}
        }
    }

    /**
     * The patterns within [expression], each over [scope] and what it names only within it, and the functions of a
     * variable whose kind they do not take.
     */
    private fun expression(expression: Expression, scope: Scope) {
        when (expression) {
            is Expression.Literal, is Expression.Parameter, is Expression.Variable, Expression.CountStar -> {}
            is Expression.Property -> expression(expression.subject, scope)
            is Expression.HasLabels -> expression(expression.subject, scope)
            is Expression.ListLiteral -> expression.items.forEach { expression(it, scope) }
            is Expression.MapLiteral -> expression.entries.forEach { expression(it.second, scope) }
            is Expression.MapProjection -> expression.elements.forEach { if (it is Expression.MapProjection.Element.Entry) expression(it.value, scope) }
            is Expression.Binary -> {
                expression(expression.left, scope)
                expression(expression.right, scope)
            }
            is Expression.Unary -> expression(expression.operand, scope)
            is Expression.Index -> {
                expression(expression.subject, scope)
                expression(expression.index, scope)
            }
            is Expression.Slice -> listOfNotNull(expression.subject, expression.from, expression.to).forEach { expression(it, scope) }
            is Expression.Function -> {
                expression.arguments.forEach { expression(it, scope) }
                function(expression, scope)
            }
            is Expression.Case -> {
                listOfNotNull(expression.subject, expression.otherwise).forEach { expression(it, scope) }
                expression.alternatives.forEach { (key, value) ->
                    expression(key, scope)
                    expression(value, scope)
                }
            }
            is Expression.ListComprehension -> {
                expression(expression.list, scope)
                val inner = scope.copy().also { it[expression.variable] = Kind.ANY }
                listOfNotNull(expression.where, expression.projection).forEach { expression(it, inner) }
            }
            is Expression.Quantifier -> {
                expression(expression.list, scope)
                val inner = scope.copy().also { it[expression.variable] = Kind.ANY }
                expression.where?.let { expression(it, inner) }
            }
            is Expression.Reduce -> {
                expression(expression.initial, scope)
                expression(expression.list, scope)
                val inner = scope.copy().also {
                    it[expression.accumulator] = Kind.ANY
                    it[expression.variable] = Kind.ANY
                }
                expression(expression.expression, inner)
            }
            is Expression.PatternComprehension -> {
                val inner = scope.copy()
                matched(expression.pattern, inner, within = true)
                expression.where?.let { expression(it, inner) }
                expression(expression.projection, inner)
            }
            is Expression.PatternPredicate -> matched(expression.pattern, scope.copy(), within = true)
            is Expression.Exists -> {
                val inner = scope.copy()
                expression.patterns.forEach { matched(it, inner, within = true) }
                expression.where?.let { expression(it, inner) }
            }
            is Expression.Subquery -> query(expression.query, scope)
        }
    }

    /** A function of a variable whose kind it does not take, as Neo4j refuses it: `size()` of a path. */
    private fun function(function: Expression.Function, scope: Scope) {
        val argument = (function.arguments.singleOrNull() as? Expression.Variable)?.let { scope[it.name] } ?: return
        if (function.name.equals("size", ignoreCase = true) && argument == Kind.PATH) {
            throw DrivineException("size() takes a list or a text, not a path, as in Neo4j: length() takes a path.")
        }
    }
}
