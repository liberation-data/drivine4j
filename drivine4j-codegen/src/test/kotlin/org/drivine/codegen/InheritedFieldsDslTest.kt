package org.drivine.codegen

import com.google.testing.compile.Compilation
import com.google.testing.compile.Compiler
import com.google.testing.compile.JavaFileObjects
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.drivine.codegen.java.GraphViewProcessor as JavaProcessor

/**
 * The generated DSL has a reference for each property the node stores, as the core library reads
 * them: those a fragment inherits, a Java superclass's private fields among them, but not a Kotlin
 * superclass's private properties, which Kotlin's reflection leaves out, and no `@GraphTransient` one.
 */
@OptIn(ExperimentalCompilerApi::class)
class InheritedFieldsDslTest {

    @TempDir
    lateinit var dir: File

    private fun generated(vararg sources: SourceFile): Map<String, String> {
        val result = KotlinCompilation().apply {
            this.sources = sources.toList()
            workingDir = dir
            inheritClassPath = true
            kotlincArguments = listOf("-Xcontext-parameters")
            useKsp2()
            configureKsp { symbolProcessorProviders += GraphViewProcessorProvider() }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return dir.walk().filter { it.extension == "kt" && "ksp" in it.path }.associate { it.name to it.readText() }
    }

    /** The class [name] declares in [files], from its header to the next top-level declaration. */
    private fun classOf(files: Map<String, String>, name: String): String {
        val text = files.values.first { "class $name" in it }
        val start = text.indexOf("class $name")
        val end = Regex("\n(public )?(class|object|fun|val|inline|context)").find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    @Test
    fun `a Java fragment's DSL references the fields it inherits, a private id among them`() {
        val files = generated(
            SourceFile.java(
                "Base.java",
                """
                package inh;
                import org.drivine.annotation.*;
                public abstract class Base {
                    public static final String KIND = "base";
                    @NodeId private String id;
                    protected String shared;
                    @GraphTransient public String cache;
                }
                """.trimIndent(),
            ),
            SourceFile.java(
                "Probe.java",
                """
                package inh;
                import org.drivine.annotation.*;
                @NodeFragment(labels = {"Probe"})
                public class Probe extends Base {
                    private String code;
                    @GraphTransient public String scratch;
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "ProbeView.kt",
                """
                package inh
                import org.drivine.annotation.*
                @GraphView data class ProbeView(@Root val probe: Probe)
                """.trimIndent(),
            ),
        )

        for (dsl in listOf("ProbeQueryDsl", "ProbeProperties")) {
            val code = classOf(files, dsl)
            for (stored in listOf("\"id\"", "\"shared\"", "\"code\"")) assertTrue(stored in code, "$dsl lacks $stored:\n$code")
            for (not in listOf("KIND", "cache", "scratch")) assertFalse(not in code, "$dsl has $not:\n$code")
        }
    }

    @Test
    fun `a Kotlin fragment's DSL references its superclass's properties but not its private ones`() {
        val files = generated(
            SourceFile.kotlin(
                "Kinds.kt",
                """
                package inh
                import org.drivine.annotation.*
                abstract class Named(@NodeId val key: String, val title: String) {
                    private val secret: String = "x"
                    @GraphTransient var scratch: String? = null
                }
                @NodeFragment(labels = ["Tag"]) class Tag(key: String, title: String, val colour: String) : Named(key, title)
                @GraphView data class TagView(@Root val tag: Tag)
                """.trimIndent(),
            ),
        )

        for (dsl in listOf("TagQueryDsl", "TagProperties")) {
            val code = classOf(files, dsl)
            for (stored in listOf("\"key\"", "\"title\"", "\"colour\"")) assertTrue(stored in code, "$dsl lacks $stored:\n$code")
            for (not in listOf("secret", "scratch")) assertFalse(not in code, "$dsl has $not:\n$code")
        }
    }

    @Test
    fun `an inherited field's annotations shape its reference, and a field hiding one in a base is the one read`() {
        val files = generated(
            SourceFile.java(
                "Doc.java",
                """
                package inh;
                import java.util.List;
                import java.util.Map;
                import org.drivine.annotation.*;
                public abstract class Doc {
                    @NodeId private String id;
                    @GraphProperty("full_title") private String title;
                    @PropertyBag private Map<String, Object> meta;
                    @FullTextIndex private String body;
                    @VectorIndex private List<Float> embedding;
                    protected String shown;
                    protected String hidden;
                }
                """.trimIndent(),
            ),
            SourceFile.java(
                "Page.java",
                """
                package inh;
                import org.drivine.annotation.*;
                @NodeFragment(labels = {"Page"})
                public class Page extends Doc {
                    protected Integer shown;
                    @GraphTransient protected String hidden;
                }
                """.trimIndent(),
            ),
            SourceFile.java(
                "Edge.java",
                """
                package inh;
                public abstract class Edge {
                    private String since;
                }
                """.trimIndent(),
            ),
            SourceFile.java(
                "Cites.java",
                """
                package inh;
                import org.drivine.annotation.*;
                @RelationshipFragment
                public class Cites extends Edge {
                    @GraphTransient public String scratch;
                    public Page target;
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "PageView.kt",
                """
                package inh
                import org.drivine.annotation.*
                @GraphView data class PageView(
                    @Root val page: Page,
                    @GraphRelationship(type = "CITES") val cites: List<Cites>,
                )
                """.trimIndent(),
            ),
        )
        for (dsl in listOf("PageQueryDsl", "PageProperties")) {
            val code = classOf(files, dsl)
            for (stored in listOf("\"id\"", "\"full_title\"", "PropertyBagReference(", "\"meta.\"", "\"body\"", "\"embedding\""))
                assertTrue(stored in code, "$dsl lacks $stored:\n$code")
            assertTrue("val shown: PropertyReference<Int>" in code, "$dsl reads Page's shown, not the Doc field it hides:\n$code")
            assertFalse("hidden" in code, "$dsl has a field that a transient one hides:\n$code")
        }
        val cites = classOf(files, "CitesProperties")
        assertTrue("\"since\"" in cites, "CitesProperties lacks since:\n$cites")
        assertFalse("scratch" in cites, "CitesProperties has scratch:\n$cites")
        // An inherited index makes the fragment, and a view rooted at it, searchable.
        assertTrue("loadMatching" in files.getValue("PageQueryDsl.kt"), "PageQueryDsl has no loadMatching")
        for (search in listOf("loadNearest", "loadMatching")) assertTrue(search in files.getValue("PageViewQueryDsl.kt"), "PageViewQueryDsl has no $search")
    }

    @Test
    fun `a property inherited from a generic base has the type the fragment gives it`() {
        val files = generated(
            SourceFile.java(
                "Held.java",
                """
                package inh;
                import org.drivine.annotation.*;
                public abstract class Held<T> {
                    @NodeId private String id;
                    private T value;
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Kinds.kt",
                """
                package inh
                import org.drivine.annotation.*
                abstract class Boxed<T>(val content: T)
                @NodeFragment(labels = ["Count"]) class Count : Held<Int>()
                @NodeFragment(labels = ["Box"]) class Box(content: Long) : Boxed<Long>(content)
                @GraphView data class CountView(@Root val count: Count)
                @GraphView data class BoxView(@Root val box: Box)
                """.trimIndent(),
            ),
        )
        for (dsl in listOf("CountQueryDsl", "CountProperties")) {
            val code = classOf(files, dsl)
            assertTrue("PropertyReference<kotlin.Int>(" in code, "$dsl lacks value as an Int:\n$code")
        }
        for (dsl in listOf("BoxQueryDsl", "BoxProperties")) {
            val code = classOf(files, dsl)
            assertTrue("PropertyReference<kotlin.Long>(" in code, "$dsl lacks content as a Long:\n$code")
        }
    }

    @Test
    fun `the Java processor's DSL references a compiled Kotlin superclass's properties but not its private ones`() {
        val compilation = Compiler.javac().withProcessors(JavaProcessor()).compile(
            JavaFileObjects.forSourceString(
                "jx.OverKotlin",
                """
                package jx;
                import org.drivine.annotation.*;
                @NodeFragment(labels = {"OverKotlin"})
                public class OverKotlin extends org.drivine.codegen.KotlinTitled { @NodeId public String id; }
                """.trimIndent(),
            ),
            JavaFileObjects.forSourceString(
                "jx.OverKotlinView",
                """
                package jx;
                import org.drivine.annotation.*;
                @GraphView public class OverKotlinView { @Root public OverKotlin over; }
                """.trimIndent(),
            ),
        )
        assertEquals(Compilation.Status.SUCCESS, compilation.status(), compilation.diagnostics().toString())
        for (dsl in listOf("OverKotlinQueryDsl", "OverKotlinProperties")) {
            val code = compilation.generatedSourceFiles().single { it.name.endsWith("/$dsl.java") }.getCharContent(true).toString()
            // A Kotlin property's backing field is private, whatever the property's visibility.
            for (stored in listOf("\"title\"", "\"open\"", "\"isDone\"", "\"rank\"")) assertTrue(stored in code, "$dsl lacks $stored:\n$code")
            assertFalse("secret" in code, "$dsl has secret:\n$code")
        }
    }
}

/** A Kotlin superclass of a Java fragment, on the classpath as a compiled Kotlin class: [secret] is no stored state. */
abstract class KotlinTitled {
    var title: String = ""
    protected val open: Boolean = false
    val isDone: Boolean = false
    internal val rank: Int = 0
    private val secret: String = ""
}
