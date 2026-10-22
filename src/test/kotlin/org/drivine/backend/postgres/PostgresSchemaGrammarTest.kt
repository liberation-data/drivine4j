package org.drivine.backend.postgres

import org.drivine.query.SqlParameters
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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

    @Test
    fun `a conflicting node is named by the fragment's id too, unless that is a key`() {
        val byUuid = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "it's"), 3)
        assertTrue("array_agg(jsonb_build_object('elementId', n.nid, 'it''s', (n.props ->> 'it''s')) ORDER BY n.nid))[1:3]" in byUuid, byUuid)

        val byKey = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "email"), 3)
        assertTrue("array_agg(jsonb_build_object('elementId', n.nid) ORDER BY n.nid))[1:3]" in byKey, byKey)
    }
}
