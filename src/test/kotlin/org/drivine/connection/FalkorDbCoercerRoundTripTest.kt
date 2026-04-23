package org.drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Round-trip tests for `FalkorDbConnection`'s wire-level workarounds:
 *
 * - Temporal → ISO-8601 string coercion for FalkorDB's CYPHER parameter protocol,
 *   reconstituted on the read side by Jackson's `JavaTimeModule` when mapped into typed
 *   domain objects (the path `@GraphView` and any `.transform(Class)` caller takes).
 *
 * - `$`-in-string inlining for JFalkorDB#251: any top-level String param value containing
 *   `$` is spliced into the query body as a Cypher string literal and pulled from the
 *   CYPHER prefix, sidestepping the server bug that misparses `${...}` in prefix values.
 *
 * Maps-as-parameter-values aren't tested here: JFalkorDB#68 tracks that FalkorDB doesn't
 * accept nested maps through jfalkordb at all, so those failures are a separate driver
 * limitation and not something this library can or should paper over.
 */
@Testcontainers
class FalkorDbCoercerRoundTripTest {

    companion object {
        private const val GRAPH = "coercer-round-trip"

        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkor-coercer-round-trip",
                host = container.host,
                port = container.getMappedPort(6379),
                password = null,
                graphName = GRAPH,
            )
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun teardown() {
            provider.end()
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NoteView(
        val id: String,
        val bio: String,
        val birthday: Instant
    )

    @BeforeEach
    fun cleanGraph() {
        val conn = provider.connect()
        try {
            conn.query<Any>(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        } finally {
            conn.release()
        }
    }

    @Test
    fun `Instant property round-trips into a typed domain object via Jackson`() {
        val conn = provider.connect()
        val birthday = Instant.parse("2026-04-23T05:07:53.123456Z")
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement(
                        "CREATE (:Note {id: \$id, bio: \$bio, birthday: \$birthday})"
                    )
                    .bind(mapOf(
                        "id" to "a",
                        "bio" to "plain",
                        "birthday" to birthday
                    ))
            )

            val notes = conn.query(
                QuerySpecification
                    .withStatement(
                        "MATCH (n:Note) RETURN {id: n.id, bio: n.bio, birthday: n.birthday}"
                    )
                    .transform(NoteView::class.java)
            )

            assertEquals(1, notes.size)
            assertEquals("a", notes[0].id)
            assertEquals(
                birthday,
                notes[0].birthday,
                "Jackson's JavaTimeModule should parse the ISO string back into Instant"
            )
        } finally {
            conn.release()
        }
    }

    @Test
    fun `short string with dollar-brace patterns round-trips cleanly`() {
        val conn = provider.connect()
        val bio = "owes \$5, and said \"hello \${name}\""
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Note {id: \$id, bio: \$bio})")
                    .bind(mapOf("id" to "b", "bio" to bio))
            )

            val raw = conn.query(
                QuerySpecification
                    .withStatement("MATCH (n:Note) RETURN n.bio")
                    .transform<String>()
            )
            assertEquals(bio, raw.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `dollar followed by an identifier-looking token round-trips`() {
        val conn = provider.connect()
        val bio = "the \$name arrived with \$totalAmount"
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Note {id: \$id, bio: \$bio})")
                    .bind(mapOf("id" to "c", "bio" to bio))
            )
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (n:Note {id: \$id}) RETURN n.bio")
                    .bind(mapOf("id" to "c"))
                    .transform<String>()
            )
            assertEquals(bio, out.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `string value literally shares a name with another parameter in the same query`() {
        val conn = provider.connect()
        val bio = "see \$id for details"
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Note {id: \$id, bio: \$bio})")
                    .bind(mapOf("id" to "d", "bio" to bio))
            )
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (n:Note {id: \$id}) RETURN n.bio")
                    .bind(mapOf("id" to "d"))
                    .transform<String>()
            )
            assertEquals(bio, out.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `List of strings with dollars round-trips - verifies jfalkordb list serialization`() {
        val conn = provider.connect()
        val tags = listOf("\$priority", "regular", "\$archived")
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Note {id: \$id, tags: \$tags})")
                    .bind(mapOf("id" to "g", "tags" to tags))
            )
            @Suppress("UNCHECKED_CAST")
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (n:Note {id: \$id}) RETURN n.tags")
                    .bind(mapOf("id" to "g"))
                    .transform(List::class.java)
            ) as List<List<String>>
            assertEquals(tags, out.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `long RAG-style chunk with Kotlin template syntax and quoted code samples`() {
        // Real-world reproducer from upstream: a docs chunk containing code samples with
        // `${input.name}` Kotlin template syntax. The pre-inlining workaround failed on
        // inputs of this shape; the inlining path treats the whole value as an opaque
        // string literal in the Cypher body, which FalkorDB parses correctly.
        val chunk = """
            val result = addTool.call("{\"a\": 5}")
            // val greetTool = Tool.fromFunction<GreetRequest, String>(name = "greet") { input ->
            //     "Hello ${'$'}{input.name}!"
            // }
            // Cost is ${'$'}100, and discount is ${'$'}{discount}. Multiple template vars:
            // ${'$'}{user.firstName} ${'$'}{user.lastName} earning ${'$'}{salary.amount}.
        """.trimIndent().repeat(8)  // bulk it up to the multi-KB range that triggers the bug

        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("MERGE (e:TestNode {id: \$id}) SET e.text = \$text")
                    .bind(mapOf("id" to "test-1", "text" to chunk))
            )
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (e:TestNode {id: \$id}) RETURN e.text")
                    .bind(mapOf("id" to "test-1"))
                    .transform<String>()
            )
            assertEquals(chunk, out.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `JSON-shaped string with escaped quotes triggers JFalkorDB#252 without inlining`() {
        // Upstream reproducer: a docs chunk containing HTML-escaped JSON code samples
        // like {\"rows\": 5}. jfalkordb's quoteString() escapes " without first escaping
        // \, so the CYPHER prefix ends up with \\" and FalkorDB closes the quoted string
        // at the wrong point. The inlining path puts the value in the query body as a
        // properly Cypher-escaped literal, bypassing the broken prefix escaping entirely.
        val text = """
            Example response: {\"rows\": 5, \"cols\": 3}
            Another chunk:    {\"name\": \"Ada\", \"role\": \"engineer\"}
            Mixed:            path="C:\\Users\\ada" and reply={\"ok\": true}
        """.trimIndent().repeat(6)

        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("MERGE (e:TestNode {id: \$id}) SET e.text = \$text")
                    .bind(mapOf("id" to "json-chunk", "text" to text))
            )
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (e:TestNode {id: \$id}) RETURN e.text")
                    .bind(mapOf("id" to "json-chunk"))
                    .transform<String>()
            )
            assertEquals(text, out.single())
        } finally {
            conn.release()
        }
    }

    @Test
    fun `string with embedded double quotes and backslashes is properly Cypher-escaped`() {
        // Exercises the literal-escape rules: \, ", and $ all need the right handling for
        // the inlined Cypher string literal to parse back to the original bytes.
        val bio = "path\\to\\file with \"quoted\" text and a \$dollar sign"
        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Note {id: \$id, bio: \$bio})")
                    .bind(mapOf("id" to "h", "bio" to bio))
            )
            val out = conn.query(
                QuerySpecification
                    .withStatement("MATCH (n:Note {id: \$id}) RETURN n.bio")
                    .bind(mapOf("id" to "h"))
                    .transform<String>()
            )
            assertEquals(bio, out.single())
        } finally {
            conn.release()
        }
    }
}