package org.drivine.query

import java.io.FileNotFoundException
import java.io.IOException


object QueryLoader {
    /**
     * Loads `queries/[queryName].cypher` from the classpath and returns its text (UTF-8).
     *
     * Reads via [ClassLoader.getResourceAsStream] so it works both when resources are on the
     * filesystem (dev/tests) **and** when packaged inside a jar — unlike `Paths.get(resource.toURI())`,
     * which throws `FileSystemNotFoundException` for a `jar:` URI.
     */
    @Throws(IOException::class)
    fun loadQuery(queryName: String): String {
        val queryPath = "queries/$queryName.cypher"
        return (QueryLoader::class.java.classLoader.getResourceAsStream(queryPath)
            ?: throw FileNotFoundException("Query resource not found on classpath: $queryPath"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
