package org.drivine.codegen.java;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.drivine.codegen.model.ModelFiles;
import org.drivine.codegen.model.ModelJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The annotation processor writes the model file for a Java module. What it shares with the KSP
 * processor is checked against it, in drivine4j-codegen's ModelFileParityTest; this is what only Java
 * says, and where the Java processor finds the module.
 */
class ModelFileCodegenTest {

    @TempDir
    File dir;

    private static final JavaFileObject TICKET = JavaFileObjects.forSourceLines("jx.Ticket",
        "package jx;",
        "import org.drivine.annotation.*;",
        "@NodeFragment(labels = {\"Ticket\"})",
        "public final class Ticket {",
        "  @NodeId public String id;",
        "  public int priority;",
        "  public Integer rank;",
        "  @Nullable public String note;",
        "  @NonNull public String title;",
        "  public java.util.List<? extends CharSequence> words;",
        "  public static final String KIND = \"ticket\";",
        "}");

    private static final JavaFileObject NULLABLE = JavaFileObjects.forSourceLines("jx.Nullable",
        "package jx;", "public @interface Nullable {}");

    private static final JavaFileObject NON_NULL = JavaFileObjects.forSourceLines("jx.NonNull",
        "package jx;", "public @interface NonNull {}");

    private static final JavaFileObject VEHICLE = JavaFileObjects.forSourceLines("jx.Vehicle",
        "package jx;",
        "import org.drivine.annotation.*;",
        "@NodeFragment(labels = {\"Vehicle\"})",
        "public sealed class Vehicle permits Car, Bike {",
        "  @NodeId public String id;",
        "}");

    private static final JavaFileObject CAR = JavaFileObjects.forSourceLines("jx.Car",
        "package jx;",
        "import org.drivine.annotation.*;",
        "@NodeFragment(labels = {\"Car\"})",
        "public final class Car extends Vehicle {}");

    /** Permitted, but not a fragment: nothing a load could give. */
    private static final JavaFileObject BIKE = JavaFileObjects.forSourceLines("jx.Bike",
        "package jx;",
        "public final class Bike extends Vehicle {}");

    private static final JavaFileObject PLAIN = JavaFileObjects.forSourceLines("jx.Plain",
        "package jx;", "public class Plain {}");

    @Test
    void javaSaysWhatItCanOfNull() throws IOException {
        Map<?, ?> model = generate(TICKET, NULLABLE, NON_NULL);
        assertEquals("java", ((Map<?, ?>) model.get("generator")).get("language"));
        Map<?, ?> ticket = fragment(model, "jx.Ticket");
        Map<String, Map<?, ?>> properties = properties(ticket);
        // A primitive cannot be null; a boxed or reference field can, unless it says it cannot.
        assertEquals(false, properties.get("priority").get("nullable"));
        assertEquals("int", properties.get("priority").get("source"));
        assertEquals("unknown", properties.get("rank").get("nullable"));
        assertEquals(true, properties.get("note").get("nullable"));
        assertEquals(false, properties.get("title").get("nullable"));
        assertEquals("java.util.List<?>", properties.get("words").get("source"));
        assertEquals("list", properties.get("words").get("type"));
        // A static field is not a property.
        assertFalse(properties.containsKey("KIND"));
        // A final class has no subtypes.
        assertFalse(ticket.containsKey("subtypesComplete"));
    }

    @Test
    void aSealedClassListsThePermittedFragments() throws IOException {
        Map<?, ?> model = generate(VEHICLE, CAR, BIKE);
        Map<?, ?> vehicle = fragment(model, "jx.Vehicle");
        assertEquals(List.of("jx.Car"), vehicle.get("subtypes"));
        assertFalse(vehicle.containsKey("subtypesComplete"));
        Map<?, ?> car = fragment(model, "jx.Car");
        assertEquals(List.of("Car", "Vehicle"), car.get("labels"));
        assertEquals("id", car.get("nodeId"));
    }

    @Test
    void aModuleWithNothingLeftKeepsItsFileEverythingInItRemoved() throws IOException {
        File file = new File(dir, ".drivine/model.json");
        generate(file, TICKET, NULLABLE, NON_NULL);
        Map<?, ?> model = generate(file, PLAIN);
        Map<?, ?> ticket = fragment(model, "jx.Ticket");
        assertEquals(true, ticket.get("removed"));
        assertEquals("java", ((Map<?, ?>) model.get("generator")).get("language"));
    }

