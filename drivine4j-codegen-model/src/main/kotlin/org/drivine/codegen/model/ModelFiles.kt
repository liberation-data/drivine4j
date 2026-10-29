package org.drivine.codegen.model

import java.io.File
import java.util.Properties
import java.util.function.Consumer

/**
 * What the KSP and the Java code generators share in writing a module's model file, the format in
 * drivine-cypher-spec's `model/`, which Drivine Studio reads. Each generator reads its own kind of
 * class into the format's entries; this turns them into the file, keeps the previous file's entries
 * the code no longer declares, marked removed, and writes the file only when it changes.
 */
object ModelFiles {

    /** The option that, set to `false`, writes no model file. */
    const val ENABLED = "drivine.model"

    /** The option naming the module in the file. */
    const val MODULE = "drivine.model.module"

    /** The option giving an absolute path to write the file to, in place of `.drivine/model.json`. */
    const val FILE = "drivine.model.file"

    const val FORMAT = "drivine-model/1"

    /** Where the jar carries the file, without its extension. */
    const val RESOURCE_PATH = "META-INF/drivine/model"

    /** Word for word what the format asks every library to write. */
    const val COMMENT = "Drivine model file: this module's fragments, relationship fragments and views, written by the build. " +
        "Drivine Studio reads it to browse the model and query the data. Keep it in source control: the build rewrites it " +
        "when the model changes, and CI checks that it is current. Format: https://github.com/liberation-data/drivine-cypher-spec/tree/main/model"

    @JvmField
    val BUILD_FILES = listOf("build.gradle", "build.gradle.kts", "pom.xml")

    /** This library's version, which the file names as its generator's. */
    @JvmStatic
    fun version(): String = ModelFiles::class.java.getResourceAsStream("/org/drivine/codegen/model/drivine4j-codegen-model.properties")
        ?.use { stream -> Properties().also { it.load(stream) }.getProperty("version") }
        ?: "unknown"

    /**
     * The model file [file] holds, or null when there is none, or it is not one this library can
     * read, which [warn] is told of: a file of another format is written over, as one the module
     * never had.
     */
    @JvmStatic
    fun previous(file: File?, warn: Consumer<String>): Map<*, *>? {
        if (file == null || !file.isFile) return null
        val model = runCatching { ModelJson.read(file.readText()) }.getOrNull() as? Map<*, *>
        if (model?.get("format") != FORMAT) {
            warn.accept("Drivine model file: ${file.path} is not a $FORMAT file, so nothing in it is kept.")
            return null
        }
        return model
    }

    /**
     * The whole model file: the envelope, and the [fragments], [relationshipFragments] and [views]
     * the code declares, with the [previous] file's entries it no longer does, marked removed. An
     * entry for a class the processor has [deferred] is still declared, and is kept as it was. With
     * no [language], as when nothing is declared, the file keeps the language the previous one had.
     */
    @JvmStatic
    fun model(
        module: String,
        language: String?,
        fragments: List<Map<String, Any?>>,
        relationshipFragments: List<Map<String, Any?>>,
        views: List<Map<String, Any?>>,
        previous: Map<*, *>?,
        deferred: Set<String>,
    ): Map<String, Any?> = linkedMapOf(
        "\$comment" to COMMENT,
        "format" to FORMAT,
        "generator" to linkedMapOf(
            "library" to "drivine4j",
            "version" to version(),
            "language" to (language ?: (previous?.get("generator") as? Map<*, *>)?.get("language") ?: "kotlin"),
        ),
        "module" to module,
        "fragments" to withRemoved(fragments, previous?.get("fragments"), deferred),
        "relationshipFragments" to withRemoved(relationshipFragments, previous?.get("relationshipFragments"), deferred),
        "views" to withRemoved(views, previous?.get("views"), deferred),
    )

    /**
     * Writes [json] to [target] when it holds something else, so that a build with nothing new leaves
     * the file as it is. Whether it wrote.
     */
    @JvmStatic
    fun write(target: File, json: String): Boolean {
        if (target.isFile && target.readText() == json) return false
        target.absoluteFile.parentFile.mkdirs()
        target.writeText(json)
        return true
    }

    /**
     * The [current] entries, and the [previous] file's entries the code no longer declares,
     * marked removed, sorted by id. An entry [deferred] by the processor is still declared, and is
     * kept as it was.
     */
    @JvmStatic
    fun withRemoved(current: List<Map<String, Any?>>, previous: Any?, deferred: Set<String>): List<Map<String, Any?>> {
        val ids = current.map { it["id"] }.toSet()
        val kept = (previous as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["id"] is String && it["id"] !in ids }
            .map { entry ->
                @Suppress("UNCHECKED_CAST")
                if (entry["id"] in deferred) entry as Map<String, Any?> else removed(entry)
            }
        return (current + kept).sortedBy { it["id"] as String }
    }

    /** [entry] marked removed, the mark after its name, where the format lists it. */
    private fun removed(entry: Map<*, *>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        entry.forEach { (key, value) ->
            if (key == "removed") return@forEach
            out[key as String] = value
            if (key == "name") out["removed"] = true
        }
        out.putIfAbsent("removed", true)
        return out
    }

    /** The nearest directory above [file] that holds a build file. */
    @JvmStatic
    fun buildFileDir(file: File): File? =
        generateSequence(file.parentFile) { it.parentFile }.firstOrNull { dir -> BUILD_FILES.any { File(dir, it).isFile } }
}
