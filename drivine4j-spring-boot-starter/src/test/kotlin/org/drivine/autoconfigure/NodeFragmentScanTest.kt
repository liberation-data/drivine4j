package org.drivine.autoconfigure

import org.drivine.autoconfigure.scanfixtures.ScanAccount
import org.drivine.autoconfigure.scanfixtures.ScanChunk
import org.drivine.autoconfigure.scanconflict.ScanCalm
import org.drivine.autoconfigure.scanfixtures.ScanHolder
import org.drivine.autoconfigure.scanfixtures.ScanJavaFragment
import org.drivine.autoconfigure.scanfixtures.ScanPerson
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.DataSourceMap
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.DatabaseTarget
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorDimensionProvider
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The schema the starter reads off an application's `@NodeFragment` classes and interfaces. */
class NodeFragmentScanTest {

    private val fixtures = "org.drivine.autoconfigure.scanfixtures"
    private val loader = javaClass.classLoader

    @Test
    fun `the scan finds each fragment class and interface of a package, nested or Java, and nothing else`() {
        assertEquals(
            listOf(ScanAccount::class.java, ScanChunk::class.java, ScanHolder.Nested::class.java, ScanJavaFragment::class.java, ScanPerson::class.java),
            NodeFragmentScan.fragments(listOf(fixtures), loader),
        )
        assertEquals(emptyList(), NodeFragmentScan.fragments(emptyList(), loader))
    }

