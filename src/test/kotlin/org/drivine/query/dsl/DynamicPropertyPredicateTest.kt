package org.drivine.query.dsl

import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the dynamic-predicate escape hatch — [property] and [predicate] — proving they
 * render to the same parameterized Cypher a typed accessor would, resolve dotted (`@PropertyBag`)
 * paths with backtick quoting, and escape a backtick in a runtime-supplied key so it can't break out
 * of the quotes.
 */
class DynamicPropertyPredicateTest {

    /** Minimal hand-written fragment DSL (alias `n`), mirroring the codegen shape. */
    private class RecordQueryDsl : NodeReference {
        override val nodeAlias: String = "n"
        val name = StringPropertyReference("n", "name")

        companion object {
            val INSTANCE = RecordQueryDsl()
        }
    }

    private val grammar = org.drivine.query.grammar.Neo4j5Grammar(ApocSortMapsEmitter())

    private fun render(block: GraphQuerySpec<RecordQueryDsl>.() -> Unit): Pair<String?, Map<String, Any?>> {
        val spec = GraphQuerySpec(RecordQueryDsl.INSTANCE)
        spec.block()
        val where = CypherGenerator.buildWhereClause(spec.conditions, null, grammar).whereClause
        val bindings = CypherGenerator.extractBindings(spec.conditions, null)
        return where to bindings
    }

    @Test
    fun `property() on a plain key renders an unquoted path`() {
        val (where, bindings) = render { where { query.property("owner") eq "ada" } }
        assertEquals("n.owner = \$param_n_owner_0", where)
        assertEquals("ada", bindings["param_n_owner_0"])
    }

    @Test
    fun `property() on a dotted PropertyBag key is backtick-quoted, value parameterized`() {
        val (where, bindings) = render { where { query.property("metadata.source") eq "wiki" } }
        assertEquals("n.`metadata.source` = \$param_n_metadata_source_0", where)
        assertEquals("wiki", bindings["param_n_metadata_source_0"])
    }

