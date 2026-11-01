package org.drivine.store

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sql.DdlContention
import org.drivine.connection.DatabaseType
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.CypherStoreStamp
import org.drivine.schema.FalkorDbSchemaGrammar
import org.drivine.schema.SchemaGrammar
import org.drivine.store.StoreIdentityResolver.Stamp
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.exceptions.Neo4jException
import org.neo4j.driver.exceptions.TransientException
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import redis.clients.jedis.exceptions.JedisDataException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How [StoreIdentityResolver] stamps a store and reads the stamp back: what it sends, what it does
 * when the stamp is not there to read, and when uniqueness cannot be enforced. The store is a
 * [RecordingStore]; what two processes do at once needs a real one.
 */
class StoreIdentityResolverTest {

    private val cypherStamp = CypherStoreStamp("DrivineStore", "key")

    /** A store that speaks Cypher and has no schema management, which reads back [stamps] in turn: one list for each read. */
    private fun unguarded(vararg stamps: List<Stamp>, type: DatabaseType = DatabaseType.NEPTUNE): RecordingStore {
        var read = 0
        return RecordingStore(type, supportsSchemaManagement = false) { ran ->
            if (ran.statement == cypherStamp.readStamps) stamps[read++] else emptyList<Any?>()
        }
    }

    @Test
    fun `the store is stamped if it has no stamp, then read, and the stamp read is its identity`() {
        val store = unguarded(listOf(Stamp("store-1", "2026-01-01T00:00:00Z")))
        val before = Instant.now()

        val identity = StoreIdentityResolver(store).resolve()

        assertEquals(StoreIdentity("store-1", DatabaseType.NEPTUNE, "2026-01-01T00:00:00Z"), identity)
        val written = store.executed.single()
        assertEquals(cypherStamp.assignIfAbsent, written.statement)
        assertEquals(setOf("key", "storeId", "assignedAt", "engine"), written.parameters.keys)
        assertEquals("singleton", written.parameters["key"])
        assertEquals("NEPTUNE", written.parameters["engine"])
        // What is offered is a new id and this moment: the store keeps them only if it had no stamp.
        UUID.fromString(written.parameters["storeId"] as String)
        assertTrue(!Instant.parse(written.parameters["assignedAt"] as String).isBefore(before.minusSeconds(1)))
        assertTrue(store.schema.isEmpty(), "a store with no schema management is asked for no constraint")
    }

    @Test
    fun `of several stamps the first read is the identity, for they are read oldest first`() {
        val store = unguarded(listOf(Stamp("older", "2026-01-01T00:00:00Z"), Stamp("newer", "2026-02-01T00:00:00Z")))

        assertEquals("older", StoreIdentityResolver(store).resolve().id)
        assertEquals(1, store.executed.size, "a duplicate is reported and never deleted")
    }

    @Test
    fun `a stamp that vanished between the write and the read is written again, with a new id offered`() {
        val store = unguarded(emptyList(), listOf(Stamp("store-2", "2026-01-01T00:00:00Z")))

        assertEquals("store-2", StoreIdentityResolver(store).resolve().id)
        assertEquals(2, store.executed.size)
        assertEquals(2, store.executed.map { it.parameters["storeId"] }.distinct().size)
    }

    @Test
    fun `a stamp that is written three times and never read back is an error that names the database`() {
        val store = unguarded(emptyList(), emptyList(), emptyList())

        val failed = assertFailsWith<IllegalStateException> { StoreIdentityResolver(store).resolve() }

        assertContains(failed.message.orEmpty(), "Could not establish identity for the store at 'recording'")
        assertContains(failed.message.orEmpty(), "written 3 times and never read back")
        assertEquals(3, store.executed.size)
        assertEquals(3, store.queried.size)
    }

    @Test
    fun `without the constraint a failed write is the caller's at once, for no other writer explains it`() {
        val refused = IllegalStateException("Cannot resolve conflicting transactions")
        val store = RecordingStore(DatabaseType.NEPTUNE, supportsSchemaManagement = false) { refused }

        assertSame(refused, assertFailsWith<IllegalStateException> { StoreIdentityResolver(store).resolve() })
        assertEquals(1, store.executed.size)
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `where the store manages a schema the key is made unique before the stamp is written`() {
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            if (ran.statement == cypherStamp.readStamps) listOf(Stamp("store-3", "2026-01-01T00:00:00Z")) else emptyList<Any?>()
        }

        assertEquals("store-3", StoreIdentityResolver(store).resolve().id)
        val created = store.schema.single { it.startsWith("CREATE CONSTRAINT") }
        assertContains(created, "DrivineStore")
        assertContains(created, "key")
        assertEquals(1, store.executed.size)
    }

