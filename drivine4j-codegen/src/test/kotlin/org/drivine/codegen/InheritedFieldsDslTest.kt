package org.drivine.codegen

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
}
