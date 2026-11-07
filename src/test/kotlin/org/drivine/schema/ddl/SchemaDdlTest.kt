package org.drivine.schema.ddl

import org.drivine.DrivineException
import org.drivine.connection.CypherCompile
import org.drivine.connection.DatabaseType
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaVersionStore
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.ddl.SchemaDdlResult.AwaitIndexes
import org.drivine.schema.ddl.SchemaDdlResult.Create
import org.drivine.schema.ddl.SchemaDdlResult.Drop
import org.drivine.schema.ddl.SchemaDdlResult.NotDdl
import org.drivine.schema.ddl.SchemaDdlResult.Refused
import org.drivine.schema.ddl.SchemaDdlResult.Reserved
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [SchemaDdl.translate]: Neo4j's schema DDL read as Drivine's specs, with no store. `cypher/schema.yaml` pins the shapes the three libraries share. */
class SchemaDdlTest {

    private fun translate(text: String) = SchemaDdl.translate(text)

    private fun refused(text: String): String = assertIs<Refused>(translate(text), text).message

    @Test
    fun `the schema file of an APOC export reads as the specs the schema API declares`() {
        assertEquals(
            Create(FullTextIndexSpec("Chunk", listOf("text")), null, true),
            translate("CREATE FULLTEXT INDEX Chunk_text_fulltext IF NOT EXISTS FOR (n:Chunk) ON EACH [n.`text`];"),
        )
        assertEquals(Create(RangeIndexSpec("ChatSession", "lastActivityAt"), null, true), translate("CREATE RANGE INDEX IF NOT EXISTS FOR (n:ChatSession) ON (n.lastActivityAt);"))
        assertEquals(
            Create(RangeIndexSpec("ContentElement", listOf("root_document_id", "ordinal")), null, true),
            translate("CREATE RANGE INDEX IF NOT EXISTS FOR (n:ContentElement) ON (n.root_document_id, n.ordinal);"),
        )
        assertEquals(
            Create(UniquenessConstraintSpec("Attachment", "attachmentId"), null, true),
            translate("CREATE CONSTRAINT Attachment_attachmentId_unique IF NOT EXISTS FOR (node:Attachment) REQUIRE (node.attachmentId) IS UNIQUE;"),
        )
        assertEquals(
            Reserved(UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)),
            translate("CREATE CONSTRAINT _DrivineSchema_scope_unique IF NOT EXISTS FOR (node:_DrivineSchema) REQUIRE (node.scope) IS UNIQUE;"),
        )
        assertEquals(
            Create(UniquenessConstraintSpec("UNIQUE IMPORT LABEL", "UNIQUE IMPORT ID"), "UNIQUE_IMPORT_NAME", true),
            translate("CREATE CONSTRAINT UNIQUE_IMPORT_NAME IF NOT EXISTS FOR (node:`UNIQUE IMPORT LABEL`) REQUIRE (node.`UNIQUE IMPORT ID`) IS UNIQUE;"),
        )
        assertEquals(AwaitIndexes, translate("CALL db.awaitIndexes(300);"))
        assertEquals(Drop(Drop.Kind.CONSTRAINT, "UNIQUE_IMPORT_NAME", false), translate("DROP CONSTRAINT UNIQUE_IMPORT_NAME;"))
    }

    @Test
    fun `a spec is unnamed, and the DDL's name is its alias only where the schema API names it otherwise`() {
        val named = assertIs<Create>(translate("CREATE INDEX person_name FOR (p:Person) ON (p.name)"))
        assertEquals(null, named.spec.name)
        assertEquals("person_name", named.alias)
        assertEquals("Person_name_range", named.spec.effectiveName)
        assertEquals(null, assertIs<Create>(translate("CREATE INDEX Person_name_range FOR (p:Person) ON (p.name)")).alias)
        assertEquals(null, assertIs<Create>(translate("CREATE CONSTRAINT FOR (p:Person) REQUIRE p.email IS UNIQUE")).alias)
    }

    @Test
    fun `each form of a range index and a uniqueness constraint, Neo4j 4's among them`() {
        val range = Create(RangeIndexSpec("Person", listOf("name", "born")), null, false)
        assertEquals(range, translate("CREATE INDEX FOR (p:Person) ON (p.name, p.born)"))
        assertEquals(range, translate("CREATE RANGE INDEX FOR (p:Person) ON (p.name, p.born)"))
        assertEquals(range, translate("CREATE BTREE INDEX FOR (p:Person) ON (p.name, p.born)"))
        assertEquals(range, translate("CREATE INDEX ON :Person(name, born)"))
        assertEquals(range, translate("create index for (p:Person) on (p.name, p.born) options {indexProvider: 'range-1.0'}"))
        assertEquals(range.copy(spec = RangeIndexSpec("Person", "name"), alias = "by_name", ifNotExists = true), translate("CREATE INDEX by_name IF NOT EXISTS FOR (p:Person) ON p.name"))

        val unique = Create(UniquenessConstraintSpec("Person", "email"), null, false)
        assertEquals(unique, translate("CREATE CONSTRAINT FOR (p:Person) REQUIRE p.email IS UNIQUE"))
        assertEquals(unique, translate("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.email) IS NODE UNIQUE"))
        assertEquals(unique, translate("CREATE CONSTRAINT ON (p:Person) ASSERT p.email IS UNIQUE"))
        assertEquals(unique.copy(alias = "email", ifNotExists = true), translate("CREATE CONSTRAINT email IF NOT EXISTS ON (p:Person) ASSERT (p.email) IS UNIQUE"))
        assertEquals(
            Create(UniquenessConstraintSpec("Person", listOf("first", "last")), null, false),
            translate("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.first, p.last) IS UNIQUE"),
        )
    }

    @Test
    fun `a full-text index takes its analyzer, a vector index its options, and a TEXT index is Neo4j's`() {
        assertEquals(
            Create(FullTextIndexSpec("Book", listOf("title", "subtitle"), analyzer = "english"), "titles", false),
            translate("CREATE FULLTEXT INDEX titles FOR (n:Book) ON EACH [n.title, n.subtitle] OPTIONS {indexConfig: {`fulltext.analyzer`: 'english', `fulltext.eventually_consistent`: true}}"),
        )
        assertEquals(
            Create(
                VectorIndexSpec(
                    "Chunk", "embedding", 3, SimilarityFunction.EUCLIDEAN, hnswM = 16, hnswEfConstruction = 100,
                    engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false)),
                ),
                null, false,
            ),
            translate(
                "CREATE VECTOR INDEX FOR (c:Chunk) ON (c.embedding) OPTIONS {indexProvider: 'vector-2.0', indexConfig: {`vector.dimensions`: 3, " +
                    "`vector.similarity_function`: 'EUCLIDEAN', `vector.hnsw.m`: 16, `vector.hnsw.ef_construction`: 100, `vector.quantization.enabled`: false}}"
            ),
        )
        assertEquals(
            Create(VectorIndexSpec("Chunk", "embedding", 1536), "chunk_embedding", true),
            translate("CREATE VECTOR INDEX chunk_embedding IF NOT EXISTS FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 1536, `vector.similarity_function`: 'cosine'}}"),
        )
        assertEquals(Create(TextIndexSpec("Person", "name"), null, false), translate("CREATE TEXT INDEX FOR (p:Person) ON (p.name)"))
    }

    @Test
    fun `a name in backticks holds spaces, and a doubled backtick in it stands for one`() {
        val created = assertIs<Create>(translate("CREATE CONSTRAINT `the ``key`` of a thing` FOR (`a node`:`A Thing`) REQUIRE (`a node`.`the key`) IS UNIQUE"))
        assertEquals(UniquenessConstraintSpec("A Thing", "the key"), created.spec)
        assertEquals("the `key` of a thing", created.alias)
        assertEquals(Drop(Drop.Kind.INDEX, "by name", true), translate("drop index `by name` if exists"))
        assertEquals(Drop(Drop.Kind.CONSTRAINT, "a``b".replace("``", "`"), false), translate("DROP CONSTRAINT `a``b`"))
        assertTrue(refused("CREATE INDEX `unclosed FOR (p:Person) ON (p.name)").startsWith("Invalid schema command:"))
    }

    @Test
    fun `db awaitIndexes and awaitIndex, with arguments or none, do nothing`() {
        listOf("CALL db.awaitIndexes", "CALL db.awaitIndexes()", "CALL db.awaitIndexes(300)", "CALL db.awaitIndex('x', 300);", "CALL db.awaitIndexes(\$t)").forEach {
            assertEquals(AwaitIndexes, translate(it), it)
        }
    }

    @Test
    fun `Cypher that uses the words is Cypher`() {
        listOf(
            "CREATE index = (:A)-[:R]->(:B)",
            "CREATE constraint = (:A)",
            "CREATE RANGE = (:A)",
            "CREATE (index:Index {name: 'CREATE INDEX'})",
            "MATCH (index:Index) RETURN index",
            "CALL db.labels()",
            "CALL db.index.fulltext.queryNodes('x', 'y')",
            "RETURN 'DROP INDEX x'",
            "DROP DATABASE x",
            "  // a comment\n MATCH (n) RETURN n",
            "",
        ).forEach { assertEquals(NotDdl, translate(it), it) }
    }

    @Test
    fun `what a SQL store cannot honour is refused by name`() {
        assertEquals("A POINT index is Neo4j's, and is not made on a SQL store.", refused("CREATE POINT INDEX FOR (p:Place) ON (p.location)"))
        assertTrue(refused("CREATE LOOKUP INDEX FOR (n) ON EACH labels(n)").startsWith("A LOOKUP index"))
        assertEquals("An index on relationships is not made on a SQL store: Drivine's schema is of nodes.", refused("CREATE INDEX FOR ()-[r:KNOWS]-() ON (r.since)"))
        assertEquals("A constraint on relationships is not made on a SQL store: Drivine's schema is of nodes.", refused("CREATE CONSTRAINT FOR ()-[r:R]-() REQUIRE r.id IS UNIQUE"))
        assertEquals("A vector index on relationships is not made on a SQL store: Drivine's schema is of nodes.", refused("CREATE VECTOR INDEX FOR ()-[r:R]-() ON r.e"))
        assertEquals("An index over several labels is not made on a SQL store: declare one for each label.", refused("CREATE INDEX FOR (n:A|B) ON (n.p)"))
        assertEquals("A constraint over several labels is not made on a SQL store: declare one for each label.", refused("CREATE CONSTRAINT FOR (n:A:B) REQUIRE n.p IS UNIQUE"))
        val existence = "A property existence constraint (IS NOT NULL) is not made on a SQL store: Drivine's schema has uniqueness constraints only."
        assertEquals(existence, refused("CREATE CONSTRAINT FOR (p:P) REQUIRE p.name IS NOT NULL"))
        assertEquals(existence, refused("CREATE CONSTRAINT ON (p:P) ASSERT exists(p.name)"))
        val key = "A key constraint (IS NODE KEY) is not made on a SQL store: Drivine's schema has uniqueness constraints only."
        assertEquals(key, refused("CREATE CONSTRAINT FOR (p:P) REQUIRE (p.a, p.b) IS NODE KEY"))
        assertEquals(key, refused("CREATE CONSTRAINT FOR (p:P) REQUIRE p.a IS KEY"))
        assertTrue(refused("CREATE CONSTRAINT FOR (p:P) REQUIRE p.a IS :: STRING").startsWith("A property type constraint"))
        assertTrue(refused("CREATE CONSTRAINT FOR (p:P) REQUIRE p.a IS TYPED STRING").startsWith("A property type constraint"))
        assertEquals("A vector index is over one property, and this names 2.", refused("CREATE VECTOR INDEX FOR (c:C) ON (c.a, c.b) OPTIONS {indexConfig: {`vector.dimensions`: 3}}"))
        assertEquals("A vector index WITH properties to filter by is not made on a SQL store.", refused("CREATE VECTOR INDEX FOR (c:C) ON c.e WITH [c.l] OPTIONS {}"))
        assertTrue(refused("CREATE VECTOR INDEX FOR (c:C) ON c.e").contains("`vector.dimensions`"))
        assertTrue(refused("CREATE VECTOR INDEX FOR (c:C) ON c.e OPTIONS {indexConfig: {`vector.dimensions`: 0}}").contains("positive whole number"))
        assertEquals(
            "A vector index's `vector.similarity_function` is 'cosine' or 'euclidean', not 'dot'.",
            refused("CREATE VECTOR INDEX FOR (c:C) ON c.e OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.similarity_function`: 'dot'}}"),
        )
        assertTrue(refused("DROP CONSTRAINT ON (p:P) ASSERT p.a IS UNIQUE").startsWith("DROP CONSTRAINT ON … is Neo4j 4's"))
        assertTrue(refused("DROP INDEX \$name").contains("parameter"))
        assertTrue(refused("CREATE INDEX FOR (m:_DrivineSchema) ON (m.version)").startsWith("_DrivineSchema is the label of Drivine's own nodes"))
        assertEquals(Reserved(UniquenessConstraintSpec("DrivineStore", "key")), translate("CREATE CONSTRAINT stamp FOR (s:DrivineStore) REQUIRE s.key IS UNIQUE"))
    }

    @Test
    fun `a schema command not well formed is refused, saying what was expected`() {
        assertEquals("Invalid schema command: expected ON, but found the end.", refused("CREATE INDEX FOR (p:Person)"))
        assertEquals("Invalid schema command: expected FOR, but found 'TO'.", refused("CREATE INDEX x TO (p:Person)"))
        assertEquals("The property q.… is of q, and the pattern binds p.", refused("CREATE INDEX FOR (p:Person) ON (q.name)"))
        assertTrue(refused("CREATE INDEX FOR (p:Person) ON (p.name); MATCH (n) RETURN n").contains("runs on its own"))
        assertTrue(refused("CREATE INDEX FOR (p:Person) ON (p.``)").contains("empty"))
    }

    @Test
    fun `an unterminated name or string is refused as such, Unicode names read as written, and a parameter where a name or options go is refused`() {
        val backtick = "Invalid schema command: A name in backticks has no closing backtick."
        assertEquals(backtick, refused("CREATE INDEX `by name FOR (p:Person) ON (p.name)"))
        assertEquals(backtick, refused("CREATE INDEX FOR (p:`Person) ON (p.name)"))
        assertEquals(backtick, refused("DROP INDEX `by name"))
        assertEquals("Invalid schema command: A string has no closing quote.", refused("CREATE INDEX FOR (p:Person) ON (p.name) OPTIONS {indexProvider: 'range}"))
        // Not DDL by its first words, so the Cypher reader is the one to refuse it.
        assertEquals(NotDdl, translate("CREATE (n:`Person {name: 'x'})"))
        assertEquals(NotDdl, translate("CREATE `index` = (a)-[:R]->(b)"))

        assertEquals(Create(RangeIndexSpec("Persön", "naïve"), "名前", false), translate("CREATE INDEX 名前 FOR (p:Persön) ON (p.naïve)"))
        assertEquals(Create(RangeIndexSpec("Ünï cødé 🙂", "🙂"), null, false), translate("CREATE INDEX FOR (p:`Ünï cødé 🙂`) ON (p.`🙂`) /* unterminated"))

        assertTrue(refused("CREATE INDEX \$name FOR (p:Person) ON (p.name)").contains("parameter"))
        assertTrue(refused("CREATE INDEX FOR (p:Person) ON (p.name) OPTIONS \$options").startsWith("OPTIONS given as a parameter"))
        assertTrue(refused("CALL db.awaitIndexes() YIELD x").contains("runs on its own"))
    }

    @Test
    fun `the SQL compilers refuse a schema command, as the translator does or as no query`() {
        // Through CypherCompile, where a connection compiles, which throws the compiler's refusal as a DrivineException.
        val compiles: List<(String) -> Any> = listOf(
            { cypher -> CypherCompile.postgres("g", cypher, emptyMap()) },
            { cypher -> CypherCompile.oracle("g", cypher, emptyMap(), { true }, { true }) },
            { cypher -> CypherCompile.sqlite("g", cypher, emptyMap()) },
        )
        compiles.forEach { compile ->
            val refused = assertThrows<DrivineException> { compile("CREATE POINT INDEX FOR (p:Place) ON (p.location)") }
            assertEquals("A POINT index is Neo4j's, and is not made on a SQL store.", refused.message)
            val command = assertThrows<DrivineException> { compile("CREATE INDEX FOR (p:Person) ON (p.name)") }
            assertTrue(command.message!!.startsWith("The statement is a schema command"), command.message)
            compile("CREATE (index:Index) RETURN index")
        }
    }

    @Test
    fun `a schema command inside a transaction is refused naming the store as it is written`() {
        val spec = QuerySpecification.withStatement(CypherStatement("CREATE INDEX FOR (p:Person) ON (p.name)"))
        mapOf(DatabaseType.POSTGRES to "Postgres", DatabaseType.ORACLE to "Oracle", DatabaseType.SQLITE to "SQLite").forEach { (type, engine) ->
            val refused = assertThrows<DrivineException> { SchemaCommands.refusedInTransaction(type, spec) }
            assertTrue("not run inside a transaction on $engine:" in refused.message!!, refused.message)
        }
        assertEquals(false, SchemaCommands.refusedInTransaction(DatabaseType.NEO4J, spec))
    }
}
