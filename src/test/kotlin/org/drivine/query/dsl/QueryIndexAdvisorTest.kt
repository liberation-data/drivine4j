/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.drivine.query.dsl

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.RangeIndexSpec
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The index advisor, against a real engine so the introspection path is exercised rather than mocked.
 *
 * The rule under test is that the index must **mirror** the ordering — same properties, same order.
 * Partial coverage does not count, because a composite index that only overlaps the cursor is the
 * case that profiles worst (see the README's pagination section).
 */
@Testcontainers
class QueryIndexAdvisorTest {

    companion object {
        private const val PASSWORD = "advisortest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-advisor", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private fun gom(): GraphObjectManager {
        val mapper = Neo4jObjectMapper.instance
        return GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
    }

    private fun GraphObjectManager.orderedLoad() =
        loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) {
            orderBy {
                query.proposition.level.desc()
                query.proposition.id.desc()
            }
            limit(2)
        }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(
            QuerySpecification.withStatement(
                "CREATE (:Proposition {id: 'p1', contextId: 'c', status: 'active', level: 1})"
            )
        )
        pm.indexes.list()
            .filter { it.label == "Proposition" }
            .mapNotNull { it.name }
            .forEach { pm.execute(QuerySpecification.withStatement("DROP INDEX $it IF EXISTS")) }
    }

    @Test
    fun `FAIL rejects an ordered query with no covering index, and names the fix`() {
        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        val error = assertThrows<IllegalStateException> { manager.orderedLoad() }

        assertTrue(error.message!!.contains("Proposition(level, id)"), error.message)
        assertTrue(
            error.message!!.contains("""@RangeIndex(properties = ["level", "id"])"""),
            error.message,
        )
    }

    @Test
    fun `an index mirroring the ordering satisfies the advisor`() {
        pm.indexes.ensure(RangeIndexSpec("Proposition", listOf("level", "id")))

        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        assertEquals(1, manager.orderedLoad().size)
    }

    @Test
    fun `an index over only part of the ordering does not count`() {
        pm.indexes.ensure(RangeIndexSpec("Proposition", "level"))

        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        assertThrows<IllegalStateException> { manager.orderedLoad() }
    }

    @Test
    fun `an index with the same properties in the wrong order does not count`() {
        pm.indexes.ensure(RangeIndexSpec("Proposition", listOf("id", "level")))

        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        assertThrows<IllegalStateException> { manager.orderedLoad() }
    }

    @Test
    fun `WARN and OFF never break a query that would otherwise run`() {
        assertEquals(1, gom().apply { indexAdvice = IndexAdvicePolicy.WARN }.orderedLoad().size)
        assertEquals(1, gom().apply { indexAdvice = IndexAdvicePolicy.OFF }.orderedLoad().size)
    }

    @Test
    fun `an unordered query is never advised on`() {
        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        assertEquals(1, manager.loadAll(PropositionView::class.java).size)
        assertEquals(
            1,
            manager.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) {
                where { query.proposition.id eq "p1" }
            }.size,
        )
    }

    @Test
    fun `a keyset cursor is advised on under its own operation name`() {
        val manager = gom().apply { indexAdvice = IndexAdvicePolicy.FAIL }

        val error = assertThrows<IllegalStateException> {
            manager.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) {
                orderBy {
                    query.proposition.level.desc()
                    query.proposition.id.desc()
                }
                seek {
                    query.proposition.level after 4
                    query.proposition.id after "p4"
                }
                limit(2)
            }
        }

        assertTrue(error.message!!.startsWith("seek on Proposition(level, id)"), error.message)
    }
}
