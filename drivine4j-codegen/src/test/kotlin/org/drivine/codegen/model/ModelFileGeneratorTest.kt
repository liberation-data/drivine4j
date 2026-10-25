package org.drivine.codegen.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.drivine.codegen.GraphViewProcessorProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the processor over small domains and reads the model file it writes. The sample module's
 * `.drivine/model.json` covers the common shapes; these cover what the sample does not declare.
 */
@OptIn(ExperimentalCompilerApi::class)
class ModelFileGeneratorTest {

    @TempDir
    lateinit var dir: File

    private val kotlinDomain = SourceFile.kotlin(
        "Domain.kt",
        """
        package fx

        import com.fasterxml.jackson.annotation.JsonSubTypes
        import org.drivine.annotation.*
        import java.time.Instant
        import java.time.LocalDate

        enum class Role { Admin, Author }

        data class Address(val city: String)

        @NodeFragment(labels = ["Signal"])
        interface Signal {
            @get:NodeId val id: String
        }

        @NodeFragment(labels = ["Email"])
        data class EmailSignal(override val id: String, val subject: String) : Signal

        @NodeFragment(labels = ["Member"])
        @Unique(properties = ["tenantId", "userId"])
        @RangeIndex(properties = ["joined"])
        data class Member(
            @NodeId val id: String,
            val tenantId: String,
            val userId: String,
            val joined: Instant,
            @TextIndex val handle: String,
            @Default val score: Int = 0,
            @EmptyWhenAbsent val tags: Set<String> = emptySet(),
            @JsonPacked val address: Address? = null,
            val counts: Map<String, Int> = emptyMap(),
            val vector: FloatArray? = null,
            @NodeLabels val roles: Set<Role> = emptySet(),
            @PropertyBag(prefix = "meta", delimiter = "_") val meta: Map<String, String> = emptyMap(),
            @PropertyBag(flat = true) val rest: Map<String, Any?> = emptyMap(),
            @GraphTransient val cache: String? = null,
            @field:GraphTransient val scratch: String? = null,
        )

        @NodeFragment(labels = ["Tagged"])
        data class Tagged(@NodeId val id: String, @NodeLabels val labels: Set<String> = emptySet())

        @NodeFragment(labels = ["Animal"])
        open class Animal(@NodeId open val id: String)

        @NodeFragment(labels = ["Shape"])
        @JsonSubTypes(JsonSubTypes.Type(Circle::class))
        abstract class Shape {
            @NodeId abstract val id: String
        }

        @NodeFragment(labels = ["Circle"])
        data class Circle(override val id: String, val radius: Double) : Shape()

        @RelationshipFragment
        data class Membership(val since: LocalDate, val member: Member)

        @GraphView
        data class Team(
            val lead: Member,
            @GraphRelationship(type = "HAS", direction = Direction.INCOMING)
            @SortedBy("member.handle", ascending = false)
            val memberships: List<Membership>,
            @GraphRelationship(type = "REPORTS_TO", maxDepth = 3)
            val chain: List<Member>,
            @ReadOnly @GraphRelationship(type = "MENTORS")
            val mentee: Member?,
            @GraphPath(hops = [Hop(type = "A"), Hop(type = "B", direction = Direction.INCOMING, label = "Mid", orderBy = "at", ascending = false)])
            val far: List<Member>,
            @Count(type = "HAS") val memberCount: Long,
            @Aggregate(function = AggregateFunction.MAX, type = "HAS", property = "score") val topScore: Int?,
        )

        @GraphView
        data class Tree(
            @Root val node: Member,
            @GraphRelationship(type = "CHILD", maxDepth = 2) val children: List<Tree>,
        )
        """.trimIndent(),
    )

    private val javaNullable = SourceFile.java(
        "Nullable.java",
        """
        package jx;

        public @interface Nullable {}
        """.trimIndent(),
    )

    private val javaTicket = SourceFile.java(
        "Ticket.java",
        """
        package jx;

        import org.drivine.annotation.*;

        @NodeFragment(labels = {"Ticket"})
        public class Ticket {
            @NodeId public String id;
            public int priority;
            @Nullable public String note;
        }
        """.trimIndent(),
    )

