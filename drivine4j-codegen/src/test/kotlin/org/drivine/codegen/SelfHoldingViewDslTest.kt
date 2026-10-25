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

/**
 * A view that holds itself, or two that hold each other, get a query DSL that can be built: a held
 * view's relationship to another view is built when it is read, at the alias the runtime gives it.
 */
@OptIn(ExperimentalCompilerApi::class)
class SelfHoldingViewDslTest {

    @TempDir
    lateinit var dir: File

    private fun compile(source: String): ClassLoader {
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Views.kt", source.trimIndent()))
            workingDir = dir
            inheritClassPath = true
            kotlincArguments = listOf("-Xcontext-parameters")
            useKsp2()
            configureKsp { symbolProcessorProviders += GraphViewProcessorProvider() }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return result.classLoader
    }

    /** The value of each property named in [path], read by its getter from [start]. */
    private fun read(start: Any, vararg path: String): Any =
        path.fold(start) { at, name ->
            at.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(at)
        }

    @Test
    fun `a view that holds itself builds, and each level is at its alias`() {
        val loader = compile(
            """
            package sh
            import org.drivine.annotation.*
            @NodeFragment(labels = ["Member"]) data class Member(@NodeId val id: String, val name: String)
            @GraphView data class Org(@Root val head: Member, @GraphRelationship(type = "RUNS") val units: List<Org>)
            """
        )
        val dsl = loader.loadClass("sh.OrgQueryDsl").getConstructor().newInstance()

        assertEquals("units", read(dsl, "units", "nodeAlias"))
        assertEquals("units_units", read(dsl, "units", "units", "nodeAlias"))
        assertEquals("units_units", read(dsl, "units", "units", "head", "nodeAlias"))
    }

    @Test
    fun `two views that hold each other build`() {
        val loader = compile(
            """
            package sh
            import org.drivine.annotation.*
            @NodeFragment(labels = ["Member"]) data class Member(@NodeId val id: String, val name: String)
            @GraphView data class Mentor(@Root val person: Member, @GraphRelationship(type = "MENTORS") val mentees: List<Mentee>)
            @GraphView data class Mentee(@Root val person: Member, @GraphRelationship(type = "MENTORED_BY") val mentor: Mentor?)
            @GraphView data class Pairing(@Root val person: Member, @GraphRelationship(type = "PAIRED") val mentor: Mentor)
            """
        )
        val dsl = loader.loadClass("sh.PairingQueryDsl").getConstructor().newInstance()

        assertEquals("mentor_mentees_mentor", read(dsl, "mentor", "mentees", "mentor", "nodeAlias"))
    }
}
