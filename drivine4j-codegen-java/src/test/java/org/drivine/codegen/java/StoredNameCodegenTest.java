package org.drivine.codegen.java;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.drivine.model.Stamps;
import org.junit.jupiter.api.Test;

/**
 * The generated DSL carries a stored name as the library stores it: the stamp's property is the
 * library's own constant, and a `@GraphProperty` name is a string literal whatever it holds.
 */
class StoredNameCodegenTest {

    private static final JavaFileObject VIEW = JavaFileObjects.forSourceLines("sample.sn.ThingView",
        "package sample.sn;",
        "import org.drivine.annotation.GraphView;",
        "import org.drivine.annotation.Root;",
        "@GraphView",
        "public class ThingView {",
        "  @Root public Thing node;",
        "}");

    private static Compilation compile(String... fields) {
        String[] lines = new String[fields.length + 8];
        lines[0] = "package sample.sn;";
        lines[1] = "import org.drivine.annotation.GraphProperty;";
        lines[2] = "import org.drivine.annotation.NodeFragment;";
        lines[3] = "import org.drivine.annotation.NodeId;";
        lines[4] = "import org.drivine.annotation.NodeStamp;";
        lines[5] = "@NodeFragment(labels = {\"Thing\"})";
        lines[6] = "public class Thing {";
        lines[7] = "  @NodeId public String id;";
        System.arraycopy(fields, 0, lines, 8, fields.length);
        String[] all = java.util.Arrays.copyOf(lines, lines.length + 1);
        all[all.length - 1] = "}";
        return Compiler.javac()
            .withProcessors(new GraphViewProcessor())
            .compile(JavaFileObjects.forSourceLines("sample.sn.Thing", all), VIEW);
    }

    @Test
    void theStampPropertyIsTheOneTheLibraryStoresTheStampUnder() {
        Compilation compilation = compile("  @NodeStamp public String version;");

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.sn.ThingProperties")
            .contentsAsUtf8String()
            .contains("\"" + Stamps.PROPERTY + "\"");
    }

    @Test
    void aStoredNameIsWrittenAsALiteralWhateverItHolds() {
        Compilation compilation = compile(
            "  @GraphProperty(\"price$usd\") public Long price;",
            "  @GraphProperty(\"rate%\") public Long rate;",
            "  @GraphProperty(\"say \\\"hi\\\"\") public String greeting;",
            "  @GraphProperty(\"back\\\\slash\") public String path;");

        assertThat(compilation).succeeded();
        String[] literals = {"\"price$usd\"", "\"rate%\"", "\"say \\\"hi\\\"\"", "\"back\\\\slash\""};
        for (String literal : literals) {
            assertThat(compilation)
                .generatedSourceFile("sample.sn.ThingProperties")
                .contentsAsUtf8String()
                .contains(literal);
        }
    }
}
