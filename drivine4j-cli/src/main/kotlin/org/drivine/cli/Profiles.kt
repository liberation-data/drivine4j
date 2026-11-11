package org.drivine.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * A profile of `.drivine.yml`: what to connect to, each key optional, as the contract's "Profiles" says. Flags and
 * environment variables override it key by key.
 */
data class Profile(
    val name: String,
    val address: String? = null,
    val type: String? = null,
    val database: String? = null,
    val graph: String? = null,
    val user: String? = null,
    val password: String? = null,
) {
    override fun toString(): String =
        "Profile(name=$name, address=${address?.let { "…" }}, type=$type, database=$database, graph=$graph, user=$user, password=${password?.let { "***" }})"
}

/**
 * The profiles of `~/.drivine.yml` and `./.drivine.yml`. One of the working directory's replaces the home's of the
 * same name as a whole, and its `default:` replaces the home's. A file that holds a password and that its group or
 * others can read is refused, as `~/.pgpass` is by libpq, where the file system has POSIX modes.
 */
object Profiles {

    /** The file's name, in the home directory and in the working directory. */
    const val FILE = ".drivine.yml"

    /** A profile's keys: any other is refused. */
    private val KEYS = setOf("address", "type", "database", "graph", "user", "password")

    private val yaml = ObjectMapper(YAMLFactory())

    /**
     * The profile that [named] names, or, where it is null, the one the files' `default:` names; null where neither names
     * one. Reads `.drivine.yml` in [home], where there is one, then in [dir]. Throws [BadInvocation] for a file that
     * can't be read as profiles, holds a password others can read, or has a key a profile doesn't take, and for a name
     * that names no profile.
     */
    fun pick(named: String?, home: File?, dir: File): Profile? {
        val files = listOfNotNull(home?.let { File(it, FILE) }, File(dir, FILE))
            .filter { it.isFile }
            .distinctBy { it.canonicalFile }
        var default: String? = null
        val profiles = LinkedHashMap<String, Profile>()
        files.forEach { file ->
            val read = read(file)
            read.default?.let { default = it }
            profiles.putAll(read.profiles)
        }
        val name = named ?: default ?: return null
        return profiles[name] ?: throw BadInvocation(
            if (named != null) "drivine: --profile ${Args.shownValue(name)} names no profile in $FILE; see --help"
            else "drivine: the default: of $FILE, ${Args.shownValue(name)}, names no profile; see --help",
        )
    }

    private class Read(val default: String?, val profiles: Map<String, Profile>)

    private fun read(file: File): Read {
        val root: JsonNode? = try {
            yaml.readTree(file)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            // The parser's message may quote the file, which may hold a password: only where it failed is named.
            throw BadInvocation("drivine: can't read $file as profiles: it isn't YAML${e.location()}")
        } catch (e: java.io.IOException) {
            // One the CLI may not read, as one of mode 000: a file's error, not the YAML's.
            throw BadInvocation("drivine: can't read $file: not a file this user can read")
        } catch (e: RuntimeException) {
            throw BadInvocation("drivine: can't read $file as profiles: it isn't YAML")
        }
        if (root == null || root.isMissingNode || root.isNull) return Read(null, emptyMap())
        if (!root.isObject) throw BadInvocation("drivine: $file holds no profiles: give default: and profiles:, as --help says")
        val default = root.get("default")?.let { text(file, "default", it) }
        val section = root.get("profiles")
        if (section != null && !section.isNull && !section.isObject) {
            throw BadInvocation("drivine: profiles: in $file isn't a map of profiles by name")
        }
        val profiles = LinkedHashMap<String, Profile>()
        section?.fields()?.forEach { (name, body) ->
            if (body.isNull) {
                profiles[name] = Profile(name)
                return@forEach
            }
            if (!body.isObject) throw BadInvocation("drivine: the profile ${Args.shownValue(name)} in $file isn't a map of keys")
            body.fieldNames().forEach { key ->
                if (key !in KEYS) {
                    throw BadInvocation("drivine: the profile ${Args.shownValue(name)} in $file has the key ${Args.shownValue(key)}, which isn't one: give ${KEYS.joinToString()}")
                }
            }
            fun key(key: String) = body.get(key)?.takeUnless { it.isNull }?.let { text(file, "$name.$key", it) }
            profiles[name] = Profile(name, key("address"), key("type"), key("database"), key("graph"), key("user"), key("password"))
        }
        if (profiles.values.any { it.password != null }) readableOnlyByOwner(file)
        return Read(default, profiles)
    }

    /** A key's value, which is text: a number or a boolean is taken as YAML writes it, a list or a map is refused. */
    private fun text(file: File, key: String, node: JsonNode): String {
        if (!node.isValueNode) throw BadInvocation("drivine: $key in $file isn't a value")
        return node.asText()
    }

    /** Refuses [file], which holds a password, where its group or others can read it. A file system with no POSIX modes, as Windows', isn't checked. */
    private fun readableOnlyByOwner(file: File) {
        val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) ?: return
        val permissions = view.readAttributes().permissions()
        val others = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        if (permissions.any { it in others }) {
            throw BadInvocation("drivine: $file holds a password, and its group or others can read it: chmod 600 $file")
        }
    }

    /** Where a YAML parser's error is, as ` at line L, column C`, from its location, with nothing of the text it read. */
    private fun Exception.location(): String {
        val location = (this as? com.fasterxml.jackson.core.JsonProcessingException)?.location ?: return ""
        return " at line ${location.lineNr}, column ${location.columnNr}"
    }
}
