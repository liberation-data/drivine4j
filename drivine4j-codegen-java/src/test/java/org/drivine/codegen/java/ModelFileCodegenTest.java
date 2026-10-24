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

    /** A record, which the KSP processor cannot read yet: its components are its fields. */
    @Test
    void aRecordsComponentsAreItsProperties() throws IOException {
        Map<?, ?> model = generate(JavaFileObjects.forSourceLines("jx.Pet",
            "package jx;",
            "import org.drivine.annotation.*;",
            "@NodeFragment(labels = {\"Pet\"})",
            "public record Pet(@NodeId String id, @TextIndex String name, int age) {",
            "  public static final String KIND = \"pet\";",
            "}"));
        Map<?, ?> pet = fragment(model, "jx.Pet");
        assertEquals("id", pet.get("nodeId"));
        Map<String, Map<?, ?>> properties = properties(pet);
        assertEquals(List.of("age", "id", "name"), List.copyOf(properties.keySet()));
        assertEquals("text", properties.get("name").get("index"));
        assertEquals(false, properties.get("age").get("nullable"));
        // A record is final: it has no subtypes.
        assertFalse(pet.containsKey("subtypesComplete"));
    }

    /** The version is the build's, which drivine4j-codegen-model carries in its resources. */
    @Test
    void theGeneratorIsNamedWithTheBuildsVersion() throws IOException {
        Map<?, ?> generator = (Map<?, ?>) generate(TICKET, NULLABLE, NON_NULL).get("generator");
        assertEquals("drivine4j", generator.get("library"));
        assertTrue(((String) generator.get("version")).matches("\\d+\\.\\d+\\.\\d+.*"), generator.toString());
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

    @Test
    void outsideAKnownLayoutOnlyTheClassOutputIsWritten() {
        Compilation compilation = Compiler.javac().withProcessors(new GraphViewProcessor())
            .compile(TICKET, NULLABLE, NON_NULL);
        assertThat(compilation).hadWarningContaining("written to the class output only");
        assertThat(compilation).generatedFile(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/drivine/model.json");
    }

    /** A fragment another processor generates, in a later round than the sources it came from. */
    @javax.annotation.processing.SupportedAnnotationTypes("*")
    static final class FragmentWriter extends javax.annotation.processing.AbstractProcessor {
        private boolean written;

        @Override
        public javax.lang.model.SourceVersion getSupportedSourceVersion() {
            return javax.lang.model.SourceVersion.latestSupported();
        }

        @Override
        public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                               javax.annotation.processing.RoundEnvironment roundEnv) {
            if (written) return false;
            written = true;
            try (java.io.Writer writer = processingEnv.getFiler().createSourceFile("jx.Made").openWriter()) {
                writer.write("package jx;\n@org.drivine.annotation.NodeFragment(labels = {\"Made\"})\n"
                    + "public final class Made { @org.drivine.annotation.NodeId public String id; }\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return false;
        }
    }

    @Test
    void aFragmentGeneratedInALaterRoundIsInTheFile() throws IOException {
        File file = new File(dir, "model.json");
        Compilation compilation = Compiler.javac().withProcessors(new FragmentWriter(), new GraphViewProcessor())
            .withOptions("-A" + ModelFiles.FILE + "=" + file.getPath())
            .compile(TICKET, NULLABLE, NON_NULL);
        assertThat(compilation).succeeded();
        Map<?, ?> model = (Map<?, ?>) ModelJson.read(Files.readString(file.toPath()));
        assertEquals(List.of("Made"), fragment(model, "jx.Made").get("labels"));
        assertEquals(List.of("Ticket"), fragment(model, "jx.Ticket").get("labels"));
    }

    /**
     * Through javac's own file manager, as a build runs it: the module is found from the class output,
     * and the jar's copy is the file's bytes, UTF-8, whatever encoding the sources are read in.
     */
    @Test
    void gradlesLayoutWritesBesideTheModuleInUtf8() throws IOException {
        File module = new File(dir, "app");
        File classes = new File(module, "build/classes/java/main");
        assertTrue(classes.mkdirs());
        File source = new File(dir, "Cafe.java");
        Files.writeString(source.toPath(), "package jx;\n@org.drivine.annotation.NodeFragment(labels = {\"Caf\\u00e9\"})\n"
            + "public final class Cafe { @org.drivine.annotation.NodeId public String id; }\n");
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (javax.tools.StandardJavaFileManager files = javac.getStandardFileManager(null, null, null)) {
            files.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classes));
            javax.tools.JavaCompiler.CompilationTask task = javac.getTask(null, files, null,
                List.of("-encoding", "ISO-8859-1", "-proc:only", "-classpath", System.getProperty("java.class.path")),
                null, files.getJavaFileObjects(source));
            task.setProcessors(List.of(new GraphViewProcessor()));
            assertTrue(task.call());
        }
        File file = new File(module, ".drivine/model.json");
        assertTrue(file.isFile(), "no model file beside the module");
        Map<?, ?> model = (Map<?, ?>) ModelJson.read(Files.readString(file.toPath()));
        assertEquals("app", model.get("module"));
        assertEquals(List.of("Café"), fragment(model, "jx.Cafe").get("labels"));
        assertEquals(Files.readString(file.toPath()),
            Files.readString(new File(classes, "META-INF/drivine/model.json").toPath()));
    }

    /**
     * Maven gives the compiler's arguments to the test compilation too: there, a file named by
     * drivine.model.file is the main sources' and is left as it is, whether the tests declare
     * nothing, which would mark all of it removed, or fragments of their own.
     */
    @Test
    void theTestsCompilationLeavesANamedFileAsItIs() throws IOException {
        File file = new File(dir, "model.json");
        generate(file, TICKET, NULLABLE, NON_NULL);
        String before = Files.readString(file.toPath());
        File testClasses = new File(dir, "app/target/test-classes");
        assertTrue(testClasses.mkdirs());
        for (String source : List.of("public class Plain {}",
            "@org.drivine.annotation.NodeFragment(labels = {\"Probe\"}) public final class Plain { @org.drivine.annotation.NodeId public String id; }")) {
            File plain = new File(dir, "Plain.java");
            Files.writeString(plain.toPath(), "package jx;\n" + source + "\n");
            javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            try (javax.tools.StandardJavaFileManager files = javac.getStandardFileManager(null, null, null)) {
                files.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(testClasses));
                javax.tools.JavaCompiler.CompilationTask task = javac.getTask(null, files, null,
                    List.of("-proc:only", "-classpath", System.getProperty("java.class.path"), "-A" + ModelFiles.FILE + "=" + file.getPath()),
                    null, files.getJavaFileObjects(plain));
                task.setProcessors(List.of(new GraphViewProcessor()));
                assertTrue(task.call());
            }
            assertEquals(before, Files.readString(file.toPath()), source);
        }
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