    @Test
    void aModuleThatNeverDeclaredAnythingWritesNoFile() {
        File file = new File(dir, ".drivine/model.json");
        Compilation compilation = compile(file, PLAIN);
        assertThat(compilation).succeeded();
        assertFalse(file.exists());
    }

    @Test
    void theClassOutputsCopyIsTheOneWritten() throws IOException {
        File file = new File(dir, ".drivine/model.json");
        Compilation compilation = compile(file, TICKET, NULLABLE, NON_NULL);
        assertThat(compilation).generatedFile(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/drivine/model.json")
            .contentsAsUtf8String().isEqualTo(Files.readString(file.toPath()));
    }

    @Test
    void theSwitchTurnsItOff() {
        File file = new File(dir, ".drivine/model.json");
        Compilation compilation = Compiler.javac().withProcessors(new GraphViewProcessor())
            .withOptions("-A" + ModelFiles.FILE + "=" + file.getPath(), "-A" + ModelFiles.ENABLED + "=false")
            .compile(TICKET, NULLABLE, NON_NULL);
        assertThat(compilation).succeeded();
        assertFalse(file.exists());
    }

    @Test
    void aRelativePathIsRefused() {
        Compilation compilation = Compiler.javac().withProcessors(new GraphViewProcessor())
            .withOptions("-A" + ModelFiles.FILE + "=model.json")
            .compile(TICKET, NULLABLE, NON_NULL);
        assertThat(compilation).hadErrorContaining("must be an absolute path");
    }

    @Test
    void theModuleIsFoundInGradlesLayoutAndMavensForTheMainSourcesOnly() {
        assertEquals(new ModelFileGenerator.Layout(new File("/repo/app"), true),
            ModelFileGenerator.layout(new File("/repo/app/build/classes/java/main/META-INF/drivine/model.json").toURI()));
        assertEquals(new ModelFileGenerator.Layout(new File("/repo/app"), false),
            ModelFileGenerator.layout(new File("/repo/app/build/classes/java/test/META-INF/drivine/model.json").toURI()));
        assertEquals(new ModelFileGenerator.Layout(new File("/repo/app"), true),
            ModelFileGenerator.layout(new File("/repo/app/target/classes/META-INF/drivine/model.json").toURI()));
        assertEquals(new ModelFileGenerator.Layout(new File("/repo/app"), false),
            ModelFileGenerator.layout(new File("/repo/app/target/test-classes/META-INF/drivine/model.json").toURI()));
        assertNull(ModelFileGenerator.layout(new File("/repo/app/out/META-INF/drivine/model.json").toURI()));
        assertNull(ModelFileGenerator.layout(java.net.URI.create("mem:///CLASS_OUTPUT/META-INF/drivine/model.json")));
    }

    private Map<?, ?> generate(JavaFileObject... sources) throws IOException {
        return generate(new File(dir, "model.json"), sources);
    }

    private Map<?, ?> generate(File file, JavaFileObject... sources) throws IOException {
        Compilation compilation = compile(file, sources);
        assertThat(compilation).succeeded();
        assertTrue(file.isFile(), "no model file written");
        return (Map<?, ?>) ModelJson.read(Files.readString(file.toPath()));
    }

    private static Compilation compile(File file, JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new GraphViewProcessor())
            .withOptions("-A" + ModelFiles.FILE + "=" + file.getPath(), "-A" + ModelFiles.MODULE + "=jx")
            .compile(sources);
    }

    private static Map<?, ?> fragment(Map<?, ?> model, String id) {
        return ((List<?>) model.get("fragments")).stream().map(f -> (Map<?, ?>) f)
            .filter(f -> id.equals(f.get("id"))).findFirst().orElseThrow();
    }

    private static Map<String, Map<?, ?>> properties(Map<?, ?> fragment) {
        Map<String, Map<?, ?>> out = new java.util.LinkedHashMap<>();
        for (Object p : (List<?>) fragment.get("properties")) out.put((String) ((Map<?, ?>) p).get("name"), (Map<?, ?>) p);
        return out;
    }
}
