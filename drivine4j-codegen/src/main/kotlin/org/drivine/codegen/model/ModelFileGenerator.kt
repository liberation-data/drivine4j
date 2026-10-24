package org.drivine.codegen.model

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Origin
import org.drivine.codegen.model.ModelFiles.ENABLED
import org.drivine.codegen.model.ModelFiles.FILE
import org.drivine.codegen.model.ModelFiles.MODULE
import org.drivine.codegen.model.ModelFiles.RESOURCE_PATH
import org.drivine.codegen.model.ModelFiles.buildFileDir
import java.io.File

/**
 * Writes the module's model file: its fragments, relationship fragments and views, which Drivine
 * Studio reads. The format is drivine-cypher-spec's `model/`; [ModelFiles], which the Java processor
 * shares, writes it from the entries [ModelReader] reads.
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

    /**
     * Writes the model of [fragments], [relationshipFragments] and [views]. The previous file's
     * entries for classes the code no longer declares stay in it, marked removed; those for classes
     * KSP has [deferred] stay as they were, since they are still declared. [sources] are the
     * module's source files, from which a module with nothing left to describe is found: only when
     * they are a main source set's.
     */
    fun generate(
        fragments: List<KSClassDeclaration>,
        relationshipFragments: List<KSClassDeclaration>,
        views: List<KSClassDeclaration>,
        deferred: Set<String> = emptySet(),
        sources: List<File> = emptyList(),
    ) {
        if (options[ENABLED]?.lowercase() == "false") return
        // A relative path would be taken from wherever the build happens to run, as Gradle's daemon's.
        options[FILE]?.takeIf { !File(it).isAbsolute }?.let {
            logger.error("Drivine model file: the KSP option $FILE must be an absolute path, not $it.")
            return
        }
        val file = options[FILE]?.let { File(it) }
        val all = fragments + relationshipFragments + views
        // With nothing declared there is nothing to write unless a file before this one had something,
        // which is now removed. The module is found from its sources, as there is no resource to go by,
        // and only from its main ones: a test source set, as Maven's with drivine.model.file, leaves it be.
        val mainFile = sources.firstNotNullOfOrNull { mainModuleFile(it) }
        if (all.isEmpty() && (mainFile == null || previous(file ?: mainFile) == null)) {
            return
        }

        // The resource first: where KSP puts it says where the module is.
        val resource = codeGenerator.createNewFileByPath(
            Dependencies(aggregating = true, *all.mapNotNull { it.containingFile }.distinct().toTypedArray()),
            RESOURCE_PATH,
            "json",
        )
        val resourceFile = codeGenerator.generatedFile.firstOrNull { it.invariantSeparatorsPath.endsWith("$RESOURCE_PATH.json") }
        val layout = resourceFile?.let { gradleLayout(it) }
        val module = options[MODULE] ?: layout?.moduleDir?.name ?: resourceFile?.let { buildFileDir(it) }?.name ?: "module"
        val target = file
            ?: layout?.takeIf { it.sourceSet == "main" }?.moduleDir?.resolve(".drivine/model.json")
        val previous = previous(target)

        val reader = ModelReader()
        val model = ModelFiles.model(
            module = module,
            language = if (all.isEmpty()) null else if (all.all { it.origin == Origin.JAVA }) "java" else "kotlin",
            fragments = fragments.map { reader.fragment(it) },
            relationshipFragments = relationshipFragments.map { reader.relationshipFragment(it) },
            views = views.map { reader.view(it) },
            previous = previous,
            deferred = deferred,
        )
        val json = ModelJson.write(model)
        resource.use { it.write(json.toByteArray()) }

        if (target == null) {
            // Outside Gradle's layout there is no telling the main source set from the tests'.
            if (layout == null) {
                logger.warn("Drivine model file: written to the resources only. Set the KSP option $FILE to write .drivine/model.json too.")
            }
            return
        }
        if (ModelFiles.write(target, json)) logger.info("Drivine model file written to ${target.path}")
    }

    private fun previous(file: File?): Map<*, *>? = ModelFiles.previous(file) { logger.warn(it) }

    internal companion object {
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

        /**
         * `.drivine/model.json` for the module whose main source set holds [source], from Gradle's
         * layout `<module>/src/main/…`, or null when [source] is not in one.
         */
        fun mainModuleFile(source: File): File? {
            val main = generateSequence(source.parentFile) { it.parentFile }
                .firstOrNull { it.name == "main" && it.parentFile?.name == "src" } ?: return null
            return main.parentFile.parentFile?.resolve(".drivine/model.json")
        }
    }
}
