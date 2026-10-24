package org.drivine.codegen.model

import com.google.testing.compile.Compiler
import com.google.testing.compile.JavaFileObjects
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.drivine.codegen.GraphViewProcessorProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.drivine.codegen.java.GraphViewProcessor as JavaProcessor

/**
 * One Java domain through both code generators: the KSP processor, as a module with Kotlin in it runs
 * it, and the Java annotation processor, as a Java module does. The two read classes with different
 * compilers' models, and must describe them alike.
 *
 * A view that holds itself is left out: the Java processor's query DSL does not stop reading one.
 *
 * They differ only where Java says more than KSP can see of it: `source` names a type as Java writes
 * it (`java.lang.String`, `int`) where KSP gives Kotlin's names, and `nullable` is `false` for a
 * primitive, which KSP cannot tell from its boxed type. Those two keys are left out of the comparison.
 */
@OptIn(ExperimentalCompilerApi::class)
class ModelFileParityTest {

    @TempDir
    lateinit var dir: File

    private val domain = mapOf(
        "Role" to "public enum Role { Admin, Author }",
        "Address" to "public class Address { public String city; }",
        "Signal" to """@NodeFragment(labels = {"Signal"}) public interface Signal {}""",
        "EmailSignal" to """
            @NodeFragment(labels = {"Email"})
            public final class EmailSignal implements Signal { @NodeId public String id; public String subject; }
        """,
        "Member" to """
            @NodeFragment(labels = {"Member"})
            @Unique(properties = {"tenantId", "userId"})
            @RangeIndex(properties = {"joined"})
            public final class Member {
                @NodeId public String id;
                public String tenantId;
                public String userId;
                public java.time.Instant joined;
                @TextIndex public String handle;
                @Default public int score;
                @EmptyWhenAbsent public java.util.Set<String> tags;
                @JsonPacked public Address address;
                public java.util.Map<String, Integer> counts;
                public float[] vector;
                @VectorIndex(similarity = SimilarityFunction.EUCLIDEAN) public java.util.List<Float> embedding;
                @NodeLabels public java.util.Set<Role> roles;
                @PropertyBag(prefix = "meta", delimiter = "_") public java.util.Map<String, String> meta;
                @PropertyBag(flat = true) public java.util.Map<String, Object> rest;
                @GraphTransient public String cache;
                @GraphProperty("stored_as") public String renamed;
                public static final String KIND = "member";
            }
        """,
        "Note" to """
            @NodeFragment(labels = {"Note"})
            public final class Note { @NodeId public String id; @NodeStamp public String stamp; }
        """,
        "Tagged" to """
            @NodeFragment(labels = {"Tagged"})
            public final class Tagged { @NodeId public String id; @NodeLabels public java.util.Set<String> labels; }
        """,
        "Animal" to """@NodeFragment(labels = {"Animal"}) public class Animal { @NodeId public String id; }""",
        "Shape" to """
            @NodeFragment(labels = {"Shape"})
            @com.fasterxml.jackson.annotation.JsonSubTypes({@com.fasterxml.jackson.annotation.JsonSubTypes.Type(Circle.class)})
            public abstract class Shape { @NodeId public String id; }
        """,
        "Circle" to """@NodeFragment(labels = {"Circle"}) public final class Circle extends Shape { public double radius; }""",
        "Membership" to """
            @RelationshipFragment
            public final class Membership { public java.time.LocalDate since; public Member member; }
        """,
        "Team" to """
            @GraphView
            public final class Team {
                @Root public Member lead;
                @GraphRelationship(type = "HAS", direction = Direction.INCOMING)
                @SortedBy(property = "member.handle", ascending = false)
                public java.util.List<Membership> memberships;
                @GraphRelationship(type = "REPORTS_TO", maxDepth = 3) public java.util.List<Member> chain;
                @ReadOnly @GraphRelationship(type = "MENTORS") public Member mentee;
                @GraphPath(hops = {@Hop(type = "A"), @Hop(type = "B", direction = Direction.INCOMING, label = "Mid", orderBy = "at", ascending = false)})
                public java.util.List<Member> far;
                @Count(type = "HAS") public long memberCount;
                @Aggregate(function = AggregateFunction.MAX, type = "HAS", property = "score") public Integer topScore;
            }
        """,
    ).mapValues { (_, body) -> "package jx;\n\nimport org.drivine.annotation.*;\nimport org.drivine.schema.SimilarityFunction;\n\n${body.trimIndent()}\n" }

    @Test
    fun `both processors describe a Java domain alike`() {
        val ksp = File(dir, "ksp.json")
        val result = KotlinCompilation().apply {
            sources = domain.map { (name, text) -> SourceFile.java("$name.java", text) }
            workingDir = File(dir, "ksp")
            inheritClassPath = true
            kotlincArguments = listOf("-Xcontext-parameters")
            useKsp2()
            configureKsp {
                symbolProcessorProviders += GraphViewProcessorProvider()
                processorOptions.putAll(mapOf(ModelFiles.FILE to ksp.path, ModelFiles.MODULE to "jx"))
            }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val apt = File(dir, "apt.json")
        val compilation = Compiler.javac().withProcessors(JavaProcessor())
            .withOptions("-A${ModelFiles.FILE}=${apt.path}", "-A${ModelFiles.MODULE}=jx")
            .compile(domain.map { (name, text) -> JavaFileObjects.forSourceString("jx.$name", text) })
        // The Java processor's query DSL compiles no relationship fragment yet, and the build fails on
        // it; the model file is written all the same.
        assertTrue(apt.isFile, compilation.diagnostics().toString())

        val model = ModelJson.read(ksp.readText()) as Map<*, *>
        // Not two empty files alike: the whole domain is in it.
        assertEquals(listOf(8, 1, 1), listOf("fragments", "relationshipFragments", "views").map { (model[it] as List<*>).size })
        assertEquals(comparable(model), comparable(ModelJson.read(apt.readText())))
    }

    /** [value] without the two keys the processors may rightly differ on. */
    private fun comparable(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.filterKeys { it != "source" && it != "nullable" }.mapValues { comparable(it.value) }
        is List<*> -> value.map { comparable(it) }
        else -> value
    }
}