    @Test
    fun `predicate() covers the string operators the untyped reference does not`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.title", ComparisonOperator.CONTAINS, "graph") }
        }
        assertEquals("n.`metadata.title` CONTAINS \$param_n_metadata_title_0", where)
        assertEquals("graph", bindings["param_n_metadata_title_0"])
    }

    @Test
    fun `predicate() renders IN with a list value bound as one parameter`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.tag", ComparisonOperator.IN, listOf("a", "b")) }
        }
        assertEquals("n.`metadata.tag` IN \$param_n_metadata_tag_0", where)
        assertEquals(listOf("a", "b"), bindings["param_n_metadata_tag_0"])
    }

    @Test
    fun `predicate() renders IS_NULL with no parameter`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.deleted", ComparisonOperator.IS_NULL) }
        }
        assertEquals("n.`metadata.deleted` IS NULL", where)
        assertTrue(bindings.isEmpty(), "IS NULL must bind nothing: $bindings")
    }

    @Test
    fun `a backtick in a runtime key is escaped so it cannot break out of the quotes`() {
        val (where, _) = render { where { query.property("metadata.a`b") eq "x" } }
        // Cypher escapes a backtick inside a quoted identifier by doubling it.
        assertEquals("n.`metadata.a``b` = \$param_n_metadata_a`b_0", where)
    }

    // ----- Phase B operators -----

    @Test
    fun `predicate() renders NOT_IN with a leading NOT`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.tag", ComparisonOperator.NOT_IN, listOf("x", "y")) }
        }
        assertEquals("NOT n.`metadata.tag` IN \$param_n_metadata_tag_0", where)
        assertEquals(listOf("x", "y"), bindings["param_n_metadata_tag_0"])
    }

    @Test
    fun `predicate() renders MATCHES as a regex operator`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.code", ComparisonOperator.MATCHES, "[A-Z]+") }
        }
        assertEquals("n.`metadata.code` =~ \$param_n_metadata_code_0", where)
        assertEquals("[A-Z]+", bindings["param_n_metadata_code_0"])
    }

    @Test
    fun `case-insensitive operators wrap the LHS in toLower and lower-case the bound value`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.source", ComparisonOperator.CONTAINS_IGNORE_CASE, "GRAPH") }
        }
        assertEquals("toLower(n.`metadata.source`) CONTAINS \$param_n_metadata_source_0", where)
        assertEquals("graph", bindings["param_n_metadata_source_0"])

        val (eqWhere, eqBindings) = render {
            where { query.predicate("metadata.source", ComparisonOperator.EQUALS_IGNORE_CASE, "Wiki") }
        }
        assertEquals("toLower(n.`metadata.source`) = \$param_n_metadata_source_0", eqWhere)
        assertEquals("wiki", eqBindings["param_n_metadata_source_0"])
    }

    @Test
    fun `infix sugar renders the same as predicate() for the new operators`() {
        assertEquals(
            "n.name =~ \$param_n_name_0",
            render { where { query.name matches "a.*" } }.first,
        )
        assertEquals(
            "toLower(n.name) CONTAINS \$param_n_name_0",
            render { where { query.name containsIgnoreCase "X" } }.first,
        )
        assertEquals(
            "NOT n.`metadata.tag` IN \$param_n_metadata_tag_0",
            render { where { query.property("metadata.tag") notIn listOf("a") } }.first,
        )
    }

    @Test
    fun `not { } negates a leaf, and preserves parameter indexing after a preceding predicate`() {
        val (where, bindings) = render {
            where {
                query.property("owner") eq "ada"
                not { query.property("metadata.status") eq "archived" }
            }
        }
        // The predicate inside NOT is indexed *after* the preceding one (0 → 1) so bindings align.
        assertEquals(
            "n.owner = \$param_n_owner_0 AND NOT (n.`metadata.status` = \$param_n_metadata_status_1)",
            where,
        )
        assertEquals("ada", bindings["param_n_owner_0"])
        assertEquals("archived", bindings["param_n_metadata_status_1"])
    }

    @Test
    fun `not { anyOf { } } renders a negated OR`() {
        val (where, bindings) = render {
            where {
                not {
                    anyOf {
                        query.property("metadata.state") eq "a"
                        query.property("metadata.state") eq "b"
                    }
                }
            }
        }
        assertEquals(
            "NOT ((n.`metadata.state` = \$param_n_metadata_state_0 OR n.`metadata.state` = \$param_n_metadata_state_1))",
            where,
        )
        assertEquals("a", bindings["param_n_metadata_state_0"])
        assertEquals("b", bindings["param_n_metadata_state_1"])
    }

    @Test
    fun `predicateOn HAS_ELEMENT renders reversed membership (value left, key right)`() {
        val (where, bindings) = render {
            where { query.predicate("tags", ComparisonOperator.HAS_ELEMENT, "kotlin") }
        }
        assertEquals("\$param_n_tags_0 IN n.tags", where)
        assertEquals("kotlin", bindings["param_n_tags_0"])
    }

    @Test
    fun `HAS_ELEMENT on a dotted bag key is backtick-quoted on the right`() {
        val (where, bindings) = render {
            where { query.predicate("metadata.tags", ComparisonOperator.HAS_ELEMENT, "graph") }
        }
        assertEquals("\$param_n_metadata_tags_0 IN n.`metadata.tags`", where)
        assertEquals("graph", bindings["param_n_metadata_tags_0"])
    }

    @Test
    fun `hasElement infix on an untyped reference renders the same as the operator`() {
        assertEquals(
            "\$param_n_tags_0 IN n.tags",
            render { where { query.property("tags") hasElement "kotlin" } }.first,
        )
    }

    @Test
    fun `hasAnyLabel renders an ANY-over-labels check and binds the label list`() {
        val (where, bindings) = render { where { query.hasAnyLabel("Chunk", "Section") } }
        assertEquals("ANY(_lbl IN labels(n) WHERE _lbl IN \$param_n_labels_0)", where)
        assertEquals(listOf("Chunk", "Section"), bindings["param_n_labels_0"])
    }

    @Test
    fun `property() and the typed PropertyBag key() render identically`() {
        val viaDynamic = render { where { query.property("metadata.source") eq "wiki" } }.first
        val bag = PropertyBagReference("n", "metadata.")
        val spec = GraphQuerySpec(RecordQueryDsl.INSTANCE)
        spec.where { bag.key("source") eq "wiki" }
        val viaBag = CypherGenerator.buildWhereClause(spec.conditions, null, grammar).whereClause
        assertEquals(viaBag, viaDynamic)
    }
}
