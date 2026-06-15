package org.drivine.codegen.java;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * Verifies the Java annotation processor emits a {@code PropertyBagReference} (with {@code key(name)})
 * for a {@code @PropertyBag} field, and a scalar reference for ordinary fields.
 */
class PropertyBagCodegenTest {

    @Test
    void emitsPropertyBagReferenceForBagField() {
        JavaFileObject fragment = JavaFileObjects.forSourceLines("sample.bag.BagFrag",
            "package sample.bag;",
            "import org.drivine.annotation.NodeFragment;",
            "import org.drivine.annotation.NodeId;",
            "import org.drivine.annotation.PropertyBag;",
            "import java.util.Map;",
            "@NodeFragment(labels = {\"Bagged\"})",
            "public class BagFrag {",
            "  @NodeId public String id;",
            "  public String title;",
            "  @PropertyBag(prefix = \"meta\") public Map<String, Object> metadata;",
            "}");
        JavaFileObject view = JavaFileObjects.forSourceLines("sample.bag.BagView",
            "package sample.bag;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class BagView {",
            "  @Root public BagFrag node;",
            "}");

        Compilation compilation = Compiler.javac()
            .withProcessors(new GraphViewProcessor())
            .compile(fragment, view);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.bag.BagFragProperties")
            .contentsAsUtf8String()
            .contains("PropertyBagReference");
        // prefix override applied: stored prefix is "meta." (not the field name "metadata.")
        assertThat(compilation)
            .generatedSourceFile("sample.bag.BagFragProperties")
            .contentsAsUtf8String()
            .contains("\"meta.\"");
    }

    @Test
    void emitsScalarReferenceForOrdinaryField() {
        JavaFileObject fragment = JavaFileObjects.forSourceLines("sample.bag.PlainFrag",
            "package sample.bag;",
            "import org.drivine.annotation.NodeFragment;",
            "import org.drivine.annotation.NodeId;",
            "@NodeFragment(labels = {\"Plain\"})",
            "public class PlainFrag {",
            "  @NodeId public String id;",
            "  public String title;",
            "}");
        JavaFileObject view = JavaFileObjects.forSourceLines("sample.bag.PlainView",
            "package sample.bag;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class PlainView {",
            "  @Root public PlainFrag node;",
            "}");

        Compilation compilation = Compiler.javac()
            .withProcessors(new GraphViewProcessor())
            .compile(fragment, view);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.bag.PlainFragProperties")
            .contentsAsUtf8String()
            .contains("StringPropertyReference");
    }
}
