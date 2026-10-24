package org.drivine.codegen.model

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Origin
import java.io.File
import java.util.Properties

/**
 * Writes the module's model file: its fragments, relationship fragments and views, which Drivine
 * Studio reads. The format is drivine-cypher-spec's `model/`.
 *
 * The file goes in two places. One is the module's resources, as `META-INF/drivine/model.json`, so
 * it is in the jar. The other is `.drivine/model.json` beside the module's build file, for the
 * repository to keep: the build writes it again when the model changes, and CI fails when the one
 * committed differs. Only the main source set writes that one, so that the tests' own fragments
 * stay out of it.
 *
 * KSP options (`ksp { arg("drivine.model.module", "…") }`):
 * - `drivine.model`: `false` writes no model file.
 * - `drivine.model.module`: the module's name in the file; the build file's directory's name otherwise.
 * - `drivine.model.file`: an absolute path to write the file to, in place of `.drivine/model.json`
 *   beside the build file, for a layout the generator cannot find that in.
 */
internal class ModelFileGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) {

    fun generate(
        fragments: List<KSClassDeclaration>,
        relationshipFragments: List<KSClassDeclaration>,
        views: List<KSClassDeclaration>,
    ) {
        if (options[ENABLED]?.lowercase() == "false") return
        val all = fragments + relationshipFragments + views
        if (all.isEmpty()) return

        // The resource first: where KSP puts it says where the module is.
        val resource = codeGenerator.createNewFileByPath(
            Dependencies(aggregating = true, *all.mapNotNull { it.containingFile }.distinct().toTypedArray()),
            RESOURCE_PATH,
            "json",
        )
        val resourceFile = codeGenerator.generatedFile.firstOrNull { it.invariantSeparatorsPath.endsWith("$RESOURCE_PATH.json") }
        val layout = resourceFile?.let { gradleLayout(it) }
        val module = options[MODULE] ?: layout?.moduleDir?.name ?: resourceFile?.let { buildFileDir(it) }?.name ?: "module"

        val reader = ModelReader()
        val model = linkedMapOf(
            "\$comment" to COMMENT,
            "format" to FORMAT,
            "generator" to linkedMapOf(
                "library" to "drivine4j",
                "version" to version(),
                "language" to if (all.all { it.origin == Origin.JAVA }) "java" else "kotlin",
            ),
            "module" to module,
            "fragments" to fragments.sortedBy { ModelReader.id(it) }.map { reader.fragment(it) },
            "relationshipFragments" to relationshipFragments.sortedBy { ModelReader.id(it) }.map { reader.relationshipFragment(it) },
            "views" to views.sortedBy { ModelReader.id(it) }.map { reader.view(it) },
        )
        val json = ModelJson.write(model)
        resource.use { it.write(json.toByteArray()) }

        // A relative path would be taken from wherever the build happens to run, as Gradle's daemon's.
        options[FILE]?.takeIf { !File(it).isAbsolute }?.let {
            logger.error("Drivine model file: the KSP option $FILE must be an absolute path, not $it.")
            return
        }
        val target = options[FILE]?.let { File(it) }
            ?: layout?.takeIf { it.sourceSet == "main" }?.moduleDir?.resolve(".drivine/model.json")
        if (target == null) {
            // Outside Gradle's layout there is no telling the main source set from the tests'.
            if (layout == null) {
                logger.warn("Drivine model file: written to the resources only. Set the KSP option $FILE to write .drivine/model.json too.")
            }
            return
        }
        // Only when it changes, so that a build with nothing new leaves the file as it is.
        if (target.isFile && target.readText() == json) return
        target.parentFile.mkdirs()
        target.writeText(json)
        logger.info("Drivine model file written to ${target.path}")
    }

    private fun version(): String = ModelFileGenerator::class.java.getResourceAsStream("/org/drivine/codegen/drivine4j-codegen.properties")
        ?.use { stream -> Properties().also { it.load(stream) }.getProperty("version") }
        ?: "unknown"

    internal companion object {
        const val ENABLED = "drivine.model"
        const val MODULE = "drivine.model.module"
        const val FILE = "drivine.model.file"

        const val FORMAT = "drivine-model/1"
        const val RESOURCE_PATH = "META-INF/drivine/model"

        /** Word for word what the format asks every library to write. */
        const val COMMENT = "Drivine model file: this module's fragments, relationship fragments and views, written by the build. " +
            "Drivine Studio reads it to browse the model and query the data. Keep it in source control: the build rewrites it " +
            "when the model changes, and CI checks that it is current. Format: https://github.com/liberation-data/drivine-cypher-spec/tree/main/model"

        val BUILD_FILES = listOf("build.gradle", "build.gradle.kts", "pom.xml")

        /** A module's directory and the source set KSP is processing in it. */
        data class GradleLayout(val moduleDir: File, val sourceSet: String)

        /** Where Gradle's layout `<module>/build/generated/ksp/<set>/resources/…` puts [resource], or null in another layout. */
        fun gradleLayout(resource: File): GradleLayout? {
            val resources = generateSequence(resource.parentFile) { it.parentFile }.firstOrNull { dir ->
                dir.name == "resources" && dir.parentFile?.parentFile?.let { it.name == "ksp" && it.parentFile?.name == "generated" } == true
            } ?: return null
            val sourceSet = resources.parentFile
            val moduleDir = sourceSet.parentFile.parentFile.parentFile.parentFile ?: return null
            return GradleLayout(moduleDir, sourceSet.name)
        }

        /** The nearest directory above [file] that holds a build file. */
        fun buildFileDir(file: File): File? =
            generateSequence(file.parentFile) { it.parentFile }.firstOrNull { dir -> BUILD_FILES.any { File(dir, it).isFile } }
    }
}
