package org.drivine.schema

import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A catalog for the `default` alias, as `forDefaultDatabase()` makes one, is applied to the first-registered database the
 * alias resolves to, and to none other (#854): its items reach that database's plan, as on `enforce` and on `ensure`.
 * Before, the database was planned but the catalog, whose target still said `default`, was filtered out of its request,
 * and its items were never made. No database is spoken to: the requests are read as the planner would be given them.
 */
class SchemaManagerDefaultTargetTest {

    private val index = RangeIndexSpec("Person", "name")
    private val other = RangeIndexSpec("Order", "placedAt")

    private fun provider(name: String) = object : ConnectionProvider {
        override val name = name
        override val type = DatabaseType.NEO4J
        override val subtypeRegistry: SubtypeRegistry? = null
        override val cypherDialect = CypherDialect.NEO4J_5
        override fun connect(): Connection = error("not connected")
        override fun end() {}
    }

    /** A manager over two databases, `neo` registered first, and [catalogs]. */
    private fun manager(vararg catalogs: SchemaCatalog): SchemaManager {
        val registry = DatabaseRegistry(DataSourceMap(emptyMap())).also {
            it.register(provider("neo"))
            it.register(provider("other"))
        }
        return SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs.toList())
    }

    /** The items the planner is asked to apply on [database] for an enforce. */
    private fun SchemaManager.applied(database: String) =
        request(database, Operation.Enforce(), emptyList()).catalogs.flatMap { it.items }

    @Test
    fun `a catalog for the default database is applied to the first-registered one, and not to another`() {
        for (catalog in listOf(SchemaCatalog.of(index).forDefaultDatabase(), SchemaCatalog.of(index).forDatabase("default"))) {
            val manager = manager(catalog, SchemaCatalog.of(other).forDatabase("other"))
            assertEquals(listOf<SchemaItemSpec>(index), manager.applied("neo"), "${catalog.target}")
            assertEquals(listOf<SchemaItemSpec>(other), manager.applied("other"), "${catalog.target}")
        }
    }

    @Test
    fun `the labels a fragment is checked under are those of the catalogs for each database, the default alias resolved`() {
        val catalogs = listOf(
            SchemaCatalog.of(index).forDefaultDatabase(),
            SchemaCatalog.of(other).forDatabase("other"),
            SchemaCatalog.of(RangeIndexSpec("Customer", "id")).forAllDatabases(),
        )
        assertEquals(
            mapOf("neo" to setOf("Person", "Customer"), "other" to setOf("Order", "Customer")),
            manager(*catalogs.toTypedArray()).cataloguedOn(listOf("neo", "other"), catalogs),
        )
    }

    @Test
    fun `a fragment read into an empty catalog is checked where that catalog is for, though no item is applied there (#848)`() {
        val catalogs = listOf(
            SchemaCatalog.of(index).forDefaultDatabase(),
            SchemaCatalog.fromFragments(UnindexedIdFixture::class).forDatabase("other"),
        )
        // What SchemaTargets resolves leaves out the empty catalog's database: its fragment belongs there all the same.
        val labels = manager(*catalogs.toTypedArray()).cataloguedOn(listOf("neo"), catalogs)
        assertTrue(catalogs[1].isEmpty())
        assertEquals(setOf("Person"), labels.getValue("neo"))
        assertEquals(setOf("Account"), labels.getValue("other"))
    }

    @Test
    fun `a catalog for both the default and the first-registered database by name is applied there once`() {
        val manager = manager(SchemaCatalog.of(index).forDatabases("default", "neo"))
        assertEquals(listOf<SchemaItemSpec>(index), manager.applied("neo"))
        assertEquals(emptyList(), manager.applied("other"))
    }

    @Test
    fun `items ensured for the default database are kept for, and planned on, the first-registered one`() {
        val manager = manager()
        val databases = manager.databasesOf(DatabaseTarget.Named(setOf("default")))
        assertEquals(setOf("neo"), databases)
        val runtime = SchemaPlanner.runtimeAfter(Operation.Ensure(null, listOf(index)), emptyList(), databases)
        assertEquals(listOf<SchemaItemSpec>(index), manager.request("neo", Operation.Enforce(), runtime).runtime.flatMap { it.items })
        assertEquals(emptyList(), manager.request("other", Operation.Enforce(), runtime).runtime.flatMap { it.items })
    }

    @Test
    fun `items ensured for the default database are forgotten by a remove naming the first-registered one, and the other way round`() {
        val manager = manager()
        val default = manager.databasesOf(DatabaseTarget.Named(setOf("default")))
        val neo = manager.databasesOf(DatabaseTarget.Named(setOf("neo")))
        for ((ensured, removed) in listOf(default to neo, neo to default)) {
            val runtime = SchemaPlanner.runtimeAfter(Operation.Ensure(null, listOf(index)), emptyList(), ensured)
            assertEquals(emptyList(), SchemaPlanner.runtimeAfter(Operation.Remove(null, listOf(index)), runtime, removed).flatMap { it.items })
        }
    }
}
