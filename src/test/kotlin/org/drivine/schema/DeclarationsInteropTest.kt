package org.drivine.schema

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.connection.ConnectionProviderBuilder
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * drivine4j's half of the run that checks a store one library indexed is up to date for the other two
 * (liberation-data/drivine-cypher-spec#135). For each case of `declarations.yaml`, on a graph of its own, it declares the
 * case in drivine4j's API ([DeclarationCases]), enforces it, and ensures its runtime items. It asserts nothing: it writes
 * a report of each case's statements and failure, and the run's driver judges the stores themselves. Run only by that
 * driver, which sets the environment:
 *
 * - `INTEROP_STORE`: `sqlite`, `postgres` or `oracle`;
 * - `INTEROP_GRAPH`: the prefix of each case's graph, `<prefix>_<index>`;
 * - `INTEROP_PHASE`: `write` or `check`, for the report;
 * - `INTEROP_CASES`: the path of `declarations.yaml`;
 * - `INTEROP_REPORT`: the path of the report to write;
 * - `INTEROP_HOST`, `INTEROP_PORT`, `INTEROP_USER`, `INTEROP_PASSWORD`: the server (not read on SQLite);
 * - `INTEROP_DATABASE`: the database on Postgres, the service on Oracle, the directory of the files on SQLite (one file
 *   for each case, `<graph>.db`).
 */
@EnabledIfEnvironmentVariable(named = "INTEROP_PHASE", matches = "write|check")
class DeclarationsInteropTest {

    private companion object {
        /** Oracle's listener refusing a session while the store's sessions are taken. */
        val LISTENER = listOf("ORA-12516", "ORA-12519", "ORA-12520")
    }

    private fun env(name: String): String = requireNotNull(System.getenv(name)) { "$name is not set" }

    @Test
    fun `each case is enforced on a graph of its own`() {
        val store = env("INTEROP_STORE")
        val prefix = env("INTEROP_GRAPH")
        val yaml = ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(File(env("INTEROP_CASES")))
        val declared = DeclarationCases.all.associateBy { it.first }
        val reports = yaml["cases"].mapIndexed { index, case ->
            val name = case["case"].textValue()
            val graph = "${prefix}_$index"
            val (_, catalogs, ensured) = declared[name] ?: return@mapIndexed mapOf("case" to name, "graph" to graph, "failure" to "no declaration in drivine4j")
            val statements = mutableListOf<Map<String, Any?>>()
            val failure = listenerRetried { traced(statements) {
                val registry = DatabaseRegistry(DataSourceMap())
                val provider = builder(registry, store, graph).register("default")
                try {
                    // The graph opened first, as an application's first use opens it, so its tables are there however
                    // little the case declares: a catalog with no items connects to nothing.
                    provider.connect().release()
                    val schema = SchemaManager(PersistenceManagerFactory(registry, TransactionContextHolder(registry)), registry, catalogs)
                    schema.enforce()
                    if (ensured.isNotEmpty()) schema.ensure(DeclarationCases.RUNTIME_OWNER, ensured)
                } finally {
                    provider.end()
                }
            } }
            mapOf("case" to name, "graph" to graph, "failure" to failure, "statements" to statements)
        }
        File(env("INTEROP_REPORT")).writeText(
            ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf("library" to "drivine4j", "store" to store, "phase" to env("INTEROP_PHASE"), "cases" to reports),
            ),
        )
    }

    private fun builder(registry: DatabaseRegistry, store: String, graph: String): ConnectionProviderBuilder {
        val builder = ConnectionProviderBuilder(registry).defaultGraphPath(graph)
        return when (store) {
            "sqlite" -> builder.withType(DatabaseType.SQLITE).databaseName(File(env("INTEROP_DATABASE"), "$graph.db").path)
            "postgres", "oracle" -> builder
                .withType(if (store == "postgres") DatabaseType.POSTGRES else DatabaseType.ORACLE)
                .host(env("INTEROP_HOST")).port(env("INTEROP_PORT").toInt())
                .userName(env("INTEROP_USER")).password(env("INTEROP_PASSWORD"))
                .databaseName(env("INTEROP_DATABASE"))
            else -> error("INTEROP_STORE is $store: sqlite, postgres or oracle")
        }
    }

    /**
     * Runs [attempt] again, up to 3 times, 10 seconds apart, while it fails at Oracle's listener: a container of Oracle Free
     * takes a while to free the sessions of the pools each case opens and ends, and refuses one meanwhile (`ORA-12516`).
     * That is the store's capacity, not what the case declares, so it is not a refusal.
     */
    private fun listenerRetried(attempt: () -> String?): String? {
        var failure = attempt()
        repeat(3) {
            if (failure == null || LISTENER.none { failure!!.contains(it) }) return failure
            Thread.sleep(10_000)
            failure = attempt()
        }
        return failure
    }

    /**
     * Runs [run] with each schema statement recorded in [statements], and gives its failure, or null. What an attempt
     * before recorded is cleared first, so a case retried at the listener reports the statements of its last attempt only.
     */
    private fun traced(statements: MutableList<Map<String, Any?>>, run: () -> Unit): String? {
        synchronized(statements) { statements.clear() }
        SchemaStatementTrace.observer = object : SchemaStatementTrace.Observer {
            override fun ran(
                source: Any, path: SchemaStatementTrace.Path, kind: SchemaStatementTrace.Kind, statement: String,
                parameters: Map<String, Any?>, failure: Throwable?,
            ) {
                synchronized(statements) {
                    statements += mapOf("path" to path.name, "statement" to statement, "failure" to failure?.message)
                }
            }

            override fun pollStarted(source: Any, what: String) {}

            override fun pollEnded(source: Any, what: String, outcome: String?, failure: Throwable?) {}
        }
        return try {
            run()
            null
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message}"
        } finally {
            SchemaStatementTrace.observer = null
        }
    }
}
