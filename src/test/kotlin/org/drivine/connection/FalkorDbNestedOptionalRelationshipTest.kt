package org.drivine.connection

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.session.SessionManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression test for the list-vs-single shape bug on FalkorDB:
 *
 * [CallSubqueryNestedViewProjector] builds `CALL { ... }` prologs for nested `@GraphView`
 * relationships because FalkorDB doesn't support nested pattern comprehensions
 * (FalkorDB/FalkorDB#1888). It collects each nested relationship with
 * `collect(DISTINCT CASE WHEN x IS NOT NULL THEN x { .* } END)` so OPTIONAL MATCH nulls are
 * filtered and duplicates collapsed.
 *
 * The bug: `collect(...)` always returns a list, regardless of whether the field is a
 * `List<…>` or a single nullable `Foo?`. When a nested optional-single field like
 * `Message.author: UserData?` is materialised, the deserializer gets a list and fails with
 * "Cannot deserialize ... from Array value".
 *
 * Fix: wrap `collect(...)` in `head(...)` when the nested relationship is not a collection,
 * matching the shape Neo4j's [InlineNestedViewProjector] produces. This test exercises the
 * exact pattern — a root GraphView with a `List<NestedGraphView>` where the nested view has
 * optional single relationships — which isn't covered by the Neo4j-side test suite.
 */
@Testcontainers
class FalkorDbNestedOptionalRelationshipTest {

    companion object {
        private const val GRAPH = "nested-optional"

        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var persistenceManager: NonTransactionalPersistenceManager
        private lateinit var graphObjectManager: GraphObjectManager

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun setup() {
            val subtypeRegistry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-nested-optional",
                host = container.host,
                port = container.getMappedPort(6379),
                password = null,
                graphName = GRAPH,
                subtypeRegistry = subtypeRegistry,
            )
            persistenceManager = NonTransactionalPersistenceManager(
                connectionProvider = provider,
                database = GRAPH,
                type = DatabaseType.FALKORDB,
                subtypeRegistry = subtypeRegistry,
            )
            val objectMapper: ObjectMapper = jacksonObjectMapper()
            val sessionManager = SessionManager(objectMapper)
            graphObjectManager = GraphObjectManager(persistenceManager, sessionManager, objectMapper, subtypeRegistry)
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun teardown() {
            provider.end()
        }
    }

    @NodeFragment(labels = ["NestedUser"])
    data class UserData(
        @NodeId val id: UUID,
        val name: String,
    )

    @NodeFragment(labels = ["NestedMessage"])
    data class MessageData(
        @NodeId val id: UUID,
        val body: String,
    )

    @NodeFragment(labels = ["NestedSession"])
    data class SessionData(
        @NodeId val id: UUID,
        val name: String,
    )

    @GraphView
    data class Message(
        @Root val data: MessageData,
        @GraphRelationship(type = "AUTHORED_BY", direction = Direction.OUTGOING)
        val author: UserData? = null,
        @GraphRelationship(type = "SENT_TO", direction = Direction.OUTGOING)
        val recipient: UserData? = null,
    )

    @GraphView
    data class Session(
        @Root val data: SessionData,
        @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
        val messages: List<Message> = emptyList(),
    )

    @BeforeEach
    fun cleanGraph() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (n) DETACH DELETE n")
        )
    }

    @Test
    fun `nested GraphView inside a list with an optional single relationship present`() {
        val sessionId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val authorId = UUID.randomUUID()

        seed(
            """
            CREATE (s:NestedSession {id: ${'$'}sessionId, name: 'chat'})
            CREATE (m:NestedMessage {id: ${'$'}messageId, body: 'hello'})
            CREATE (a:NestedUser {id: ${'$'}authorId, name: 'Ada'})
            CREATE (s)-[:HAS_MESSAGE]->(m)
            CREATE (m)-[:AUTHORED_BY]->(a)
            """.trimIndent(),
            mapOf(
                "sessionId" to sessionId.toString(),
                "messageId" to messageId.toString(),
                "authorId" to authorId.toString(),
            )
        )

        val loaded = graphObjectManager.loadAll(Session::class.java)

        assertEquals(1, loaded.size)
        val session = loaded.single()
        assertEquals(1, session.messages.size)
        val msg = session.messages.single()
        val author = msg.author
        assertNotNull(author, "author must materialise as a single UserData, not a list")
        assertEquals("Ada", author.name)
        assertEquals(authorId, author.id)
        assertNull(msg.recipient, "recipient has no SENT_TO edge — must be null, not empty list")
    }

    @Test
    fun `nested GraphView inside a list with an optional single relationship absent`() {
        val sessionId = UUID.randomUUID()
        val messageId = UUID.randomUUID()

        seed(
            """
            CREATE (s:NestedSession {id: ${'$'}sessionId, name: 'chat'})
            CREATE (m:NestedMessage {id: ${'$'}messageId, body: 'orphan message'})
            CREATE (s)-[:HAS_MESSAGE]->(m)
            """.trimIndent(),
            mapOf(
                "sessionId" to sessionId.toString(),
                "messageId" to messageId.toString(),
            )
        )

        val loaded = graphObjectManager.loadAll(Session::class.java)

        assertEquals(1, loaded.size)
        val msg = loaded.single().messages.single()
        assertNull(msg.author, "no AUTHORED_BY edge — author must be null, not [] or [null]")
        assertNull(msg.recipient, "no SENT_TO edge — recipient must be null")
    }

    @Test
    fun `root session with zero messages yields empty list, not a list of null-valued messages`() {
        // Repro for FalkorDB/FalkorDB#1889: when the CALL subquery's OPTIONAL MATCH for
        // messages finds nothing, FalkorDB still produces a row with messages = null.
        // Without the CASE-WHEN-IS-NOT-NULL wrap on the outer projection, collect() builds
        // a list containing a {message: {messageId: null, ...}} map and deserialization
        // blows up on any non-nullable field of MessageData.
        val sessionId = UUID.randomUUID()
        seed(
            "CREATE (:NestedSession {id: ${'$'}sessionId, name: 'empty-session'})",
            mapOf("sessionId" to sessionId.toString())
        )

        val loaded = graphObjectManager.loadAll(Session::class.java)

        assertEquals(1, loaded.size)
        assertEquals(
            emptyList(),
            loaded.single().messages,
            "absent HAS_MESSAGE edges must produce [] — not [{message: {nulls}, author: null, recipient: null}]"
        )
    }

    private fun seed(statement: String, params: Map<String, Any?>) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement(statement)
                .bind(params)
        )
    }
}