    @Test
    fun `a plain index on the key in the way of the constraint is replaced by it, for the label is Drivine's`() {
        val index = mapOf("name" to "store_key", "type" to "RANGE", "entityType" to "NODE", "labelsOrTypes" to listOf("DrivineStore"), "properties" to listOf("key"))
        var dropped = false
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-6", "2026-01-01T00:00:00Z"))
                ran.statement.startsWith("DROP INDEX") -> emptyList<Any?>().also { dropped = true }
                ran.statement.startsWith("CREATE CONSTRAINT") ->
                    if (dropped) emptyList<Any?>() else Neo4jException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:DrivineStore {key}).")
                ran.statement.startsWith("SHOW INDEXES") -> if (dropped) emptyList() else listOf(index)
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-6", StoreIdentityResolver(store).resolve().id)
        val dropAt = store.schema.indexOf("DROP INDEX `store_key` IF EXISTS")
        assertTrue(dropAt >= 0, "expected the index dropped: ${store.schema}")
        assertTrue(store.schema.drop(dropAt).any { it.startsWith("CREATE CONSTRAINT") }, "expected the constraint made after it: ${store.schema}")
    }

    @Test
    fun `when the constraint cannot be made the identity is still resolved, best effort`() {
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-4", "2026-01-01T00:00:00Z"))
                ran.statement == cypherStamp.assignIfAbsent -> emptyList<Any?>()
                else -> IllegalStateException("no privilege to manage the schema")
            }
        }

        assertEquals("store-4", StoreIdentityResolver(store).resolve().id)
        assertTrue(store.schema.isNotEmpty(), "the constraint was tried")
    }

    @Test
    fun `a constraint whose build failed is not taken as in force when ensure throws, so a lost race is not retried`() {
        // FalkorDB lists the failed constraint, and the recording store cannot run the native command that drops it.
        val failed = mapOf("type" to "unique", "label" to "DrivineStore", "properties" to listOf("key"), "entitytype" to "NODE", "status" to "FAILED")
        val conflict = IllegalStateException("Cannot resolve conflicting transactions")
        var merges = 0
        val store = RecordingStore(DatabaseType.FALKORDB, FalkorDbSchemaGrammar()) { ran ->
            when {
                "db.constraints()" in ran.statement -> listOf(failed)
                ran.statement == cypherStamp.assignIfAbsent -> if (merges++ == 0) conflict else emptyList<Any?>()
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-7", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }

        assertSame(conflict, assertFailsWith<IllegalStateException> { StoreIdentityResolver(store).resolve() })
        assertEquals(1, merges)
    }

    @Test
    fun `a stamp a constraint refuses is written again though the constraint was not seen made, for the refusal proves it is in force`() {
        // #473: on Neo4j, managers making the constraint at once deadlock, and a loser looking for the winner's
        // constraint may not see it yet. By the time it writes the stamp the constraint is in force, and refuses it.
        val deadlock = TransientException(
            "Neo.TransientError.Transaction.DeadlockDetected",
            "ForsetiClient can't acquire EXCLUSIVE LABEL because holders of that lock are waiting for ForsetiClient. Deadlock wait cycle.",
        )
        val refused = ClientException(
            "Neo.ClientError.Schema.ConstraintValidationFailed",
            "Node(0) already exists with label `DrivineStore` and property `key` = 'singleton'",
        )
        var merges = 0
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> deadlock
                ran.statement == cypherStamp.assignIfAbsent -> if (merges++ == 0) refused else emptyList<Any?>()
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-8", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-8", StoreIdentityResolver(store).resolve().id)
        assertEquals(2, merges, "the refused stamp is written again, and then matches the winner's")
        assertTrue(store.schema.any { it.startsWith("CREATE CONSTRAINT") }, "the constraint was tried: ${store.schema}")
    }

    @Test
    fun `a constraint whose making deadlocks with another manager's is made again, and is then in force`() {
        // #473: a manager that went on without the constraint could write a second stamp before the winner's was made.
        val deadlock = TransientException("Neo.TransientError.Transaction.DeadlockDetected", "Deadlock wait cycle.")
        val conflict = IllegalStateException("Cannot resolve conflicting transactions")
        var creates = 0
        var merges = 0
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> if (creates++ == 0) deadlock else emptyList<Any?>()
                // Only under a constraint in force is a conflict taken as a lost race, and written again.
                ran.statement == cypherStamp.assignIfAbsent -> if (merges++ == 0) conflict else emptyList<Any?>()
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-9", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-9", StoreIdentityResolver(store).resolve().id)
        assertEquals(2, creates, "the constraint is made again after the deadlock: ${store.schema}")
        assertEquals(2, merges, "the stamp is written under the constraint, so a conflict is written again")
    }

    @Test
    fun `on Oracle a constraint whose making another session's DDL deadlocks is made again`() {
        // ORA-04020 is a SQL store's DDL deadlock (DdlContention), though not a save's (OracleBackend.contended).
        val grammar = OracleSchemaGrammar(OracleGraph("g"))
        val statements = grammar.storeStamp("DrivineStore", "key")
        val deadlock = SQLException("ORA-04020: deadlock detected while trying to lock object DRIVINE.G_NODE", "61000", 4020)
        var creates = 0
        val store = RecordingStore(DatabaseType.ORACLE, ddl = grammar, graphName = "g") { ran ->
            when {
                ran.statement.startsWith("CREATE UNIQUE INDEX") -> if (creates++ == 0) deadlock else emptyList<Any?>()
                ran.statement == statements.readStamps -> listOf(Stamp("store-10", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-10", StoreIdentityResolver(store).resolve().id)
        assertEquals(2, creates, "the constraint is made again after the deadlock: ${store.schema}")
    }

    @Test
    fun `a deadlock run as many times as allowed is not run again as made meanwhile, so retries don't multiply`() {
        // #453: a failure whenNotContended gave up on is run again by no retry outside it, though it looks made meanwhile.
        val grammar = OracleSchemaGrammar(OracleGraph("g"))
        val statements = grammar.storeStamp("DrivineStore", "key")
        val madeMeanwhile = SQLException("ORA-00955: name is already used by an existing object", "42000", 955)
        var creates = 0
        val store = RecordingStore(DatabaseType.ORACLE, ddl = grammar, graphName = "g") { ran ->
            when {
                ran.statement.startsWith("CREATE UNIQUE INDEX") ->
                    SQLException("ORA-04020: deadlock detected while trying to lock object DRIVINE.G_NODE", "61000", 4020, madeMeanwhile).also { creates++ }
                ran.statement == statements.readStamps -> listOf(Stamp("store-12", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-12", StoreIdentityResolver(store).resolve().id)
        assertEquals(DdlContention.ATTEMPTS, creates, "run as often as whenNotContended runs it, and no more: ${store.schema}")
    }

    @Test
    fun `a stamp refused for a reason that is not the constraint is not written again`() {
        val deadlock = TransientException("Neo.TransientError.Transaction.DeadlockDetected", "Deadlock wait cycle.")
        val refused = ClientException("Neo.ClientError.Statement.SyntaxError", "Invalid input")
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> deadlock
                ran.statement == cypherStamp.assignIfAbsent -> refused
                else -> emptyList<Any?>()
            }
        }

        assertSame(refused, assertFailsWith<ClientException> { StoreIdentityResolver(store).resolve() })
        assertEquals(1, store.executed.size)
    }

    /**
     * A FalkorDB store where another manager is making the key's constraint: its backing index is already there, so
     * each `CREATE INDEX` is refused as "already indexed", and its constraint is listed from the [listedFrom]th look
     * on; never, if null. The first stamp written conflicts with another write, which is written again only under a
     * constraint confirmed, so [Race.merges] says whether the resolver went on under one.
     */
    private class Race(listedFrom: Int?) {
        var looks = 0
        var indexCreates = 0
        var merges = 0
        private val stamp = CypherStoreStamp("DrivineStore", "key")
        private val constraint = mapOf("type" to "unique", "label" to "DrivineStore", "properties" to listOf("key"), "entitytype" to "NODE", "status" to "OPERATIONAL")
        val store = RecordingStore(DatabaseType.FALKORDB, FalkorDbSchemaGrammar()) { ran ->
            when {
                "db.constraints()" in ran.statement -> if (++looks >= (listedFrom ?: Int.MAX_VALUE)) listOf(constraint) else emptyList<Any?>()
                ran.statement.startsWith("CREATE INDEX") -> JedisDataException("Attribute 'key' is already indexed").also { indexCreates++ }
                ran.statement == stamp.assignIfAbsent ->
                    if (merges++ == 0) IllegalStateException("Cannot resolve conflicting transactions") else emptyList<Any?>()
                ran.statement == stamp.readStamps -> listOf(Stamp("store-11", "2026-01-01T00:00:00Z"))
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `a constraint refused for what another manager made meanwhile is ensured again, and found, so the stamp is written under it`() {
        // liberation-data/drivine#489: the loser looks for the winner's constraint before the winner has made it,
        // having made only its index. It must look again, rather than go on without the constraint, best effort.
        val race = Race(listedFrom = 3)

        assertEquals("store-11", StoreIdentityResolver(race.store).resolve().id)
        assertEquals(2, race.indexCreates, "the constraint is ensured again after the refusal: ${race.store.schema}")
        assertEquals(2, race.merges, "the stamp is written under the constraint, so a conflict is written again")
    }

    @Test
    fun `a constraint refused each time for what another manager made is ensured three times at most, then best effort`() {
        val race = Race(listedFrom = null)

        assertFailsWith<IllegalStateException> { StoreIdentityResolver(race.store).resolve() }
        assertEquals(3, race.indexCreates, "ensured three times, and no more: ${race.store.schema}")
        assertEquals(1, race.merges, "without the constraint confirmed, a conflict is not taken for a lost race")
    }

    /** A `23505` as the Postgres driver gives it, with the schema and constraint the server names in their own fields. */
    private fun postgresUniqueViolation(
        schema: String,
        constraint: String,
        message: String = "duplicate key value violates unique constraint \"$constraint\"",
    ) = PSQLException(ServerErrorMessage(listOf("SERROR", "C23505", "M$message", "s$schema", "n$constraint").joinToString("\u0000", postfix = "\u0000")))

    @Test
    fun `only a refusal for what another session made is taken as made meanwhile`() {
        val falkor = FalkorDbSchemaGrammar()
        val neo4j = CypherDialect.NEO4J_5.schemaGrammar()
        val postgres = PostgresSchemaGrammar(PostgresGraph("g"))
        val oracle = OracleSchemaGrammar(OracleGraph("g"))
        fun made(e: Throwable, grammar: SchemaGrammar) = StoreIdentityResolver.madeMeanwhile(e, grammar)

        assertTrue(made(RuntimeException(JedisDataException("Attribute 'key' is already indexed")), falkor))
        assertTrue(made(JedisDataException("Constraint already exists."), falkor))
        assertTrue(made(RuntimeException(postgresUniqueViolation("pg_catalog", "pg_class_relname_nsp_index")), postgres))
        // Known by the fields the server sends, not by its message, which is in the server's language (lc_messages).
        assertTrue(made(postgresUniqueViolation("pg_catalog", "pg_class_relname_nsp_index", message = "doppelter Schlüsselwert verletzt Unique-Constraint »pg_class_relname_nsp_index«"), postgres))
        assertTrue(made(SQLException("ORA-00955: name is already used by an existing object", "42000", 955), oracle))

        // A key that repeats in a graph's rows is a violation, though its detail says "already exists", and though
        // the graph's name, and so its index's, starts as the catalog's constraints' names do.
        assertFalse(made(postgresUniqueViolation("public", "g_uq_drivinestorekey"), postgres))
        assertFalse(made(postgresUniqueViolation("public", "pg_store_uq_DrivineStorekey_1a2b"), postgres))
        assertFalse(made(SQLException("ERROR: duplicate key value violates unique constraint \"pg_store_uq_DrivineStorekey_1a2b\"\n  Detail: Key (singleton) already exists.", "23505"), postgres))
        // An index in the way of the constraint, which ensure replaces itself.
        assertFalse(made(ClientException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:DrivineStore {key})."), neo4j))
        assertFalse(made(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "Deadlock wait cycle."), neo4j))
        assertFalse(made(IllegalStateException("no privilege to manage the schema"), falkor))
    }

    @Test
    fun `on Oracle the graph is made first, on a schema connection, and the stamp is written and read in SQL`() {
        val graph = OracleGraph("g")
        val grammar = OracleSchemaGrammar(graph)
        val statements = grammar.storeStamp("DrivineStore", "key")
        val store = RecordingStore(DatabaseType.ORACLE, ddl = grammar, graphName = "g", supportsSchemaManagement = false) { ran ->
            if (ran.statement == statements.readStamps) listOf(Stamp("store-5", "2026-01-01T00:00:00Z")) else emptyList<Any?>()
        }

        val identity = StoreIdentityResolver(store).resolve()

        assertEquals(StoreIdentity("store-5", DatabaseType.ORACLE, "2026-01-01T00:00:00Z"), identity)
        assertEquals(listOf(graph.ensuring), store.schema)
        assertEquals(statements.assignIfAbsent, store.executed.single().statement)
        assertEquals("ORACLE", store.executed.single().parameters["engine"])
    }

    @Test
    fun `a manager with no schema grammar whose store speaks no Cypher is sent nothing, and told why`() {
        val store = RecordingStore(DatabaseType.ORACLE, schemaGrammar = null, supportsSchemaManagement = false)

        val refused = assertFailsWith<DrivineException> { StoreIdentityResolver(store).resolve() }

        assertContains(refused.message.orEmpty(), "ORACLE does not speak Cypher, and this PersistenceManager has no schema grammar to write the store's identity with.")
        assertTrue(store.executed.isEmpty() && store.queried.isEmpty() && store.schema.isEmpty())
    }
}
