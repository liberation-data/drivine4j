package org.drivine.cli

import java.net.URI
import java.net.URISyntaxException

/**
 * A store's address, read from the URI the contract's table gives for it ("The address"): which store, where, and the
 * database, service or file, with the user and the password where the address holds them.
 *
 * @property protocol the Bolt scheme the Neo4j driver is given, as `neo4j+s`, for Neo4j and Memgraph; null elsewhere
 * @property database Postgres's database, Oracle's service or SQLite's path; null where the address names none
 */
data class Address(
    val store: StoreType,
    val protocol: String? = null,
    val host: String = "",
    val port: Int? = null,
    val database: String? = null,
    val user: String? = null,
    val password: String? = null,
) {
    /** Whether this is `sqlite::memory:`, a database that lasts as long as the run. */
    val inMemory: Boolean get() = store == StoreType.SQLITE && database == MEMORY

    override fun toString(): String =
        "Address(store=$store, protocol=$protocol, host=$host, port=$port, database=$database, user=$user, password=${password?.let { "***" }})"

    companion object {
        /** SQLite's name for a database in memory, after `sqlite:`. */
        const val MEMORY = ":memory:"

        /** The Bolt schemes, which leave the store to `--type`: Neo4j unless it says Memgraph. */
        private val BOLT = setOf("bolt", "bolt+s", "bolt+ssc")

        /** The `neo4j://` schemes, which route, and which only Neo4j speaks. */
        private val NEO4J = setOf("neo4j", "neo4j+s", "neo4j+ssc")

        /**
         * Reads [text] as an address. [type] is `--type`'s, which only a Bolt address leaves room for: a type the scheme
         * contradicts is refused. An address [fromCommandLine], given with `-a`, may not hold a password, which would show
         * in `ps` and the shell's history, as `-p` would; one from `DRIVINE_ADDRESS` may.
         */
        fun parse(text: String, type: StoreType?, fromCommandLine: Boolean): Address {
            if (text.startsWith("sqlite:")) {
                val path = text.removePrefix("sqlite:")
                if (path.isBlank()) throw BadInvocation("drivine: give SQLite's file, as sqlite:path, or sqlite::memory:")
                return Address(agreeing(StoreType.SQLITE, type, text), database = path)
            }
            // The address as a refusal prints it, with no password: DRIVINE_ADDRESS's may hold one.
            val shown = shown(text)
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                throw BadInvocation("drivine: the address $shown isn't a URI: ${e.reason}")
            }
            val scheme = uri.scheme?.lowercase() ?: throw BadInvocation("drivine: the address $shown has no scheme, such as neo4j:// or postgresql://")
            val (store, protocol) = when (scheme) {
                in BOLT -> when (type) {
                    null, StoreType.NEO4J -> StoreType.NEO4J
                    StoreType.MEMGRAPH -> StoreType.MEMGRAPH
                    else -> throw BadInvocation("drivine: --type ${type.flag} doesn't speak Bolt, as the address $shown does")
                } to scheme
                in NEO4J -> agreeing(StoreType.NEO4J, type, shown) to scheme
                "memgraph" -> agreeing(StoreType.MEMGRAPH, type, shown) to "bolt"
                "falkordb" -> agreeing(StoreType.FALKORDB, type, shown) to null
                "postgresql", "postgres" -> agreeing(StoreType.POSTGRES, type, shown) to null
                "oracle" -> agreeing(StoreType.ORACLE, type, shown) to null
                else -> throw BadInvocation("drivine: the address's scheme $scheme:// names no store; see --help")
            }
            // java.net.URI reads no host, port or user from an authority whose host it won't take as a server's, such as
            // a Docker Compose service's name with a `_` in it, so the authority is read here where it doesn't.
            val authority = uri.rawAuthority ?: throw BadInvocation("drivine: the address $shown names no host")
            // Not dropped unread: a ?sslmode=require that the CLI didn't pass on would connect without TLS.
            if (uri.rawQuery != null || uri.rawFragment != null) {
                throw BadInvocation("drivine: the address $shown has a ?query or a #fragment, which this build doesn't read; see --help")
            }
            val hostAndPort = authority.substringAfterLast('@')
            val host = (uri.host ?: hostAndPort.substringBefore(':')).ifEmpty { throw BadInvocation("drivine: the address $shown names no host") }
            val port = if (uri.host != null) uri.port.takeIf { it != -1 } else hostAndPort.substringAfter(':', "").ifEmpty { null }?.let {
                it.toIntOrNull() ?: throw BadInvocation("drivine: the address $shown has no port number after its host")
            }
            val (user, password) = (uri.rawUserInfo ?: authority.substringBeforeLast('@', "").ifEmpty { null })?.let { info ->
                decoded(info.substringBefore(':')).ifEmpty { null } to (if (':' in info) decoded(info.substringAfter(':')) else null)
            } ?: (null to null)
            if (password != null && fromCommandLine) throw BadInvocation(Args.PASSWORD_REFUSED)
            val database = uri.path?.removePrefix("/")?.ifEmpty { null }
            if (database != null && store !in setOf(StoreType.POSTGRES, StoreType.ORACLE)) {
                throw BadInvocation("drivine: a ${store.flag} address names no database: $shown")
            }
            // The contract lets -d name the service where the address doesn't: until -d is here, the address must.
            if (database == null && store == StoreType.ORACLE) {
                throw BadInvocation("drivine: give Oracle's service in the address, as oracle://host:port/FREEPDB1; -d comes later")
            }
            return Address(
                store = store,
                protocol = protocol,
                host = host,
                port = port,
                database = database,
                user = user,
                password = password,
            )
        }

        /** [text] with the password its authority holds, if any, as `***`. */
        private fun shown(text: String): String {
            val start = text.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return text
            // The last `@`, and not the last before a `/`: a password may hold a `/`, unescaped.
            val at = text.lastIndexOf('@')
            val colon = text.indexOf(':', start)
            return if (at < start || colon < 0 || colon > at) text else text.substring(0, colon + 1) + "***" + text.substring(at)
        }

        /** [store], the one the scheme names, where `--type` gives none or the same one. */
        private fun agreeing(store: StoreType, type: StoreType?, text: String): StoreType {
            if (type != null && type != store) throw BadInvocation("drivine: --type ${type.flag} contradicts the address $text, which is ${store.flag}'s")
            return store
        }

        private fun decoded(text: String): String = java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)
    }
}
