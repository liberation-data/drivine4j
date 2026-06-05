package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaTargetsTest {

    /** Minimal provider whose only job is to advertise a dialect (and thus schema capability). */
    private class FakeProvider(
        override val name: String,
        override val cypherDialect: CypherDialect,
    ) : ConnectionProvider {
        override val type: DatabaseType = DatabaseType.NEO4J
        override val subtypeRegistry: SubtypeRegistry? = null
        override fun connect(): Connection = throw UnsupportedOperationException()
        override fun end() {}
    }

    /**
     * Builds a registry with the given (name → dialect) providers. Registration order is preserved,
     * so the first entry is the "default".
     */
    private fun registryOf(vararg providers: Pair<String, CypherDialect>): DatabaseRegistry {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap()))
        providers.forEach { (name, dialect) -> registry.register(FakeProvider(name, dialect)) }
        return registry
    }

    private val anIndex = RangeIndexSpec("Proposition", "contextId")

    @Test
    fun `broadcast catalog targets every schema-capable database`() {
        val registry = registryOf("neo" to CypherDialect.NEO4J_5, "graph" to CypherDialect.MEMGRAPH)

        val resolution = SchemaTargets.resolve(listOf(SchemaCatalog.of(anIndex)), registry)

        assertEquals(setOf("neo", "graph"), resolution.databases)
        assertTrue(resolution.skipped.isEmpty())
    }

    @Test
    fun `broadcast skips schema-incapable engines and reports them`() {
        val registry = registryOf(
            "neo" to CypherDialect.NEO4J_5,
            "neptune" to CypherDialect.NEPTUNE,        // no DDL parity
            "open" to CypherDialect.OPEN_CYPHER,       // no DDL parity
        )

        val resolution = SchemaTargets.resolve(listOf(SchemaCatalog.of(anIndex)), registry)

        assertEquals(setOf("neo"), resolution.databases)
        assertEquals(setOf("neptune", "open"), resolution.skipped)
    }

    @Test
    fun `named target applies only to the named database`() {
        val registry = registryOf("neo" to CypherDialect.NEO4J_5, "graph" to CypherDialect.MEMGRAPH)

        val resolution = SchemaTargets.resolve(
            listOf(SchemaCatalog.of(anIndex).forDatabase("graph")),
            registry,
        )

        assertEquals(setOf("graph"), resolution.databases)
        assertTrue(resolution.skipped.isEmpty())
    }

    @Test
    fun `forDefaultDatabase resolves to the first-registered datasource`() {
        val registry = registryOf("primary" to CypherDialect.NEO4J_5, "secondary" to CypherDialect.MEMGRAPH)

        val resolution = SchemaTargets.resolve(
            listOf(SchemaCatalog.of(anIndex).forDefaultDatabase()),
            registry,
        )

        assertEquals(setOf("primary"), resolution.databases)
    }

    @Test
    fun `broadcast and named catalogs combine - named adds to the capable set`() {
        val registry = registryOf("neo" to CypherDialect.NEO4J_5, "graph" to CypherDialect.MEMGRAPH)

        val resolution = SchemaTargets.resolve(
            listOf(
                SchemaCatalog.of(anIndex),                                  // all capable
                SchemaCatalog.of(RangeIndexSpec("User", "id")).forDatabase("graph"),
            ),
            registry,
        )

        assertEquals(setOf("neo", "graph"), resolution.databases)
    }

    @Test
    fun `named target on an unknown database fails fast`() {
        val registry = registryOf("neo" to CypherDialect.NEO4J_5)

        assertThrows<DrivineException> {
            SchemaTargets.resolve(listOf(SchemaCatalog.of(anIndex).forDatabase("nope")), registry)
        }
    }

    @Test
    fun `named target on a schema-incapable engine fails fast`() {
        val registry = registryOf("neptune" to CypherDialect.NEPTUNE)

        val exception = assertThrows<DrivineException> {
            SchemaTargets.resolve(listOf(SchemaCatalog.of(anIndex).forDatabase("neptune")), registry)
        }
        assertTrue(exception.message!!.contains("does not support schema management"))
    }

    @Test
    fun `empty catalogs resolve to no databases`() {
        val registry = registryOf("neo" to CypherDialect.NEO4J_5)

        val resolution = SchemaTargets.resolve(listOf(SchemaCatalog.of()), registry)

        assertTrue(resolution.databases.isEmpty())
    }
}