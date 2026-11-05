package org.drivine.backend.sql

/**
 * BM25, the score Lucene gives a text for a query, and so Neo4j's full-text index: each term's count in the text, weighed by
 * how rare the term is among the label's nodes and against the text's length, then summed (drivine4j#604). The SQL stores
 * without a text index of their own score so, so that they rank the texts as Neo4j does: counted alone, a common word
 * outweighed the word a query is about, and a long text won by holding more of everything.
 *
 * Each term's count is [TextQuery]'s, a whole word or phrase matched in the lower-cased text. Its rarity is over every node
 * of the label, and so is the average length.
 */
internal object Bm25 {

    /** How soon a term's count stops adding to its score: Lucene's. */
    const val K1 = 1.2

    /** How much a text's length, against the average, weighs its counts down: Lucene's. */
    const val B = 0.75

    /** How a store writes what [ranked] asks of it. */
    class Dialect(
        /** The columns of a node's row, as the alias's. */
        val columns: (alias: String) -> String,
        /** How often a term occurs in a text, the term bound as a parameter. */
        val count: (text: String, term: String) -> String,
        /** A text's length in characters, 0 when there is none. */
        val length: (text: String) -> String,
        /** The natural logarithm of a number. */
        val ln: (String) -> String,
        /** A number as floating point. */
        val real: (String) -> String,
    )

    /**
     * The nodes whose text meets [query], each with its BM25 score against the best of them, so the best scores one: a
     * query of [Dialect.columns] and `score`. [texts] is a query of `nid` and `text` for every node of the label, and
     * [nodes] the table of the rows they are joined back to.
     *
     * The label is read once (drivine4j#604). Each node's counts and length are worked out as it is read, and the label's
     * count of nodes, average length and count of the nodes that hold each term are window aggregates over those same
     * rows, so they cost little beyond the counting. They are floating point, so the logarithm is a float's: Postgres's
     * of a `numeric`, as an average and `0.5` beside a count would make them, cost more than the counting. Only then are the nodes that lack a required term or hold an excluded
     * one left out, since they still count towards the label's figures; and only the nodes left are joined back to their
     * rows, so the window holds no more than a few numbers a node.
     */
    fun ranked(texts: String, nodes: String, query: TextQuery, sql: Dialect): String {
        val tf = query.scored.indices.map { "tf${it + 1}" }
        val df = query.scored.indices.map { "df${it + 1}" }
        val required = query.required.indices.map { "r${it + 1}" }
        val excluded = query.excluded.indices.map { "x${it + 1}" }
        val counts = query.scored.zip(tf) + query.required.zip(required) + query.excluded.zip(excluded)
        val counted = Query(
            (listOf("s.nid", "${sql.length("s.text")} AS len") + counts.map { (term, name) -> "${sql.count("s.text", term)} AS $name" }).joinToString(", "),
            Derived(texts, "s"),
        )
        val weighed = Query(
            (listOf("h.*", "${sql.real("COUNT(*) OVER ()")} AS n", "${sql.real("AVG(h.len) OVER ()")} AS avglen") +
                tf.zip(df).map { (t, d) -> "SUM(CASE WHEN h.$t > 0 THEN 1 ELSE 0 END) OVER () AS $d" }).joinToString(", "),
            Derived(counted.text(), "h"),
        )
        val score = score(tf.zip(df).map { (t, d) -> "w.$t" to "w.$d" }, "w.n", "w.len", "NULLIF(w.avglen, 0)", sql.ln)
        val scored = Query(
            "w.nid, ${sql.real(score)} AS score",
            Derived(weighed.text(), "w"),
            // A node that holds no scoring term scores 0: it is left out before its score is worked out, which most are.
            listOf(tf.joinToString(" OR ", "(", ")") { "w.$it > 0" }) + required.map { "w.$it > 0" } + excluded.map { "w.$it = 0" },
        )
        return Query(
            "${sql.columns("c")}, b.score / MAX(b.score) OVER () AS score",
            OnceReached(scored.text(), "b", nodes, "c"),
            listOf("b.score > 0"),
        ).text()
    }

    /**
     * The score of a text whose [length] is against the [average] over the label's [nodes]: for each term, its count in the
     * text and the count of the label's nodes that hold it, as SQL. [ln] is the store's natural logarithm. Lucene's weight
     * of a term's rarity, `ln(1 + (N - df + 0.5) / (df + 0.5))`, is never below 0, so a term every node holds adds nothing
     * but never takes away.
     */
    fun score(terms: List<Pair<String, String>>, nodes: String, length: String, average: String, ln: (String) -> String): String =
        terms.joinToString("\n    + ") { (count, holding) ->
            "${ln("1 + ($nodes - $holding + 0.5) / ($holding + 0.5)")} * $count * ${K1 + 1} / ($count + $K1 * (${1 - B} + $B * $length / $average))"
        }
}