    private val javaView = SourceFile.java(
        "TicketView.java",
        """
        package jx;

        import org.drivine.annotation.*;

        @GraphView
        public final class TicketView {
            @Root public Ticket ticket;
            @GraphRelationship(type = "OWNS", direction = Direction.INCOMING) public Ticket parent;
        }
        """.trimIndent(),
    )

    @Test
    fun `fragments carry what the sample does not declare`() {
        val model = generate(kotlinDomain)
        val member = fragment(model, "fx.Member")

        assertEquals(listOf("Member"), member["labels"].map { it.asText() })
        assertEquals("id", member["nodeId"].asText())
        assertEquals("""{"field":"roles","values":["Admin","Author"]}""", member["labelSet"].toString())
        assertEquals(
            """[{"field":"meta","prefix":"meta_","type":"string"},{"field":"rest","prefix":"","flat":true,"type":"any"}]""",
            member["bags"].toString(),
        )
        assertEquals("""[{"kind":"unique","properties":["tenantId","userId"]}]""", member["indexes"].toString())

        val properties = member["properties"].associateBy { it["name"].asText() }
        // Transient fields, bags and the labels field are not properties.
        assertEquals(
            listOf("address", "counts", "handle", "id", "joined", "score", "tags", "tenantId", "userId", "vector"),
            member["properties"].map { it["name"].asText() },
        )
        assertEquals("range", properties.getValue("joined")["index"].asText())
        assertEquals("datetime", properties.getValue("joined")["type"].asText())
        assertEquals("text", properties.getValue("handle")["index"].asText())
        assertEquals("default", properties.getValue("score")["whenAbsent"].asText())
        assertEquals("empty", properties.getValue("tags")["whenAbsent"].asText())
        assertEquals("""{"name":"tags","stored":"tags","type":"list","of":"string","nullable":false,"whenAbsent":"empty","source":"kotlin.collections.Set<kotlin.String>"}""",
            properties.getValue("tags").toString())
        assertEquals("""{"name":"address","stored":"address","type":"json","nullable":true,"packed":"json","source":"fx.Address"}""",
            properties.getValue("address").toString())
        assertEquals("""{"name":"counts","stored":"counts","type":"map","of":"int","nullable":false,"source":"kotlin.collections.Map<kotlin.String, kotlin.Int>"}""",
            properties.getValue("counts").toString())
        assertEquals("float", properties.getValue("vector")["of"].asText())

        // An open set of labels has no values to list.
        assertEquals("""{"field":"labels"}""", fragment(model, "fx.Tagged")["labelSet"].toString())
    }

    @Test
    fun `labels and the node id are inherited, and subtypes are listed when the build can know them`() {
        val model = generate(kotlinDomain)

        val email = fragment(model, "fx.EmailSignal")
        assertEquals(listOf("Email", "Signal"), email["labels"].map { it.asText() })
        assertEquals("id", email["nodeId"].asText())

        // An interface may have subtypes registered in code.
        assertEquals(false, fragment(model, "fx.Signal")["subtypesComplete"].asBoolean())
        assertEquals(false, fragment(model, "fx.Animal")["subtypesComplete"].asBoolean())
        assertFalse(fragment(model, "fx.Animal").has("subtypes"))

        val shape = fragment(model, "fx.Shape")
        assertEquals(listOf("fx.Circle"), shape["subtypes"].map { it.asText() })
        assertFalse(shape.has("subtypesComplete"))

        val circle = fragment(model, "fx.Circle")
        assertEquals(listOf("Circle", "Shape"), circle["labels"].map { it.asText() })
        assertEquals("id", circle["nodeId"].asText())
        assertFalse(circle.has("subtypesComplete"))
    }

