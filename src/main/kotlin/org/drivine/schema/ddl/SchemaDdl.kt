package org.drivine.schema.ddl

import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaVersionStore
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.ddl.SchemaDdlResult.AwaitIndexes
import org.drivine.schema.ddl.SchemaDdlResult.Create
import org.drivine.schema.ddl.SchemaDdlResult.Drop
import org.drivine.schema.ddl.SchemaDdlResult.NotDdl
import org.drivine.schema.ddl.SchemaDdlResult.Refused
import org.drivine.schema.ddl.SchemaDdlResult.Reserved
import org.drivine.store.StoreIdentityResolver

/**
 * Reads Neo4j's schema DDL as Drivine's schema specs: a pure function of the text, which touches no store.
 *
 * Neo4j's schema commands are not Cypher queries (openCypher's grammar has no rules for them), so they are read
 * here, by hand, and not by the Cypher reader. A statement is taken for DDL only where it opens with one of these
 * sequences of keywords, so that Cypher that merely uses the words, such as `CREATE index = (a)-[:R]->(b)` or a
 * variable named `index`, reads as Cypher still ([SchemaDdlResult.NotDdl]):
 *
 * - `CREATE [RANGE | BTREE | TEXT | FULLTEXT | VECTOR | POINT | LOOKUP] INDEX`, not followed by `=`;
 * - `CREATE CONSTRAINT`, not followed by `=`;
 * - `DROP INDEX` and `DROP CONSTRAINT`;
 * - `CALL db.awaitIndexes` and `CALL db.awaitIndex`.
 *
 * What it reads:
 *
 * | DDL | Spec |
 * |---|---|
 * | `CREATE [RANGE] INDEX [name] [IF NOT EXISTS] FOR (n:L) ON (n.a, n.b)`, or Neo4j 4's `ON :L(a, b)` | [RangeIndexSpec] |
 * | `CREATE CONSTRAINT [name] [IF NOT EXISTS] FOR (n:L) REQUIRE n.p IS [NODE] UNIQUE`, `REQUIRE (n.a, n.b) IS UNIQUE`, or Neo4j 4's `ON (n:L) ASSERT n.p IS UNIQUE` | [UniquenessConstraintSpec] |
 * | `CREATE FULLTEXT INDEX … FOR (n:L) ON EACH [n.a, …] [OPTIONS {indexConfig: {`fulltext.analyzer`: …}}]` | [FullTextIndexSpec] |
 * | `CREATE VECTOR INDEX … FOR (n:L) ON n.p OPTIONS {indexConfig: {`vector.dimensions`: …}}` | [VectorIndexSpec] |
 * | `CREATE TEXT INDEX … FOR (n:L) ON (n.p)` | [TextIndexSpec], which only Neo4j makes |
 * | `DROP INDEX name [IF EXISTS]`, `DROP CONSTRAINT name [IF EXISTS]` | [SchemaDdlResult.Drop] |
 * | `CALL db.awaitIndexes(…)`, `CALL db.awaitIndex(…)` | [SchemaDdlResult.AwaitIndexes] |
 *
 * A name is a plain identifier or one in backticks, a doubled backtick in it standing for one. Keywords are read in
 * any case. A statement may end with a `;`.
 *
 * What a SQL store cannot honour is refused by name ([SchemaDdlResult.Refused]): POINT and LOOKUP indexes,
 * relationship indexes and constraints, an index or constraint over several labels, existence (`IS NOT NULL`), key
 * (`IS NODE KEY`) and type (`IS :: TYPE`) constraints, a vector index over several properties or `WITH` properties
 * to filter by, and one with no `vector.dimensions`; Neo4j 4's `DROP … ON` by label and properties; a name or `OPTIONS`
 * given as a parameter; and any schema for the labels of Drivine's own nodes but their own uniqueness constraints, which
 * read as [SchemaDdlResult.Reserved].
 */
object SchemaDdl {

