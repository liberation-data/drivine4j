package org.drivine.backend.postgres

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.query.SqlParameters
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaStatement
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresSchemaGrammarTest {

    private val grammar = PostgresSchemaGrammar(PostgresGraph("g"))

    @Test
    fun `the conflicts of a constraint are the labelled nodes that share values of its properties, each named by its nid`() {
        val query = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", listOf("email", "tenant")), 5)

        assertEquals(
            """
            SELECT jsonb_build_object('properties', jsonb_build_object('email', (n.props ->> 'email'), 'tenant', (n.props ->> 'tenant')), 'count', COUNT(*),
                                      'nodes', to_jsonb((array_agg(jsonb_build_object('elementId', n.nid) ORDER BY n.nid))[1:5]))
            FROM g_node n
            WHERE n.labels @> '["Person"]' AND (n.props ->> 'email') IS NOT NULL AND (n.props ->> 'tenant') IS NOT NULL
            GROUP BY (n.props ->> 'email'), (n.props ->> 'tenant')
            HAVING COUNT(*) > 1
            LIMIT 5
            """.trimIndent(),
            query,
        )
        assertEquals(emptyList(), SqlParameters.positional(query).names)
    }

    /**
     * The one statement the switch to the module's DDL changed (drivine-cypher-spec#105): a label's JSON in a partial
     * index's predicate is written as Jackson writes the labels a node holds, so a tab in it is `\t`. Before, it was
     * written raw, which jsonb refuses, so such an index could not be made. The compiled read tests the same predicate,
     * so the index is the read's. Run on no database.
     */
    @Test
    fun `a range index on a label holding a tab tests the label's JSON with the tab escaped, as a compiled read tests it`() {
        val label = "a\tb"
        val predicate = """labels @> '["a\tb"]'"""
        val made = grammar.createIndex(RangeIndexSpec(label, listOf("name"))).map { (it as SchemaStatement.Cypher).statement }
        val index = made.single { it.startsWith("CREATE INDEX") }
        assertTrue(index.endsWith("WHERE $predicate"), index)
        assertFalse('\t' in index, index)

        val read = PostgresCypherCompiler("g").compile("MATCH (n:`$label` {name: \$x}) RETURN n", mapOf("x" to "Ada")).sql
        assertTrue(".$predicate" in read, read)
    }

    @Test
    fun `a conflicting node is named by the fragment's id too, unless that is a key`() {
        val byUuid = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "it's"), 3)
        assertTrue("array_agg(jsonb_build_object('elementId', n.nid, 'it''s', (n.props ->> 'it''s')) ORDER BY n.nid))[1:3]" in byUuid, byUuid)

        val byKey = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "email"), 3)
        assertTrue("array_agg(jsonb_build_object('elementId', n.nid) ORDER BY n.nid))[1:3]" in byKey, byKey)
    }
}