    @Test
    fun `views carry paths, depth, sorting, read-only fields and aggregates`() {
        val model = generate(kotlinDomain)
        val team = view(model, "fx.Team")

        // A Kotlin view's root may be the one fragment no annotation maps.
        assertEquals("""{"field":"lead","fragment":"fx.Member"}""", team["root"].toString())
        val relationships = team["relationships"].associateBy { it["field"].asText() }
        assertEquals(listOf("chain", "far", "memberships", "mentee"), team["relationships"].map { it["field"].asText() })
        assertEquals(
            """{"field":"memberships","type":"HAS","direction":"INCOMING","target":"fx.Membership","many":true,"required":false,"sortedBy":{"property":"member.handle","ascending":false}}""",
            relationships.getValue("memberships").toString(),
        )
        // A list read over several hops is never written.
        assertEquals(
            """{"field":"chain","type":"REPORTS_TO","direction":"OUTGOING","target":"fx.Member","many":true,"required":false,"maxDepth":3,"readOnly":true}""",
            relationships.getValue("chain").toString(),
        )
        assertEquals(true, relationships.getValue("mentee")["readOnly"].asBoolean())
        assertEquals(false, relationships.getValue("mentee")["required"].asBoolean())
        assertEquals(
            """{"field":"far","target":"fx.Member","many":true,"required":false,"readOnly":true,"path":[{"type":"A","direction":"OUTGOING"},{"type":"B","direction":"INCOMING","label":"Mid","orderBy":"at","ascending":false}]}""",
            relationships.getValue("far").toString(),
        )
        assertEquals(
            """[{"field":"memberCount","function":"COUNT","type":"HAS","direction":"OUTGOING"},{"field":"topScore","function":"MAX","type":"HAS","direction":"OUTGOING","property":"score"}]""",
            team["aggregates"].toString(),
        )

        // A view nested in itself is written level by level, so its depth does not make it read-only.
        val children = view(model, "fx.Tree")["relationships"].single()
        assertEquals(2, children["maxDepth"].asInt())
        assertFalse(children.has("readOnly"))

        assertEquals(
            """{"id":"fx.Membership","name":"Membership","target":{"field":"member","fragment":"fx.Member"},"properties":[{"name":"since","stored":"since","type":"date","nullable":false,"source":"java.time.LocalDate"}]}""",
            model["relationshipFragments"].single().toString(),
        )
    }

    @Test
    fun `what the core library ignores, the file leaves out`() {
        val model = generate(
            SourceFile.kotlin(
                "Board.kt",
                """
                package gx

                import org.drivine.annotation.*

                @NodeFragment(labels = ["Named"])
                interface Named {
                    @get:NodeId val id: String
                }

                @NodeFragment(labels = ["Base"])
                abstract class Base

                @NodeFragment(labels = ["Leaf"])
                class Leaf(override val id: String) : Named, Base()

                @RelationshipFragment
                data class Link(val weight: Int, val leaf: Leaf)

                @GraphView
                data class Board(
                    val leaf: Leaf,
                    @GraphRelationship(type = "LINKS", maxDepth = 3) val links: List<Link>,
                    @SortedBy("id") @GraphPath(hops = [Hop(type = "A"), Hop(type = "B")]) val far: List<Leaf>,
                    @GraphRelationship(type = "C") @GraphPath(hops = [Hop(type = "A"), Hop(type = "B")]) val both: List<Leaf>,
                )
                """.trimIndent(),
            ),
        )

        // The superclass's labels before the interfaces', whichever the class lists first.
        assertEquals(listOf("Leaf", "Base", "Named"), fragment(model, "gx.Leaf")["labels"].map { it.asText() })

        val relationships = view(model, "gx.Board")["relationships"].associateBy { it["field"].asText() }
        // A relationship fragment is read one hop away, whatever maxDepth says.
        assertEquals(
            """{"field":"links","type":"LINKS","direction":"OUTGOING","target":"gx.Link","many":true,"required":false}""",
            relationships.getValue("links").toString(),
        )
        // A path is not sorted once read, and is a path even with a @GraphRelationship beside it.
        val path = """"target":"gx.Leaf","many":true,"required":false,"readOnly":true,"path":[{"type":"A","direction":"OUTGOING"},{"type":"B","direction":"OUTGOING"}]}"""
        assertEquals("""{"field":"far",$path""", relationships.getValue("far").toString())
        assertEquals("""{"field":"both",$path""", relationships.getValue("both").toString())
    }

