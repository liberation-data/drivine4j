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
}