    @Test
    fun `a package that can't be scanned is passed over, and the others still scanned`() {
        // A loader that can't list the broken package's resources, as a jar it can't read.
        val broken = object : ClassLoader(loader) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                if (name.startsWith("org/drivine/autoconfigure/broken")) throw java.io.IOException("unreadable") else super.getResources(name)
        }
        assertEquals(
            NodeFragmentScan.fragments(listOf(fixtures), loader),
            NodeFragmentScan.fragments(listOf("org.drivine.autoconfigure.broken", fixtures), broken),
        )
    }

    @Test
    fun `the packages are those configured, else Spring Boot's auto-configuration packages, else none`() {
        val factory = DefaultListableBeanFactory()
        assertEquals(emptyList(), NodeFragmentScan.packages(factory, emptyList()))
        AutoConfigurationPackages.register(factory, "com.example.app")
        assertEquals(listOf("com.example.app"), NodeFragmentScan.packages(factory, emptyList()))
        assertEquals(listOf("com.example.other"), NodeFragmentScan.packages(factory, listOf("com.example.other")))
    }

    @Test
    fun `a fragment with a vector index is read with a dimension provider, and passed over without one`() {
        val fragments = NodeFragmentScan.fragments(listOf(fixtures), loader)
        val without = NodeFragmentScan.catalogs(fragments, null, emptyList(), emptySet()).single()
        assertTrue(without.items.none { it.label == "ScanChunk" })
        assertTrue(RangeIndexSpec("ScanPerson", "name") in without.indexes)
        assertTrue(RangeIndexSpec("ScanJava", "code") in without.indexes)
        assertTrue(without.constraints.any { it is UniquenessConstraintSpec && it.label == "ScanAccount" && it.properties == listOf("code") })

        val with = NodeFragmentScan.catalogs(fragments, VectorDimensionProvider { _, _ -> 384 }, emptyList(), emptySet()).single()
        assertEquals(384, with.indexes.filterIsInstance<VectorIndexSpec>().single().dimensions)
    }

    @Test
    fun `an item an application's own catalog declares is left to it`() {
        val fragments = NodeFragmentScan.fragments(listOf(fixtures), loader)
        val own = SchemaCatalog.of(RangeIndexSpec("ScanPerson", listOf("name"), "person_name_custom"))
        val scanned = NodeFragmentScan.catalogs(fragments, null, listOf(own), emptySet()).single()
        assertTrue(scanned.items.none { it.label == "ScanPerson" && it.properties == listOf("name") })
        assertTrue(scanned.constraints.isNotEmpty())
        // Nothing left once the application declares it all.
        assertEquals(
            emptyList(),
            NodeFragmentScan.catalogs(listOf(ScanAccount::class.java), null, listOf(SchemaCatalog.of(UniquenessConstraintSpec("ScanAccount", "code"))), emptySet()),
        )
    }

    @Test
    fun `a fragment's @NodeId default stays a default through the scan, beside a TEXT index and an application's own constraint over the id`() {
        val fragments = listOf(ScanHolder.Nested::class.java, ScanPerson::class.java)
        val default = RangeIndexSpec("ScanPerson", "id")
        val own = SchemaCatalog.of(UniquenessConstraintSpec("ScanPerson", "id"))
        val (all, _) = NodeFragmentScan.catalogs(fragments, null, listOf(own), setOf("neo"))
        assertTrue(default in all.indexes)
        assertTrue(default in all.nodeIdIndexes)
        assertTrue(RangeIndexSpec("ScanNested", "id") in all.nodeIdIndexes)
        // So drivine.schema.index-node-ids=false leaves it out, and only the defaults.
        assertEquals(all.items - all.nodeIdIndexes, all.withoutNodeIdIndexes().items)
    }

    @Test
    fun `fragments that declare conflicting items are passed over together, and the rest applied, never failing startup`() {
        val fragments = NodeFragmentScan.fragments(listOf("org.drivine.autoconfigure.scanconflict"), loader)
        assertEquals(3, fragments.size)
        assertEquals(listOf(RangeIndexSpec("ScanCalm", "name")), NodeFragmentScan.catalogs(fragments, null, emptyList(), emptySet()).single().items)
        assertEquals(emptyList(), NodeFragmentScan.catalogs(fragments - ScanCalm::class.java, null, emptyList(), emptySet()))
    }

    @Test
    fun `a TEXT index is given to the Neo4j databases alone, and left out where there is none`() {
        val fragments = listOf(ScanHolder.Nested::class.java, ScanPerson::class.java)
        val (all, neo4j) = NodeFragmentScan.catalogs(fragments, null, emptyList(), setOf("neo"))
        assertEquals(DatabaseTarget.All, all.target)
        assertTrue(all.items.none { it is TextIndexSpec })
        assertEquals(listOf(TextIndexSpec("ScanNested", "title")), neo4j.items)
        assertEquals(DatabaseTarget.Named(setOf("neo")), neo4j.target)

        assertEquals(all.items, NodeFragmentScan.catalogs(fragments, null, emptyList(), emptySet()).single().items)
    }

    @Test
    fun `the TEXT index goes to the Neo4j databases that manage schema, not to another store or a Neo4j one that refuses it`() {
        val registry = DatabaseRegistry(DataSourceMap())
        registry.register(provider("neo", DatabaseType.NEO4J, CypherDialect.NEO4J_5))
        registry.register(provider("plain", DatabaseType.NEO4J, CypherDialect.OPEN_CYPHER))
        registry.register(provider("refused", DatabaseType.NEO4J, CypherDialect.NEO4J_5, refusal = "read only."))
        registry.register(provider("memgraph", DatabaseType.MEMGRAPH, CypherDialect.MEMGRAPH))
        assertEquals(setOf("neo"), NodeFragmentScan.neo4jDatabases(registry))
        // The scanned schema goes to every database that manages schema, of any store.
        assertEquals(setOf("neo", "memgraph"), NodeFragmentScan.schemaDatabases(registry))
    }

    // ----- An application's catalog for some databases (#851) -----

    private val person = listOf(ScanPerson::class.java)
    private val name = RangeIndexSpec("ScanPerson", "name")
    private val id = RangeIndexSpec("ScanPerson", "id")

    @Test
    fun `an item an application's catalog declares for one database is left to it there alone, and made on the others`() {
        val own = SchemaCatalog.of(RangeIndexSpec("ScanPerson", listOf("name"), "person_name_a")).forDatabase("a")
        val scanned = NodeFragmentScan.catalogs(person, null, listOf(own), emptySet(), setOf("a", "b"))
        val onA = scanned.single { it.target == DatabaseTarget.Named(setOf("a")) }
        val onB = scanned.single { it.target == DatabaseTarget.Named(setOf("b")) }
        assertTrue(name !in onA.indexes)
        assertTrue(name in onB.indexes)
        // The id's default is a default on both, as nothing declares it.
        assertEquals(setOf(id), onA.nodeIdIndexes)
        assertEquals(setOf(id), onB.nodeIdIndexes)
    }

    @Test
    fun `a default an application's catalog for one database serves is left out there alone, and stays a default on the others`() {
        val own = SchemaCatalog.of(RangeIndexSpec("ScanPerson", listOf("id"), "person_id_a")).forDatabase("a")
        val scanned = NodeFragmentScan.catalogs(person, null, listOf(own), emptySet(), setOf("a", "b", "c"))
        assertEquals(2, scanned.size)
        val onA = scanned.single { it.target == DatabaseTarget.Named(setOf("a")) }
        assertTrue(id !in onA.indexes)
        assertEquals(emptySet(), onA.nodeIdIndexes)
        // b and c are left the same, so they share one catalog.
        val rest = scanned.single { it.target == DatabaseTarget.Named(setOf("b", "c")) }
        assertEquals(setOf(id), rest.nodeIdIndexes)
    }

    @Test
    fun `where every database is left the same, the one catalog keeps the scan's target, as it did before`() {
        val everywhere = SchemaCatalog.of(RangeIndexSpec("ScanPerson", listOf("name"), "person_name"))
        val forAll = NodeFragmentScan.catalogs(person, null, listOf(everywhere), emptySet(), setOf("a", "b")).single()
        assertEquals(DatabaseTarget.All, forAll.target)
        assertTrue(name !in forAll.indexes)
        // One database, and a catalog for it: the same.
        val one = NodeFragmentScan.catalogs(person, null, listOf(everywhere.forDatabase("a")), emptySet(), setOf("a")).single()
        assertEquals(DatabaseTarget.All, one.target)
        assertTrue(name !in one.indexes)
    }

    @Test
    fun `a catalog for a database that doesn't manage schema, or no database known, leaves the scan one catalog for all`() {
        val own = SchemaCatalog.of(RangeIndexSpec("ScanPerson", listOf("name"), "person_name_pg")).forDatabase("pg")
        // pg is not among the databases that manage schema: none of them is left the item, so all keep it.
        val elsewhere = NodeFragmentScan.catalogs(person, null, listOf(own), emptySet(), setOf("a", "b")).single()
        assertEquals(DatabaseTarget.All, elsewhere.target)
        assertTrue(name in elsewhere.indexes)
        // No database known: what the catalog declares is left out, wherever it is for.
        val unknown = NodeFragmentScan.catalogs(person, null, listOf(own), emptySet(), emptySet()).single()
        assertEquals(DatabaseTarget.All, unknown.target)
        assertTrue(name !in unknown.indexes)
    }

    @Test
    fun `a TEXT index goes to the Neo4j databases among those its group is for`() {
        val nested = listOf(ScanHolder.Nested::class.java)
        val own = SchemaCatalog.of(RangeIndexSpec("ScanNested", listOf("id"), "nested_id_a")).forDatabase("a")
        val scanned = NodeFragmentScan.catalogs(nested, null, listOf(own), setOf("a", "b"), setOf("a", "b", "pg"))
        val text = scanned.filter { it.items.any { item -> item is TextIndexSpec } }
        assertEquals(setOf<DatabaseTarget>(DatabaseTarget.Named(setOf("a")), DatabaseTarget.Named(setOf("b"))), text.map { it.target }.toSet())
        assertTrue(scanned.none { it.target is DatabaseTarget.Named && "pg" in (it.target as DatabaseTarget.Named).databases && it.items.any { item -> item is TextIndexSpec } })
    }

    private fun provider(name: String, type: DatabaseType, dialect: CypherDialect, refusal: String? = null) = object : ConnectionProvider {
        override val name = name
        override val type = type
        override val subtypeRegistry: SubtypeRegistry? = null
        override val cypherDialect = dialect
        override val schemaRefusal = refusal
        override fun connect(): Connection = error("not connected")
        override fun end() {}
    }
}
