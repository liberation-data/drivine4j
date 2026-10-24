package org.drivine.codegen.java;

import org.drivine.codegen.model.ModelFiles;
import org.drivine.codegen.model.ModelJson;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the module's model file from the entries [ModelReader] read, as the KSP processor does for a
 * Kotlin module: to the class output, as `META-INF/drivine/model.json`, so that it is in the jar, and
 * to `.drivine/model.json` beside the module's build file, for the repository to keep. Only the main
 * source set writes that one, so that the tests' own fragments stay out of it.
 *
 * Processor options (`-Adrivine.model.module=…`):
 * - `drivine.model`: `false` writes no model file.
 * - `drivine.model.module`: the module's name in the file; the module directory's name otherwise.
 * - `drivine.model.file`: an absolute path to write the file to, in place of `.drivine/model.json`
 *   beside the build file, for a layout the generator cannot find that in. A compilation of the
 *   tests, in Gradle's layout or Maven's, does not write it.
 */
final class ModelFileGenerator {

    private final Filer filer;
    private final Messager messager;
    private final Map<String, String> options;

    ModelFileGenerator(Filer filer, Messager messager, Map<String, String> options) {
        this.filer = filer;
        this.messager = messager;
        this.options = options;
    }

    /**
     * Writes the model of [fragments], [relationshipFragments] and [views], with the previous file's
     * entries for classes the code no longer declares, marked removed.
     */
    void generate(List<Map<String, Object>> fragments, List<Map<String, Object>> relationshipFragments, List<Map<String, Object>> views) {
        if ("false".equalsIgnoreCase(options.get(ModelFiles.ENABLED))) return;
        String named = options.get(ModelFiles.FILE);
        // A relative path would be taken from wherever the build happens to run.
        if (named != null && !new File(named).isAbsolute()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "Drivine model file: the option " + ModelFiles.FILE + " must be an absolute path, not " + named + ".");
            return;
        }
        // Where the class output is says where the module is, before anything is written there.
        Layout layout = layout(resourceUri());
        // The tests' compilation leaves the file be, a named one too: Maven gives it the main one's options.
        File target = layout != null && !layout.main() ? null
            : named != null ? new File(named)
            : layout != null ? new File(layout.moduleDir(), ".drivine/model.json") : null;
        Map<?, ?> previous = ModelFiles.previous(target, warning -> messager.printMessage(Diagnostic.Kind.WARNING, warning));

        boolean declared = !fragments.isEmpty() || !relationshipFragments.isEmpty() || !views.isEmpty();
        // With nothing declared there is nothing to write, unless a file before this one had something,
        // which is now removed.
        if (!declared && previous == null) return;

        String module = options.get(ModelFiles.MODULE);
        if (module == null) module = layout != null ? layout.moduleDir().getName() : "module";
        String json = ModelJson.write(ModelFiles.model(module, "java", fragments, relationshipFragments, views, previous, Set.of()));
        writeResource(json);

        if (target == null) {
            if (layout == null) {
                messager.printMessage(Diagnostic.Kind.WARNING, "Drivine model file: written to the class output only. Set the option "
                    + ModelFiles.FILE + " to write .drivine/model.json too.");
            }
            return;
        }
        if (ModelFiles.write(target, json)) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Drivine model file written to " + target.getPath());
        }
    }

    private URI resourceUri() {
        try {
            return filer.getResource(StandardLocation.CLASS_OUTPUT, "", ModelFiles.RESOURCE_PATH + ".json").toUri();
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private void writeResource(String json) {
        try {
            FileObject resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", ModelFiles.RESOURCE_PATH + ".json");
            // Bytes, not openWriter(), which encodes as the sources are read (-encoding): the file is UTF-8.
            try (OutputStream out = resource.openOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Drivine model file: could not write the class output's copy: " + e.getMessage());
        }
    }

    /** A module's directory, and whether the class output is its main source set's. */
    record Layout(File moduleDir, boolean main) {}

    /**
     * Where the build puts the class output [uri] is in: Gradle's `<module>/build/classes/java/<set>/`,
     * or Maven's `<module>/target/classes/` for the main sources and `target/test-classes/` for the
     * tests'. Null in another layout.
     */
    static Layout layout(URI uri) {
        if (uri == null || !"file".equals(uri.getScheme())) return null;
        for (File dir = new File(uri).getParentFile(); dir != null; dir = dir.getParentFile()) {
            File parent = dir.getParentFile();
            if (parent == null) break;
            // Gradle: <module>/build/classes/java/<set>
            if (parent.getName().equals("java") && parent.getParentFile() != null && parent.getParentFile().getName().equals("classes")) {
                File build = parent.getParentFile().getParentFile();
                if (build != null && build.getParentFile() != null) return new Layout(build.getParentFile(), dir.getName().equals("main"));
            }
            // Maven: <module>/target/classes or <module>/target/test-classes
            if (parent.getName().equals("target") && (dir.getName().equals("classes") || dir.getName().equals("test-classes"))
                && parent.getParentFile() != null) {
                return new Layout(parent.getParentFile(), dir.getName().equals("classes"));
            }
        }
        return null;
    }
}