    /** What [text] is: schema DDL as a spec, a refusal, or not DDL. */
    @JvmStatic
    fun translate(text: String): SchemaDdlResult {
        val reader = Reader(text)
        return try {
            when {
                reader.word(0, "CREATE") -> reader.create()
                reader.word(0, "DROP") && (reader.word(1, "INDEX") || reader.word(1, "CONSTRAINT")) -> reader.drop()
                reader.word(0, "CALL") -> reader.call()
                else -> NotDdl
            }
        } catch (refusal: Refusal) {
            Refused(refusal.message)
        }
    }

    /** Whether [spec] is the uniqueness constraint of one of Drivine's own nodes, by any name. */
    internal fun reserved(spec: SchemaItemSpec): Boolean =
        spec is UniquenessConstraintSpec && RESERVED[spec.label]?.properties == spec.properties

    /** The uniqueness constraints of Drivine's own nodes, by label: the schema marker's scope and the store stamp's key. */
    private val RESERVED: Map<String, UniquenessConstraintSpec> = listOf(
        UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY),
        StoreIdentityResolver.KEY_CONSTRAINT,
    ).associateBy { it.label }

    /** The kinds of index a `CREATE … INDEX` names before `INDEX`. */
    private val INDEX_KINDS = setOf("RANGE", "BTREE", "TEXT", "FULLTEXT", "VECTOR", "POINT", "LOOKUP")

    private class Refusal(override val message: String) : RuntimeException(message, null, false, false)

    private enum class Type { WORD, NAME, STRING, NUMBER, PARAMETER, SYMBOL, END, BAD }

    /** A token: a [WORD][Type.WORD] is a bare identifier or keyword, a [NAME][Type.NAME] one in backticks, [text] what it reads as. */
    private class Token(val type: Type, val text: String, val value: Any? = null) {
        override fun toString(): String = when (type) {
            Type.END -> "the end"
            Type.NAME -> "`$text`"
            Type.STRING -> "'$text'"
            Type.PARAMETER -> "\$$text"
            else -> "'$text'"
        }
    }

    /** Reads [text] into tokens as they are asked for, so that a statement that is not DDL is read no further than its first few. */
    private class Lexer(private val text: String) {
        private var at = 0

        fun next(): Token {
            skipSpace()
            if (at >= text.length) return Token(Type.END, "")
            val c = text[at]
            return when {
                c == '`' -> quoted()
                c == '\'' || c == '"' -> string(c)
                c.isDigit() -> number()
                c.isLetter() || c == '_' -> Token(Type.WORD, word())
                c == '$' -> parameter()
                c == ':' && text.startsWith("::", at) -> { at += 2; Token(Type.SYMBOL, "::") }
                else -> { at++; Token(Type.SYMBOL, c.toString()) }
            }
        }

        private fun skipSpace() {
            while (at < text.length) {
                when {
                    text[at].isWhitespace() -> at++
                    text.startsWith("//", at) -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
                    text.startsWith("/*", at) -> at = text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
                    else -> return
                }
            }
        }

        private fun word(): String {
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            return text.substring(start, at)
        }

        /** A name in backticks, a doubled backtick in it standing for one. */
        private fun quoted(): Token {
            val name = StringBuilder()
            at++
            while (at < text.length) {
                val c = text[at++]
                if (c != '`') {
                    name.append(c)
                } else if (at < text.length && text[at] == '`') {
                    name.append('`')
                    at++
                } else {
                    return Token(Type.NAME, name.toString())
                }
            }
            return Token(Type.BAD, "A name in backticks has no closing backtick.")
        }

        private fun string(quote: Char): Token {
            val value = StringBuilder()
            at++
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == quote -> return Token(Type.STRING, value.toString())
                    c == '\\' && at < text.length -> {
                        val escaped = text[at++]
                        when (escaped) {
                            'n' -> value.append('\n')
                            't' -> value.append('\t')
                            'r' -> value.append('\r')
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000C')
                            'u' -> if (at + 4 <= text.length && text.substring(at, at + 4).all { it.digitToIntOrNull(16) != null }) {
                                value.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            } else {
                                value.append('u')
                            }
                            else -> value.append(escaped)
                        }
                    }
                    else -> value.append(c)
                }
            }
            return Token(Type.BAD, "A string has no closing quote.")
        }

        private fun number(): Token {
            val start = at
            while (at < text.length && text[at].isDigit()) at++
            var fraction = false
            if (at + 1 < text.length && text[at] == '.' && text[at + 1].isDigit()) {
                fraction = true
                at++
                while (at < text.length && text[at].isDigit()) at++
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                val mark = at
                at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                if (at < text.length && text[at].isDigit()) {
                    fraction = true
                    while (at < text.length && text[at].isDigit()) at++
                } else {
                    at = mark
                }
            }
            val written = text.substring(start, at)
            val value: Any = if (fraction) written.toDouble() else written.toLongOrNull() ?: written.toDouble()
            return Token(Type.NUMBER, written, value)
        }

        private fun parameter(): Token {
            at++
            val name = when {
                at < text.length && text[at] == '`' -> quoted().text
                else -> word()
            }
            return Token(Type.PARAMETER, name)
        }
    }

    /** Reads one statement's tokens, from its first. */
    private class Reader(text: String) {
        private val lexer = Lexer(text)
        private val tokens = mutableListOf<Token>()
        private var position = 0

        fun peek(ahead: Int = 0): Token {
            while (tokens.size <= position + ahead) {
                val last = tokens.lastOrNull()
                tokens += if (last != null && (last.type == Type.END || last.type == Type.BAD)) last else lexer.next()
            }
            return tokens[position + ahead]
        }

        private fun advance(): Token = peek().also { position++ }

        fun word(ahead: Int, keyword: String): Boolean = peek(ahead).let { it.type == Type.WORD && it.text.equals(keyword, ignoreCase = true) }

        private fun word(keyword: String) = word(0, keyword)

        private fun symbol(ahead: Int, symbol: String): Boolean = peek(ahead).let { it.type == Type.SYMBOL && it.text == symbol }

        private fun symbol(symbol: String) = symbol(0, symbol)

        private fun accept(symbol: String): Boolean = symbol(symbol).also { if (it) advance() }

        private fun fail(expected: String): Nothing {
            val found = peek()
            if (found.type == Type.BAD) throw Refusal("Invalid schema command: ${found.text}")
            throw Refusal("Invalid schema command: expected $expected, but found $found.")
        }

        private fun expectWord(keyword: String) {
            if (!word(keyword)) fail(keyword)
            advance()
        }

        private fun expectSymbol(symbol: String) {
            if (!symbol(symbol)) fail("'$symbol'")
            advance()
        }

        /** A label, a property or a variable: bare, or in backticks. */
        private fun name(what: String): String {
            val token = peek()
            if (token.type != Type.WORD && token.type != Type.NAME) fail(what)
            advance()
            if (token.text.isEmpty()) throw Refusal("A $what is empty: an empty name names nothing.")
            return token.text
        }

        /** The end of the statement, after an optional `;`. */
        private fun end() {
            accept(";")
            if (peek().type != Type.END) fail("the end of the schema command, which runs on its own")
        }

        // ----- CREATE -----

        fun create(): SchemaDdlResult {
            val first = peek(1)
            if (first.type != Type.WORD) return NotDdl
            val kind = first.text.uppercase()
            return when {
                (kind == "INDEX" || kind == "CONSTRAINT") && !symbol(2, "=") -> {
                    position += 2
                    if (kind == "INDEX") index("") else constraint()
                }
                kind in INDEX_KINDS && word(2, "INDEX") && !symbol(3, "=") -> {
                    position += 3
                    index(kind)
                }
                else -> NotDdl
            }
        }

        /** The item's name, when it has one: not the `IF NOT EXISTS`, `FOR (` or `ON :` that follows where it has none. */
        private fun itemName(): String? {
            val token = peek()
            if (token.type == Type.PARAMETER) {
                throw Refusal("A schema item named by a parameter, \$${token.text}, is not created on a SQL store: write its name.")
            }
            if (token.type == Type.WORD) {
                val keyword = token.text.uppercase()
                if (keyword == "IF" && word(1, "NOT")) return null
                if (keyword == "FOR" && symbol(1, "(")) return null
                if (keyword == "ON" && (symbol(1, ":") || symbol(1, "("))) return null
            } else if (token.type != Type.NAME) {
                return null
            }
            advance()
            if (token.text.isBlank()) throw Refusal("A schema item's name is empty: an item is created with a name, or with none.")
            return token.text
        }

        private fun ifNotExists(): Boolean {
            if (!word("IF")) return false
            advance()
            expectWord("NOT")
            expectWord("EXISTS")
            return true
        }

        /** The node pattern an item is for, `(n:L)`: its variable, which may be left out, and its one label. */
        private fun nodePattern(item: String): Pair<String?, String> {
            expectSymbol("(")
            val variable = if (peek().type == Type.WORD || peek().type == Type.NAME) name("variable") else null
            if (symbol(")")) {
                advance()
                if (symbol("-") || symbol("<")) throw relationships(item)
                throw Refusal("$item is made for the nodes of one label, and this pattern names none.")
            }
            expectSymbol(":")
            val label = name("label")
            if (symbol("|") || symbol(":") || symbol("&")) {
                throw Refusal("$item over several labels is not made on a SQL store: declare one for each label.")
            }
            expectSymbol(")")
            if (symbol("-") || symbol("<")) throw relationships(item)
            return variable to label
        }

        private fun relationships(item: String) = Refusal("$item on relationships is not made on a SQL store: Drivine's schema is of nodes.")

        /** A property of the pattern's node, `n.p`. */
        private fun property(variable: String?): String {
            val owner = name("variable")
            if (variable != null && owner != variable) {
                throw Refusal("The property $owner.… is of $owner, and the pattern binds $variable.")
            }
            expectSymbol(".")
            return name("property")
        }

        /** Properties of the pattern's node, between [open] and [close], separated by commas. */
        private fun properties(variable: String?, open: String, close: String): List<String> {
            expectSymbol(open)
            val properties = mutableListOf(property(variable))
            while (accept(",")) properties += property(variable)
            expectSymbol(close)
            return properties
        }

        private fun index(kind: String): SchemaDdlResult {
            when (kind) {
                "POINT" -> throw Refusal("A POINT index is Neo4j's, and is not made on a SQL store.")
                "LOOKUP" -> throw Refusal("A LOOKUP index is Neo4j's, and is not made on a SQL store, which finds a node's labels without one.")
            }
            val item = when (kind) {
                "FULLTEXT" -> "A full-text index"
                "VECTOR" -> "A vector index"
                "TEXT" -> "A TEXT index"
                else -> "An index"
            }
            val name = itemName()
            val ifNotExists = ifNotExists()
            val label: String
            val properties: List<String>
            if (word("FOR")) {
                advance()
                val (variable, of) = nodePattern(item)
                label = of
                expectWord("ON")
                properties = when {
                    kind == "FULLTEXT" -> {
                        expectWord("EACH")
                        properties(variable, "[", "]")
                    }
                    symbol("(") -> properties(variable, "(", ")")
                    else -> listOf(property(variable))
                }
            } else if (word("ON") && symbol(1, ":") && kind != "FULLTEXT" && kind != "VECTOR") {
                // Neo4j 4's CREATE INDEX ON :L(a, b), whose properties are named bare.
                advance()
                advance()
                label = name("label")
                expectSymbol("(")
                val named = mutableListOf(name("property"))
                while (accept(",")) named += name("property")
                expectSymbol(")")
                properties = named
            } else {
                fail("FOR")
            }
            if (kind == "VECTOR" && word("WITH")) {
                throw Refusal("A vector index WITH properties to filter by is not made on a SQL store.")
            }
            val options = options()
            end()
            val spec: SchemaItemSpec = when (kind) {
                "TEXT" -> TextIndexSpec(label, single(item, properties))
                "FULLTEXT" -> FullTextIndexSpec(label, properties, analyzer = analyzer(options))
                "VECTOR" -> vector(label, single(item, properties), options)
                else -> RangeIndexSpec(label, properties)
            }
            return created(spec, name, ifNotExists)
        }

        private fun single(item: String, properties: List<String>): String =
            properties.singleOrNull() ?: throw Refusal("$item is over one property, and this names ${properties.size}.")

        private fun constraint(): SchemaDdlResult {
            val name = itemName()
            val ifNotExists = ifNotExists()
            val neo4j4 = when {
                word("FOR") -> false
                word("ON") -> true
                else -> fail("FOR")
            }
            advance()
            val item = "A constraint"
            val (variable, label) = nodePattern(item)
            expectWord(if (neo4j4) "ASSERT" else "REQUIRE")
            if (word("EXISTS") && symbol(1, "(")) throw existence()
            val properties = if (symbol("(")) properties(variable, "(", ")") else listOf(property(variable))
            expectWord("IS")
            when {
                word("NOT") -> throw existence()
                symbol("::") || word("TYPED") ->
                    throw Refusal("A property type constraint (IS :: TYPE) is not made on a SQL store: Drivine's schema has uniqueness constraints only.")
                word("KEY") -> throw key()
                word("RELATIONSHIP") || word("REL") -> throw relationships(item)
                word("NODE") -> {
                    advance()
                    if (word("KEY")) throw key()
                    if (!word("UNIQUE")) fail("UNIQUE")
                    advance()
                }
                word("UNIQUE") -> advance()
                else -> fail("UNIQUE")
            }
            options()
            end()
            return created(UniquenessConstraintSpec(label, properties), name, ifNotExists)
        }

        private fun existence() =
            Refusal("A property existence constraint (IS NOT NULL) is not made on a SQL store: Drivine's schema has uniqueness constraints only.")

        private fun key() =
            Refusal("A key constraint (IS NODE KEY) is not made on a SQL store: Drivine's schema has uniqueness constraints only.")

        /** [spec], as DDL named it [name]: Drivine's own, or one to create, with the name as its alias where the schema API names it otherwise. */
        private fun created(spec: SchemaItemSpec, name: String?, ifNotExists: Boolean): SchemaDdlResult {
            RESERVED[spec.label]?.let { own ->
                if (SchemaDdl.reserved(spec)) return Reserved(own)
                throw Refusal("${spec.label} is the label of Drivine's own nodes, and no schema is made for it but Drivine's.")
            }
            return Create(spec, name?.takeIf { it != spec.defaultName() }, ifNotExists)
        }

        // ----- OPTIONS -----

        /** The map of `OPTIONS`, when there is one. */
        private fun options(): Map<String, Any?> {
            if (!word("OPTIONS")) return emptyMap()
            advance()
            if (peek().type == Type.PARAMETER) throw Refusal("OPTIONS given as a parameter are not read on a SQL store: write them.")
            @Suppress("UNCHECKED_CAST")
            return map() as Map<String, Any?>
        }

        private fun map(): Map<String, Any?> {
            expectSymbol("{")
            val map = linkedMapOf<String, Any?>()
            if (!symbol("}")) {
                do {
                    val key = peek()
                    if (key.type != Type.WORD && key.type != Type.NAME && key.type != Type.STRING) fail("a key")
                    advance()
                    expectSymbol(":")
                    map[key.text] = value()
                } while (accept(","))
            }
            expectSymbol("}")
            return map
        }

        private fun value(): Any? {
            val token = peek()
            return when {
                token.type == Type.STRING || token.type == Type.NUMBER -> advance().let { it.value ?: it.text }
                symbol("-") && peek(1).type == Type.NUMBER -> {
                    advance()
                    when (val number = advance().value) {
                        is Long -> -number
                        is Double -> -number
                        else -> number
                    }
                }
                word("true") -> advance().let { true }
                word("false") -> advance().let { false }
                word("null") -> advance().let { null }
                symbol("{") -> map()
                symbol("[") -> {
                    advance()
                    val items = mutableListOf<Any?>()
                    if (!symbol("]")) {
                        do items += value() while (accept(","))
                    }
                    expectSymbol("]")
                    items
                }
                token.type == Type.PARAMETER -> throw Refusal("An option given as a parameter, \$${token.text}, is not read on a SQL store: write it.")
                else -> fail("a value")
            }
        }

        private fun config(options: Map<String, Any?>): Map<*, *> = when (val config = options["indexConfig"]) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> config
            else -> throw Refusal("OPTIONS indexConfig is a map.")
        }

        private fun analyzer(options: Map<String, Any?>): String? = when (val analyzer = config(options)["fulltext.analyzer"]) {
            null, is String -> analyzer as String?
            else -> throw Refusal("A full-text index's `fulltext.analyzer` is the name of an analyzer, not $analyzer.")
        }

        private fun vector(label: String, property: String, options: Map<String, Any?>): VectorIndexSpec {
            val config = config(options)
            val dimensions = whole(config, "vector.dimensions")
                ?: throw Refusal("A vector index on a SQL store needs `vector.dimensions` in its OPTIONS indexConfig: nothing else gives the size of its embeddings.")
            val similarity = when (val function = config["vector.similarity_function"]) {
                null -> SimilarityFunction.COSINE
                is String -> SimilarityFunction.entries.firstOrNull { it.name.equals(function, ignoreCase = true) }
                    ?: throw Refusal("A vector index's `vector.similarity_function` is 'cosine' or 'euclidean', not '$function'.")
                else -> throw Refusal("A vector index's `vector.similarity_function` is 'cosine' or 'euclidean', not $function.")
            }
            val quantization = when (val enabled = config["vector.quantization.enabled"]) {
                null, is Boolean -> enabled as Boolean?
                else -> throw Refusal("A vector index's `vector.quantization.enabled` is true or false, not $enabled.")
            }
            return VectorIndexSpec(
                label, property, dimensions, similarity,
                hnswM = whole(config, "vector.hnsw.m"),
                hnswEfConstruction = whole(config, "vector.hnsw.ef_construction"),
                engineOptions = listOfNotNull(quantization?.let { Neo4jVectorOptions(quantizationEnabled = it) }),
            )
        }

        /** The positive whole number [key] holds in [config]; null where it holds none. */
        private fun whole(config: Map<*, *>, key: String): Int? {
            val value = config[key] ?: return null
            val number = (value as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                ?: (value as? Double)?.takeIf { it >= 1 && it <= Int.MAX_VALUE && it == Math.floor(it) }?.toInt()
            return number ?: throw Refusal("A vector index's `$key` is a positive whole number, not $value.")
        }

        // ----- DROP -----

        fun drop(): SchemaDdlResult {
            advance()
            val kind = if (advance().text.equals("INDEX", ignoreCase = true)) Drop.Kind.INDEX else Drop.Kind.CONSTRAINT
            val what = kind.name.lowercase()
            if (word("ON") && (symbol(1, ":") || symbol(1, "("))) {
                throw Refusal("DROP ${kind.name} ON … is Neo4j 4's, and drops by label and properties: drop the $what by its name.")
            }
            val token = peek()
            if (token.type == Type.PARAMETER) {
                val item = if (kind == Drop.Kind.INDEX) "An index" else "A constraint"
                throw Refusal("$item named by a parameter, \$${token.text}, is not dropped on a SQL store: write its name.")
            }
            val name = name("name")
            val ifExists = if (word("IF")) {
                advance()
                expectWord("EXISTS")
                true
            } else {
                false
            }
            end()
            return Drop(kind, name, ifExists)
        }

        // ----- CALL -----

        fun call(): SchemaDdlResult {
            val procedure = peek(3)
            val awaits = peek(1).let { it.type == Type.WORD && it.text == "db" } && symbol(2, ".") &&
                procedure.type == Type.WORD && (procedure.text == "awaitIndexes" || procedure.text == "awaitIndex")
            if (!awaits) return NotDdl
            position += 4
            if (accept("(")) {
                if (!symbol(")")) {
                    do {
                        if (peek().type == Type.PARAMETER) advance() else value()
                    } while (accept(","))
                }
                expectSymbol(")")
            }
            end()
            return AwaitIndexes
        }
    }
}
