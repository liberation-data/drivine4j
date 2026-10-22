package org.drivine.cypher

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.TerminalNode
import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.grammar.CypherLexer
import org.drivine.cypher.grammar.CypherParser
import org.drivine.cypher.grammar.CypherParser.*

/**
 * Reads a Cypher statement into a [Query]: parsed with openCypher's grammar, then walked into the
 * syntax tree the compiler works from, which says what the statement means and no more of how it was
 * written. A statement that is not Cypher is refused with where it went wrong. A statement that is
 * Cypher the compiler does not take (a `UNION`, a procedure call, a write, before they are built) is
 * refused by name.
 */
object CypherReader {

    /** The statement [cypher], read. */
    fun read(cypher: String): Query {
        val refusing = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?,
            ) = throw DrivineException("The statement is not Cypher, at line $line, column ${charPositionInLine + 1}: $msg")
        }
        val lexer = CypherLexer(CharStreams.fromString(cypher)).apply { removeErrorListeners(); addErrorListener(refusing) }
        val parser = CypherParser(CommonTokenStream(lexer)).apply { removeErrorListeners(); addErrorListener(refusing) }
        return query(parser.oC_Cypher().oC_Statement().oC_Query())
    }

    private fun query(context: OC_QueryContext): Query {
        val regular = context.oC_RegularQuery() ?: refuse("A procedure call (CALL)")
        if (regular.oC_Union().isNotEmpty()) refuse("UNION")
        val single = regular.oC_SingleQuery()
        val clauses = mutableListOf<Clause>()
        single.oC_MultiPartQuery()?.let { multi ->
            // Its children in order: the reading clauses and updates of each part, then the part's WITH.
            for (child in multi.children) {
                when (child) {
                    is OC_ReadingClauseContext -> clauses += reading(child)
                    is OC_UpdatingClauseContext -> refuse("A write (${keyword(child)})")
                    is OC_WithContext -> clauses += Clause.With(projection(child.oC_ProjectionBody()), child.oC_Where()?.let { expression(it.oC_Expression()) })
                }
            }
        }
        val last = single.oC_SinglePartQuery() ?: single.oC_MultiPartQuery().oC_SinglePartQuery()
        if (last.oC_UpdatingClause().isNotEmpty()) refuse("A write (${keyword(last.oC_UpdatingClause(0))})")
        last.oC_ReadingClause().forEach { clauses += reading(it) }
        clauses += Clause.Return(projection(last.oC_Return().oC_ProjectionBody()))
        return Query(clauses)
    }

    private fun reading(context: OC_ReadingClauseContext): Clause = when {
        context.oC_Match() != null -> context.oC_Match().let { match ->
            Clause.Match(match.OPTIONAL() != null, patterns(match.oC_Pattern()), match.oC_Where()?.let { expression(it.oC_Expression()) })
        }
        context.oC_Unwind() != null -> context.oC_Unwind().let { Clause.Unwind(expression(it.oC_Expression()), name(it.oC_Variable())) }
        else -> refuse("A procedure call (CALL)")
    }

    private fun projection(context: OC_ProjectionBodyContext): Projection {
        val items = context.oC_ProjectionItems()
        return Projection(
            distinct = context.DISTINCT() != null,
            star = items.children.firstOrNull()?.text == "*",
            items = items.oC_ProjectionItem().map { item ->
                Projection.Item(expression(item.oC_Expression()), item.oC_Variable()?.let(::name) ?: item.oC_Expression().text)
            },
            orderBy = context.oC_Order()?.oC_SortItem()?.map { sort ->
                Projection.SortItem(expression(sort.oC_Expression()), sort.DESC() != null || sort.DESCENDING() != null)
            }.orEmpty(),
            skip = context.oC_Skip()?.let { expression(it.oC_Expression()) },
            limit = context.oC_Limit()?.let { expression(it.oC_Expression()) },
        )
    }

    // ----- Patterns -----

    private fun patterns(context: OC_PatternContext): List<Pattern> = context.oC_PatternPart().map { part ->
        pattern(part.oC_AnonymousPatternPart().oC_PatternElement(), part.oC_Variable()?.let(::name))
    }

    private fun pattern(context: OC_PatternElementContext, variable: String?): Pattern {
        // A pattern in parentheses is the pattern.
        context.oC_PatternElement()?.let { return pattern(it, variable) }
        val nodes = mutableListOf(node(context.oC_NodePattern()))
        val relationships = mutableListOf<RelationshipPattern>()
        context.oC_PatternElementChain().forEach { chain ->
            relationships += relationship(chain.oC_RelationshipPattern())
            nodes += node(chain.oC_NodePattern())
        }
        return Pattern(variable, nodes, relationships)
    }

    private fun pattern(context: OC_RelationshipsPatternContext, variable: String?): Pattern {
        val nodes = mutableListOf(node(context.oC_NodePattern()))
        val relationships = mutableListOf<RelationshipPattern>()
        context.oC_PatternElementChain().forEach { chain ->
            relationships += relationship(chain.oC_RelationshipPattern())
            nodes += node(chain.oC_NodePattern())
        }
        return Pattern(variable, nodes, relationships)
    }

    private fun node(context: OC_NodePatternContext) = NodePattern(
        context.oC_Variable()?.let(::name),
        context.oC_NodeLabels()?.oC_NodeLabel()?.map { name(it.oC_LabelName().oC_SchemaName()) }.orEmpty(),
        context.oC_Properties()?.let(::properties),
    )

    private fun relationship(context: OC_RelationshipPatternContext): RelationshipPattern {
        val left = context.oC_LeftArrowHead() != null
        val right = context.oC_RightArrowHead() != null
        val detail = context.oC_RelationshipDetail()
        val direction = when {
            right && !left -> RelationshipPattern.Direction.OUTGOING
            left && !right -> RelationshipPattern.Direction.INCOMING
            else -> RelationshipPattern.Direction.EITHER
        }
        return RelationshipPattern(
            detail?.oC_Variable()?.let(::name),
            detail?.oC_RelationshipTypes()?.oC_RelTypeName()?.map { name(it.oC_SchemaName()) }.orEmpty(),
            direction,
            detail?.oC_RangeLiteral()?.let(::range),
            detail?.oC_Properties()?.let(::properties),
        )
    }

    /** `*`, `*2`, `*1..3`, `*..3`, `*2..`: a bare number is both bounds. */
    private fun range(context: OC_RangeLiteralContext): RelationshipPattern.Range {
        val bounds = context.oC_IntegerLiteral().map { integer(it).toInt() }
        val dots = context.children.any { it.text == ".." }
        return when {
            !dots && bounds.size == 1 -> RelationshipPattern.Range(bounds[0], bounds[0])
            !dots -> RelationshipPattern.Range(null, null)
            bounds.size == 2 -> RelationshipPattern.Range(bounds[0], bounds[1])
            bounds.isEmpty() -> RelationshipPattern.Range(null, null)
            // One bound and the dots: before them it is the least, after them the most.
            context.children.indexOfFirst { it.text == ".." } < context.children.indexOf(context.oC_IntegerLiteral(0)) ->
                RelationshipPattern.Range(null, bounds[0])
            else -> RelationshipPattern.Range(bounds[0], null)
        }
    }

    private fun properties(context: OC_PropertiesContext): Expression =
        context.oC_MapLiteral()?.let(::map) ?: parameter(context.oC_Parameter())

    // ----- Expressions -----

    private fun expression(context: OC_ExpressionContext): Expression = or(context.oC_OrExpression())

    private fun or(context: OC_OrExpressionContext): Expression =
        context.oC_XorExpression().map(::xor).reduce { a, b -> Expression.Binary(Expression.Binary.Operator.OR, a, b) }

    private fun xor(context: OC_XorExpressionContext): Expression =
        context.oC_AndExpression().map(::and).reduce { a, b -> Expression.Binary(Expression.Binary.Operator.XOR, a, b) }

    private fun and(context: OC_AndExpressionContext): Expression =
        context.oC_NotExpression().map(::not).reduce { a, b -> Expression.Binary(Expression.Binary.Operator.AND, a, b) }

    private fun not(context: OC_NotExpressionContext): Expression =
        context.NOT().fold(comparison(context.oC_ComparisonExpression())) { operand, _ -> Expression.Unary(Expression.Unary.Operator.NOT, operand) }

    /** `a < b <= c` is `a < b AND b <= c`, as Cypher reads a chain of comparisons. */
    private fun comparison(context: OC_ComparisonExpressionContext): Expression {
        var left = predicate(context.oC_StringListNullPredicateExpression())
        var result: Expression? = null
        for (partial in context.oC_PartialComparisonExpression()) {
            val right = predicate(partial.oC_StringListNullPredicateExpression())
            val operator = when (partial.children.first().text) {
                "=" -> Expression.Binary.Operator.EQUALS
                "<>" -> Expression.Binary.Operator.NOT_EQUALS
                "<" -> Expression.Binary.Operator.LESS_THAN
                ">" -> Expression.Binary.Operator.GREATER_THAN
                "<=" -> Expression.Binary.Operator.LESS_THAN_OR_EQUAL
                else -> Expression.Binary.Operator.GREATER_THAN_OR_EQUAL
            }
            val one = Expression.Binary(operator, left, right)
            result = result?.let { Expression.Binary(Expression.Binary.Operator.AND, it, one) } ?: one
            left = right
        }
        return result ?: left
    }

    private fun predicate(context: OC_StringListNullPredicateExpressionContext): Expression {
        var result = additive(context.oC_AddOrSubtractExpression())
        for (child in context.children.drop(1)) {
            result = when (child) {
                is OC_StringPredicateExpressionContext -> {
                    val operator = when {
                        child.STARTS() != null -> Expression.Binary.Operator.STARTS_WITH
                        child.ENDS() != null -> Expression.Binary.Operator.ENDS_WITH
                        else -> Expression.Binary.Operator.CONTAINS
                    }
                    Expression.Binary(operator, result, additive(child.oC_AddOrSubtractExpression()))
                }
                is OC_ListPredicateExpressionContext -> Expression.Binary(Expression.Binary.Operator.IN, result, additive(child.oC_AddOrSubtractExpression()))
                is OC_NullPredicateExpressionContext ->
                    Expression.Unary(if (child.NOT() != null) Expression.Unary.Operator.IS_NOT_NULL else Expression.Unary.Operator.IS_NULL, result)
                else -> result
            }
        }
        return result
    }

    private fun additive(context: OC_AddOrSubtractExpressionContext): Expression {
        val operands = context.oC_MultiplyDivideModuloExpression().map(::multiplicative)
        val operators = context.children.filter { it.text == "+" || it.text == "-" }.map { it.text }
        return operands.drop(1).zip(operators).fold(operands.first()) { left, (right, operator) ->
            Expression.Binary(if (operator == "+") Expression.Binary.Operator.ADD else Expression.Binary.Operator.SUBTRACT, left, right)
        }
    }

    private fun multiplicative(context: OC_MultiplyDivideModuloExpressionContext): Expression {
        val operands = context.oC_PowerOfExpression().map(::power)
        val operators = context.children.filter { it.text == "*" || it.text == "/" || it.text == "%" }.map { it.text }
        return operands.drop(1).zip(operators).fold(operands.first()) { left, (right, operator) ->
            val kind = when (operator) {
                "*" -> Expression.Binary.Operator.MULTIPLY
                "/" -> Expression.Binary.Operator.DIVIDE
                else -> Expression.Binary.Operator.MODULO
            }
            Expression.Binary(kind, left, right)
        }
    }

    private fun power(context: OC_PowerOfExpressionContext): Expression =
        context.oC_UnaryAddOrSubtractExpression().map(::unary).reduce { a, b -> Expression.Binary(Expression.Binary.Operator.POWER, a, b) }

    private fun unary(context: OC_UnaryAddOrSubtractExpressionContext): Expression {
        val operand = nonArithmetic(context.oC_NonArithmeticOperatorExpression())
        return if (context.children.first().text == "-") Expression.Unary(Expression.Unary.Operator.NEGATE, operand) else operand
    }

    /** An atom, then its property lookups and list operators in the order written, then a label test. */
    private fun nonArithmetic(context: OC_NonArithmeticOperatorExpressionContext): Expression {
        var result = atom(context.oC_Atom())
        for (child in context.children.drop(1)) {
            result = when (child) {
                is OC_PropertyLookupContext -> Expression.Property(result, name(child.oC_PropertyKeyName().oC_SchemaName()))
                is OC_ListOperatorExpressionContext -> {
                    val dots = child.children.any { it.text == ".." }
                    if (!dots) {
                        Expression.Index(result, expression(child.oC_Expression(0)))
                    } else {
                        // Which bound each expression is: before the dots, the start; after them, the end.
                        val split = child.children.indexOfFirst { it.text == ".." }
                        val bounds = child.oC_Expression()
                        val from = bounds.firstOrNull { child.children.indexOf(it) < split }?.let(::expression)
                        val to = bounds.firstOrNull { child.children.indexOf(it) > split }?.let(::expression)
                        Expression.Slice(result, from, to)
                    }
                }
                is OC_NodeLabelsContext -> Expression.HasLabels(result, child.oC_NodeLabel().map { name(it.oC_LabelName().oC_SchemaName()) })
                else -> result
            }
        }
        return result
    }

    private fun atom(context: OC_AtomContext): Expression = when {
        context.oC_Literal() != null -> literal(context.oC_Literal())
        context.oC_Parameter() != null -> parameter(context.oC_Parameter())
        context.oC_CaseExpression() != null -> case(context.oC_CaseExpression())
        context.COUNT() != null -> Expression.CountStar
        context.oC_ListComprehension() != null -> context.oC_ListComprehension().let { comprehension ->
            val filter = comprehension.oC_FilterExpression()
            Expression.ListComprehension(
                name(filter.oC_IdInColl().oC_Variable()),
                expression(filter.oC_IdInColl().oC_Expression()),
                filter.oC_Where()?.let { expression(it.oC_Expression()) },
                comprehension.oC_Expression()?.let(::expression),
            )
        }
        context.oC_PatternComprehension() != null -> context.oC_PatternComprehension().let { comprehension ->
            Expression.PatternComprehension(
                pattern(comprehension.oC_RelationshipsPattern(), comprehension.oC_Variable()?.let(::name)),
                comprehension.oC_Where()?.let { expression(it.oC_Expression()) },
                expression(comprehension.oC_Expression()),
            )
        }
        context.oC_Quantifier() != null -> context.oC_Quantifier().let { quantifier ->
            val kind = when {
                quantifier.ALL() != null -> Expression.Quantifier.Kind.ALL
                quantifier.ANY() != null -> Expression.Quantifier.Kind.ANY
                quantifier.NONE() != null -> Expression.Quantifier.Kind.NONE
                else -> Expression.Quantifier.Kind.SINGLE
            }
            val filter = quantifier.oC_FilterExpression()
            Expression.Quantifier(
                kind, name(filter.oC_IdInColl().oC_Variable()), expression(filter.oC_IdInColl().oC_Expression()),
                filter.oC_Where()?.let { expression(it.oC_Expression()) },
            )
        }
        context.oC_PatternPredicate() != null -> Expression.PatternPredicate(pattern(context.oC_PatternPredicate().oC_RelationshipsPattern(), null))
        context.oC_ParenthesizedExpression() != null -> expression(context.oC_ParenthesizedExpression().oC_Expression())
        context.oC_FunctionInvocation() != null -> context.oC_FunctionInvocation().let { function ->
            val namespace = function.oC_FunctionName().oC_Namespace().oC_SymbolicName().joinToString("") { symbolic(it) + "." }
            Expression.Function(
                namespace + symbolic(function.oC_FunctionName().oC_SymbolicName()),
                function.DISTINCT() != null,
                function.oC_Expression().map(::expression),
            )
        }
        context.oC_ExistentialSubquery() != null -> context.oC_ExistentialSubquery().let { exists ->
            if (exists.oC_RegularQuery() != null) refuse("EXISTS { … } over a whole query")
            Expression.Exists(patterns(exists.oC_Pattern()), exists.oC_Where()?.let { expression(it.oC_Expression()) })
        }
        context.oC_MapProjection() != null -> context.oC_MapProjection().let { projection ->
            Expression.MapProjection(
                name(projection.oC_Variable()),
                projection.oC_MapProjectionElement().map { element ->
                    when {
                        element.oC_Expression() != null ->
                            Expression.MapProjection.Element.Entry(name(element.oC_PropertyKeyName().oC_SchemaName()), expression(element.oC_Expression()))
                        element.oC_PropertyKeyName() != null -> Expression.MapProjection.Element.Selected(name(element.oC_PropertyKeyName().oC_SchemaName()))
                        element.oC_Variable() != null -> Expression.MapProjection.Element.Variable(name(element.oC_Variable()))
                        else -> Expression.MapProjection.Element.All
                    }
                },
            )
        }
        else -> Expression.Variable(name(context.oC_Variable()))
    }

    private fun case(context: OC_CaseExpressionContext): Expression {
        val expressions = context.oC_Expression()
        // A subject is an expression before the first WHEN; ELSE's is after the last alternative.
        val firstWhen = context.children.indexOf(context.oC_CaseAlternative(0))
        val subject = expressions.firstOrNull { context.children.indexOf(it) < firstWhen }?.let(::expression)
        val otherwise = expressions.firstOrNull { context.children.indexOf(it) > firstWhen }?.let(::expression)
        return Expression.Case(
            subject,
            context.oC_CaseAlternative().map { expression(it.oC_Expression(0)) to expression(it.oC_Expression(1)) },
            otherwise,
        )
    }

    private fun literal(context: OC_LiteralContext): Expression = when {
        context.oC_BooleanLiteral() != null -> Expression.Literal(context.oC_BooleanLiteral().TRUE() != null)
        context.NULL() != null -> Expression.Literal(null)
        context.oC_NumberLiteral() != null -> context.oC_NumberLiteral().let { number ->
            number.oC_IntegerLiteral()?.let { Expression.Literal(integer(it)) } ?: Expression.Literal(number.oC_DoubleLiteral().text.toDouble())
        }
        context.StringLiteral() != null -> Expression.Literal(string(context.StringLiteral()))
        context.oC_ListLiteral() != null -> Expression.ListLiteral(context.oC_ListLiteral().oC_Expression().map(::expression))
        else -> map(context.oC_MapLiteral())
    }

    private fun map(context: OC_MapLiteralContext) = Expression.MapLiteral(
        context.oC_PropertyKeyName().zip(context.oC_Expression()).map { (key, value) -> name(key.oC_SchemaName()) to expression(value) }
    )

    private fun parameter(context: OC_ParameterContext) =
        Expression.Parameter(context.oC_SymbolicName()?.let(::symbolic) ?: context.DecimalInteger().text)

    // ----- Names and literals -----

    private fun name(context: OC_VariableContext): String = symbolic(context.oC_SymbolicName())

    /** A name, as written or between backticks, where a doubled backtick is one. */
    private fun name(context: OC_SchemaNameContext): String =
        context.oC_SymbolicName()?.let(::symbolic) ?: context.oC_ReservedWord().text

    private fun symbolic(context: OC_SymbolicNameContext): String {
        val text = context.text
        return if (text.startsWith("`")) text.substring(1, text.length - 1).replace("``", "`") else text
    }

    private fun integer(context: OC_IntegerLiteralContext): Long {
        val text = context.text
        return when {
            text.startsWith("0x") -> text.substring(2).toLong(16)
            text.startsWith("0o") -> text.substring(2).toLong(8)
            text.length > 1 && text.startsWith("0") -> text.substring(1).toLong(8)
            else -> text.toLong()
        }
    }

    /** A string literal's value: its quotes taken off and its escapes read. */
    private fun string(node: TerminalNode): String {
        val text = node.text
        val body = text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i == body.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val escaped = body[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u', 'U' -> {
                    val digits = if (escaped == 'u') 4 else 8
                    out.appendCodePoint(body.substring(i + 2, i + 2 + digits).toInt(16))
                    i += digits
                }
                else -> out.append(escaped)
            }
            i += 2
        }
        return out.toString()
    }

    private fun keyword(context: ParserRuleContext): String = context.start.text.uppercase()

    /** Cypher the compiler does not take: refused by name, so the caller is told what, and not that the SQL failed. */
    private fun refuse(construct: String): Nothing =
        throw DrivineException("$construct is Cypher, and does not run on Oracle or Postgres yet.")
}
