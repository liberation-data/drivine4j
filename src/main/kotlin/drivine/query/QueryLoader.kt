package drivine.query

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths


object QueryLoader {
    @Throws(IOException::class)
    fun loadQuery(queryName: String): String {
        val queryPath = "queries/$queryName.cypher"
        return String(Files.readAllBytes(Paths.get(QueryLoader::class.java.classLoader.getResource(queryPath).toURI())))
    }
}