    @Test
    fun `a Kotlin fragment takes a Java base's private fields and @NodeId, and not a Kotlin base's private ones`() {
        val model = generate(
            SourceFile.java(
                "JavaBase.java",
                """
                package kx;

                import org.drivine.annotation.*;

                public abstract class JavaBase {
                    @NodeId private String id;
                    private String createdOn;
                    @GraphTransient private String cache;
                    private static final String KIND = "base";
                    public String getId() { return id; }
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Over.kt",
                """
                package kx

                import org.drivine.annotation.*

                @NodeFragment(labels = ["OverJava"])
                class OverJava : JavaBase() { var title: String = "" }

                abstract class KotlinBase { @NodeId val key: String = ""; private val secret: String = "" }

                @NodeFragment(labels = ["OverKotlin"])
                class OverKotlin : KotlinBase() { var title: String = "" }
                """.trimIndent(),
            ),
        )

        val overJava = fragment(model, "kx.OverJava")
        assertEquals("id", overJava["nodeId"].asText())
        assertEquals(setOf("createdOn", "id", "title"), overJava["properties"].map { it["name"].asText() }.toSet())
        // Kotlin's reflection leaves a Kotlin superclass's private property out, and so does the file.
        val overKotlin = fragment(model, "kx.OverKotlin")
        assertEquals("key", overKotlin["nodeId"].asText())
        assertEquals(setOf("key", "title"), overKotlin["properties"].map { it["name"].asText() }.toSet())
    }

    @Test
    fun `a Java domain says where it cannot tell null`() {
        val model = generate(javaNullable, javaTicket, javaView)
        assertEquals("java", model["generator"]["language"].asText())

        val ticket = fragment(model, "jx.Ticket")
        val properties = ticket["properties"].associateBy { it["name"].asText() }
        assertEquals("unknown", properties.getValue("id")["nullable"].asText())
        assertEquals("int", properties.getValue("priority")["type"].asText())
        assertEquals(true, properties.getValue("note")["nullable"].asBoolean())
        // A Java class that is not final may be subclassed and registered in code.
        assertEquals(false, ticket["subtypesComplete"].asBoolean())

        val parent = view(model, "jx.TicketView")["relationships"].single()
        assertEquals("""{"field":"parent","type":"OWNS","direction":"INCOMING","target":"jx.Ticket","many":false,"required":false}""", parent.toString())
    }

    @Test
    fun `the file is the same each time, and left alone when nothing changed`() {
        val file = File(dir, "first/.drivine/model.json")
        generate(kotlinDomain, file = file)
        val first = file.readText()
        assertTrue(first.startsWith("{\n  \"\$comment\": \"Drivine model file: "))
        assertTrue(first.endsWith("}\n"))

        file.setLastModified(0)
        generate(kotlinDomain, file = file, workDir = File(dir, "again"))
        assertEquals(first, file.readText())
        assertEquals(0, file.lastModified())
    }

    @Test
    fun `the switch turns it off`() {
        val file = File(dir, ".drivine/model.json")
        compile(listOf(kotlinDomain), mapOf(ModelFiles.ENABLED to "false", ModelFiles.FILE to file.path), File(dir, "work"))
        assertFalse(file.exists())
    }

    @Test
    fun `the module's file is written for the main source set only`() {
        val main = ModelFileGenerator.gradleLayout(File("/repo/app/build/generated/ksp/main/resources/META-INF/drivine/model.json"))
        assertEquals(ModelFileGenerator.Companion.GradleLayout(File("/repo/app"), "main"), main)
        assertEquals("test", ModelFileGenerator.gradleLayout(File("/repo/app/build/generated/ksp/test/resources/META-INF/drivine/model.json"))?.sourceSet)
        assertNull(ModelFileGenerator.gradleLayout(File("/repo/app/target/classes/META-INF/drivine/model.json")))
    }

    @Test
    fun `with no file named, the module is the build file's directory, and the jar's copy is the one written`() {
        File(dir, "build.gradle").writeText("")
        val workDir = File(dir, "work")
        val result = compile(listOf(kotlinDomain), emptyMap(), workDir)

        val resource = workDir.walk().single { it.invariantSeparatorsPath.endsWith("META-INF/drivine/model.json") }
        val model = ObjectMapper().readTree(resource)
        assertEquals(dir.name, model["module"].asText())
        // The version is the build's, put in the processor's resources.
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(model["generator"]["version"].asText()), model["generator"].toString())
        assertTrue(result.messages.contains("Set the KSP option ${ModelFiles.FILE}"), result.messages)
        assertFalse(File(dir, ".drivine").exists())
    }

    @Test
    fun `a file named by a relative path is refused`() {
        val result = compile(listOf(kotlinDomain), mapOf(ModelFiles.FILE to "model.json"), File(dir, "work"), ExitCode.COMPILATION_ERROR)
        assertTrue(result.messages.contains("${ModelFiles.FILE} must be an absolute path"), result.messages)
    }

    private fun shop(withOrders: Boolean) = SourceFile.kotlin(
        "Shop.kt",
        """
        package shop

        import org.drivine.annotation.*

        @NodeFragment(labels = ["Customer"])
        data class Customer(@NodeId val id: String, val name: String)
        """.trimIndent() + if (!withOrders) "" else """

        @NodeFragment(labels = ["Order"])
        data class Order(@NodeId val id: String, val total: Long)

        @RelationshipFragment
        data class Placed(val on: String, val order: Order)

        @GraphView
        data class CustomerOrders(
            @Root val customer: Customer,
            @GraphRelationship(type = "PLACED") val orders: List<Placed>,
        )
        """.trimIndent(),
    )

    private val nothing = SourceFile.kotlin("Nothing.kt", "package shop\n\nobject Nothing\n")

    /** A source with nothing to describe, in [sourceSet] of Gradle's and Maven's layout under [dir]. */
    private fun nothingIn(sourceSet: String) = SourceFile.fromPath(
        File(dir, "app/src/$sourceSet/kotlin/shop/Nothing.kt").apply { parentFile.mkdirs(); writeText("package shop\n\nobject Nothing\n") },
    )

    @Test
    fun `what the code no longer declares stays in the file, marked removed, until it comes back`() {
        val file = File(dir, ".drivine/model.json")
        generate(shop(withOrders = true), file = file, workDir = File(dir, "one"))
        val before = file.readText()
        val beforeModel = ObjectMapper().readTree(before)

        val model = generate(shop(withOrders = false), file = file, workDir = File(dir, "two"))
        assertEquals(listOf("shop.Customer", "shop.Order"), model["fragments"].map { it["id"].asText() })
        assertFalse(fragment(model, "shop.Customer").has("removed"))
        // A removed entry is the one the file had, with the mark after its name.
        val order = fragment(model, "shop.Order")
        assertEquals(listOf("id", "name", "removed", "labels", "nodeId", "properties"), order.fieldNames().asSequence().toList())
        assertEquals(fragment(beforeModel, "shop.Order"), (order.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("removed") })
        assertEquals(true, view(model, "shop.CustomerOrders")["removed"].asBoolean())
        assertEquals(true, model["relationshipFragments"].single()["removed"].asBoolean())

        // Written again with nothing changed, it is the same file.
        val removed = file.readText()
        generate(shop(withOrders = false), file = file, workDir = File(dir, "three"))
        assertEquals(removed, file.readText())

        // Back in the code, it is written from the code again.
        generate(shop(withOrders = true), file = file, workDir = File(dir, "four"))
        assertEquals(before, file.readText())
    }

    @Test
    fun `a module with nothing left keeps its file, everything in it removed`() {
        val file = File(dir, ".drivine/model.json")
        generate(shop(withOrders = true), file = file, workDir = File(dir, "one"))
        val model = generate(nothingIn("main"), file = file, workDir = File(dir, "two"))
        val entries = model["fragments"] + model["relationshipFragments"] + model["views"]
        assertEquals(4, entries.size)
        assertTrue(entries.all { it["removed"].asBoolean() })
    }

    @Test
    fun `a test source set with nothing declared leaves the main file alone`() {
        val file = File(dir, ".drivine/model.json")
        generate(shop(withOrders = true), file = file, workDir = File(dir, "one"))
        val before = file.readText()
        compile(listOf(nothingIn("test")), mapOf(ModelFiles.FILE to file.path), File(dir, "two"))
        assertEquals(before, file.readText())
    }

    @Test
    fun `a Java module with nothing left is still a Java one`() {
        val file = File(dir, ".drivine/model.json")
        generate(javaNullable, javaTicket, javaView, file = file, workDir = File(dir, "one"))
        val model = generate(nothingIn("main"), file = file, workDir = File(dir, "two"))
        assertEquals("java", model["generator"]["language"].asText())
    }

    @Test
    fun `a deferred entry is kept as it was, a removed one marked once, all sorted by id`() {
        val entries = ModelFiles.withRemoved(
            current = listOf(linkedMapOf("id" to "b.Current", "name" to "Current")),
            previous = listOf(
                linkedMapOf("id" to "c.Gone", "name" to "Gone", "removed" to true, "labels" to listOf("Gone")),
                linkedMapOf("id" to "a.Deferred", "name" to "Deferred", "labels" to listOf("Deferred")),
                linkedMapOf("id" to "b.Current", "name" to "Old"),
                linkedMapOf("id" to "d.Nameless"),
            ),
            deferred = setOf("a.Deferred"),
        )
        assertEquals(listOf("a.Deferred", "b.Current", "c.Gone", "d.Nameless"), entries.map { it["id"] })
        assertEquals(linkedMapOf("id" to "a.Deferred", "name" to "Deferred", "labels" to listOf("Deferred")), entries[0])
        assertEquals("Current", entries[1]["name"])
        assertEquals(listOf("id", "name", "removed", "labels"), entries[2].keys.toList())
        assertEquals(listOf("id", "removed"), entries[3].keys.toList())
    }

    @Test
    fun `a module that never declared anything writes no file`() {
        val file = File(dir, ".drivine/model.json")
        compile(listOf(nothing), mapOf(ModelFiles.FILE to file.path), File(dir, "work"))
        assertFalse(file.exists())
    }

    @Test
    fun `a file of another format is written over, keeping nothing`() {
        val file = File(dir, ".drivine/model.json").apply { parentFile.mkdirs(); writeText("""{"format": "drivine-model/2", "fragments": [{"id": "x.Old"}]}""") }
        val result = compile(listOf(shop(withOrders = false)), mapOf(ModelFiles.FILE to file.path), File(dir, "work"))
        assertTrue(result.messages.contains("is not a drivine-model/1 file"), result.messages)
        assertEquals(listOf("shop.Customer"), ObjectMapper().readTree(file)["fragments"].map { it["id"].asText() })
    }

    @Test
    fun `the module's file is found from its main sources`() {
        assertEquals(File("/repo/app/.drivine/model.json"), ModelFileGenerator.mainModuleFile(File("/repo/app/src/main/kotlin/a/B.kt")))
        assertNull(ModelFileGenerator.mainModuleFile(File("/repo/app/src/test/kotlin/a/B.kt")))
    }

    private fun generate(vararg sources: SourceFile, file: File = File(dir, "model.json"), workDir: File = File(dir, "work")): JsonNode {
        compile(sources.toList(), mapOf(ModelFiles.FILE to file.path, ModelFiles.MODULE to "fx"), workDir)
        return ObjectMapper().readTree(file)
    }

    private fun compile(
        sources: List<SourceFile>,
        options: Map<String, String>,
        workDir: File,
        exitCode: ExitCode = ExitCode.OK,
    ): JvmCompilationResult {
        val result = KotlinCompilation().apply {
            this.sources = sources
            workingDir = workDir
            inheritClassPath = true
            messageOutputStream = System.out
            // The generated query DSL uses context parameters, as a module using it enables them.
            kotlincArguments = listOf("-Xcontext-parameters")
            useKsp2()
            configureKsp {
                symbolProcessorProviders += GraphViewProcessorProvider()
                processorOptions.putAll(options)
            }
        }.compile()
        assertEquals(exitCode, result.exitCode, result.messages)
        return result
    }

    private fun fragment(model: JsonNode, id: String): JsonNode = model["fragments"].single { it["id"].asText() == id }

    private fun view(model: JsonNode, id: String): JsonNode = model["views"].single { it["id"].asText() == id }
}
