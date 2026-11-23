package org.drivine.schema

import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test

/**
 * Each case of the spec's `declarations.yaml` (liberation-data/drivine-cypher-spec#135), declared in drivine4j's own API ([DeclarationCases]),
 * gives the planner the catalogs and the items ensured at runtime the case pins: what the planner decides from, so a store
 * one library indexed is up to date for the others. drivine4j's are the reference. A case with no declaration here fails,
 * so a case the spec adds is never passed over. No database is spoken to: the request is read as the planner is given it.
 */
class DeclarationsGoldenTest {

    private val pinned = com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        .readTree(javaClass.getResourceAsStream("/cypher/declarations.yaml"))

    private fun provider(name: String) = object : ConnectionProvider {
        override val name = name
        override val type = DatabaseType.NEO4J
        override val subtypeRegistry: SubtypeRegistry? = null
        override val cypherDialect = CypherDialect.NEO4J_5
        override fun connect(): Connection = error("not connected")
        override fun end() {}
    }

    private fun manager(catalogs: List<SchemaCatalog>): SchemaManager {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap())).also { it.register(provider("default")) }
        return SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs)
    }

    @Test
    fun `each case declared in drivine4j's API gives the planner the catalogs it pins`() {
        val cases = DeclarationCases.all
        val declared = cases.associateBy { it.first }
        val names = pinned["cases"].map { it["case"].textValue() }
        // A name declared twice would be passed over by associateBy, its first declaration never checked.
        kotlin.test.assertEquals(emptyList(), cases.groupBy { it.first }.filterValues { it.size > 1 }.keys.toList(), "cases declared here twice")
        kotlin.test.assertEquals(emptyList(), names.filter { it !in declared }, "declarations.yaml cases with no declaration here")
        kotlin.test.assertEquals(emptyList(), declared.keys.filter { it !in names }, "declarations here no case of declarations.yaml pins")
        for (case in pinned["cases"]) {
            val name = case["case"].textValue()
            val (_, catalogs, ensured) = declared.getValue(name)
            val runtime = if (ensured.isEmpty()) emptyList() else SchemaPlanner.runtimeAfter(Operation.Ensure(DeclarationCases.RUNTIME_OWNER, ensured), emptyList(), setOf("default"))
            val request = CypherJson.readTree(manager(catalogs).request("default", Operation.Enforce(), runtime).toJson())
            val json = com.fasterxml.jackson.databind.ObjectMapper()
            kotlin.test.assertEquals(json.writeValueAsString(case["catalogs"]), json.writeValueAsString(json.readTree(CypherJson.writeValueAsString(request["catalogs"]!!))), "$name: catalogs")
            kotlin.test.assertEquals(json.writeValueAsString(case["runtime"]), json.writeValueAsString(json.readTree(CypherJson.writeValueAsString(request["runtime"]!!))), "$name: runtime")
        }
    }
}
