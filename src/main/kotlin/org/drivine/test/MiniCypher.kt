package org.drivine.test

import org.drivine.manager.PersistenceManager

/**
 * Runs the Cypher the cross-engine tests use for their fixtures against a graph kept by Oracle: the
 * `CREATE` a test seeds with, the statement another writer changes a node with, and the queries a
 * test reads back what was stored. It is a test's tool and reads only what those statements use:
 *
 * ```
 * CREATE (a:Human {id: 'ada'})-[:KNOWS {since: 2020}]->(:Human {id: 'bob'})
 * MATCH (c:Claim {id: 'c1'}), (h:Human) CREATE (c)-[:MENTIONS]->(h) SET c.text = 'x'
 * MATCH (c:Claim {id: 'c1'}) DETACH DELETE c
 * MERGE (k:Keeper {id: 'kim'}) SET k.name = 'Kim' MERGE (s)-[:KEPT_BY]->(k)
 * MATCH (a)-[r]->(b) WHERE type(r) = $type RETURN toString(a.id) + '->' + b.id
 * ```
 *
 * What it reads, with keywords in upper case as the fixtures mostly write them, though any case is read:
 *
 * - the clauses `MATCH` (with a `WHERE`), `CREATE`, `MERGE` of one pattern, `SET` of properties and
 *   labels, `REMOVE` of a property, `DETACH DELETE` of a node, `UNWIND … AS`, `WITH` of variables
 *   (with a `WHERE`), and `RETURN` of one expression, with an alias and an `ORDER BY` of one key, or
 *   of one `count(…)`;
 * - patterns of nodes with labels and literal or bound properties, and relationships of one type in
 *   either direction, or in none when they are matched;
 * - in a predicate, the comparisons `=`, `<>`, `<`, `<=`, `>` and `>=`, `IN`, `STARTS WITH`,
 *   `IS [NOT] NULL` and `AND`;
 * - in an expression, strings, numbers, `true`, `false`, `null`, parameters, lists, maps, `+`,
 *   a property of a variable as `n.key` or `n[key]`, and the searched `CASE WHEN … THEN … ELSE … END`;
 * - the functions `toString`, `coalesce`, `type`, `labels`, `keys`, `size`, `substring`, `left`, `right`,
 *   `replace`, `properties` and `randomUUID`, and the constructors that wrap one value, such as `datetime`.
 *
 * Anything else is refused, never guessed at: `OR`, a `NOT` other than in `IS NOT NULL`, `ON CREATE SET`,
 * `SKIP`, `LIMIT`, a `RETURN` of several columns, a `DELETE` of a relationship, a function not listed
 * or given a number of arguments it does not take, a `count` anywhere but as the whole of a `RETURN`,
 * a parameter with no value bound, a simple `CASE x WHEN …`, brackets around an expression, and a
 * variable named where none is bound, among them any variable in a pattern's properties, which are
 * read once before any row is.
 *
 * A statement is read whole before any of it is worked out: [MiniCypherLexer] cuts the text into
 * tokens, [MiniCypherParser] makes of them the tree [MiniCypherAst] describes, and
 * [MiniCypherEvaluator] works the tree out over a [MiniCypherGraph]. What the interpreter does not
 * read is refused by the parser, with the place in the text it stands at, before the graph is
 * loaded; the parser's own documentation has the grammar. Only what depends on the graph is
 * refused later: a `MERGE` that has to make something that cannot be made, a `DETACH DELETE` of
 * what is no node, and a property read of a value that has none.
 *
 * Keywords are read in any case, as Cypher reads them. A fixture opens with a pattern or `UNWIND`,
 * after any `//` comments; one that opens with `WITH` is taken for SQL. A temporal constructor gives its argument, so a moment is stored as its
 * ISO text, and compares with another by `<` and `>` and in `ORDER BY`, not by `=`. The stamp is a
 * column of the node, and `keys(n)` and `properties(n)` list it as the graph engines would.
 *
 * A statement is worked out over the whole graph read into memory, and what it changed is written
 * back. A test's graph is a handful of nodes.
 */
internal class MiniCypher(private val pm: PersistenceManager, private val text: String, private val parameters: Map<String, Any?>) {

    /** The rows the statement returns; empty for one that returns nothing. */
    fun run(): List<Any?> {
        val statement = MiniCypherParser(text, parameters.keys).statement()
        val graph = MiniCypherGraph(pm)
        graph.load()
        return MiniCypherEvaluator(graph, parameters, text).run(statement).also { graph.store() }
    }

    companion object {
        /** Whether [statement] is one of a test's Cypher fixtures, and not a statement in the store's own language. */
        fun reads(statement: String): Boolean = FIXTURE.containsMatchIn(statement)

        /**
         * A statement that opens with a pattern, or with `UNWIND`, after any `//` comments: `CREATE INDEX`
         * is the store's own, and so is one that opens with `WITH`, which SQL names a common table
         * expression by. SQL has no `//` comment, so a statement that opens with one is Cypher.
         */
        private val FIXTURE = Regex("^(?:\\s|//[^\\n]*)*(?:(?:MATCH|CREATE|MERGE)\\s*\\(|UNWIND\\s)", RegexOption.IGNORE_CASE)
    }
}
