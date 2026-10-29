package org.drivine.codegen.java;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * The generated DSL has a reference for each field the node stores, as the core library reads them:
 * those a fragment inherits, a Java superclass's private ones among them, but no static field and no
 * {@code @GraphTransient} one.
 */
class InheritedFieldsCodegenTest {

    private static final JavaFileObject BASE = JavaFileObjects.forSourceLines("sample.inh.Base",
        "package sample.inh;",
        "import org.drivine.annotation.GraphTransient;",
        "import org.drivine.annotation.NodeId;",
        "public abstract class Base {",
        "  public static final String KIND = \"base\";",
        "  @NodeId private String id;",
        "  protected String shared;",
        "  @GraphTransient public String cache;",
        "}");

    private static final JavaFileObject PROBE = JavaFileObjects.forSourceLines("sample.inh.Probe",
        "package sample.inh;",
        "import org.drivine.annotation.GraphTransient;",
        "import org.drivine.annotation.NodeFragment;",
        "@NodeFragment(labels = {\"Probe\"})",
        "public class Probe extends Base {",
        "  private String code;",
        "  @GraphTransient public String scratch;",
        "}");

    private static final JavaFileObject VIEW = JavaFileObjects.forSourceLines("sample.inh.ProbeView",
        "package sample.inh;",
        "import org.drivine.annotation.GraphView;",
        "import org.drivine.annotation.Root;",
        "@GraphView",
        "public class ProbeView {",
        "  @Root public Probe probe;",
        "}");

    private static Compilation compile() {
        return Compiler.javac().withProcessors(new GraphViewProcessor()).compile(BASE, PROBE, VIEW);
    }

    @Test
    void aFragmentsDslReferencesTheFieldsItInheritsAPrivateIdAmongThem() {
        Compilation compilation = compile();

        assertThat(compilation).succeeded();
        for (String dsl : new String[] {"sample.inh.ProbeQueryDsl", "sample.inh.ProbeProperties"}) {
            for (String reference : new String[] {"\"id\"", "\"shared\"", "\"code\""}) {
                assertThat(compilation).generatedSourceFile(dsl).contentsAsUtf8String().contains(reference);
            }
        }
    }

    @Test
    void aFragmentsDslReferencesNoStaticAndNoTransientField() {
        Compilation compilation = compile();

        assertThat(compilation).succeeded();
        for (String dsl : new String[] {"sample.inh.ProbeQueryDsl", "sample.inh.ProbeProperties"}) {
            for (String reference : new String[] {"KIND", "cache", "scratch"}) {
                assertThat(compilation).generatedSourceFile(dsl).contentsAsUtf8String().doesNotContain(reference);
            }
        }
    }

    @Test
    void anInheritedFieldsAnnotationsShapeItsReferenceAndAFieldHidingOneInABaseIsTheOneRead() {
        Compilation compilation = Compiler.javac().withProcessors(new GraphViewProcessor()).compile(
            JavaFileObjects.forSourceLines("sample.inh.Doc",
                "package sample.inh;",
                "import java.util.Map;",
                "import org.drivine.annotation.*;",
                "public abstract class Doc {",
                "  @NodeId private String id;",
                "  @GraphProperty(\"full_title\") private String title;",
                "  @PropertyBag private Map<String, Object> meta;",
                "  protected String shown;",
                "  protected String hidden;",
                "}"),
            JavaFileObjects.forSourceLines("sample.inh.Page",
                "package sample.inh;",
                "import org.drivine.annotation.*;",
                "@NodeFragment(labels = {\"Page\"})",
                "public class Page extends Doc {",
                "  protected Integer shown;",
                "  @GraphTransient protected String hidden;",
                "}"),
            JavaFileObjects.forSourceLines("sample.inh.Edge",
                "package sample.inh;",
                "public abstract class Edge {",
                "  private String since;",
                "}"),
            JavaFileObjects.forSourceLines("sample.inh.Cites",
                "package sample.inh;",
                "import org.drivine.annotation.*;",
                "@RelationshipFragment",
                "public class Cites extends Edge {",
                "  @GraphTransient public String scratch;",
                "  public Page target;",
                "}"),
            JavaFileObjects.forSourceLines("sample.inh.PageView",
                "package sample.inh;",
                "import java.util.List;",
                "import org.drivine.annotation.*;",
                "@GraphView",
                "public class PageView {",
                "  @Root public Page page;",
                "  @GraphRelationship(type = \"CITES\") public List<Cites> cites;",
                "}"));

        assertThat(compilation).succeeded();
        for (String dsl : new String[] {"sample.inh.PageQueryDsl", "sample.inh.PageProperties"}) {
            for (String reference : new String[] {"\"id\"", "\"full_title\"", "PropertyBagReference", "\"meta.\"",
                                                  "PropertyReference<Integer> shown()"}) {
                assertThat(compilation).generatedSourceFile(dsl).contentsAsUtf8String().contains(reference);
            }
            assertThat(compilation).generatedSourceFile(dsl).contentsAsUtf8String().doesNotContain("hidden");
        }
        assertThat(compilation).generatedSourceFile("sample.inh.CitesProperties").contentsAsUtf8String().contains("\"since\"");
        assertThat(compilation).generatedSourceFile("sample.inh.CitesProperties").contentsAsUtf8String().doesNotContain("scratch");
    }

    @Test
    void aFieldInheritedFromAGenericBaseHasTheTypeTheFragmentGivesIt() {
        Compilation compilation = Compiler.javac().withProcessors(new GraphViewProcessor()).compile(
            JavaFileObjects.forSourceLines("sample.inh.Held",
                "package sample.inh;",
                "import org.drivine.annotation.NodeId;",
                "public abstract class Held<T> {",
                "  @NodeId protected String id;",
                "  protected T value;",
                "}"),
            JavaFileObjects.forSourceLines("sample.inh.Count",
                "package sample.inh;",
                "import org.drivine.annotation.NodeFragment;",
                "@NodeFragment(labels = {\"Count\"})",
                "public class Count extends Held<Integer> {}"),
            JavaFileObjects.forSourceLines("sample.inh.CountView",
                "package sample.inh;",
                "import org.drivine.annotation.*;",
                "@GraphView",
                "public class CountView {",
                "  @Root public Count count;",
                "}"));

        assertThat(compilation).succeeded();
        for (String dsl : new String[] {"sample.inh.CountQueryDsl", "sample.inh.CountProperties"}) {
            assertThat(compilation).generatedSourceFile(dsl).contentsAsUtf8String()
                .contains("PropertyReference<Integer> value()");
        }
    }
}
