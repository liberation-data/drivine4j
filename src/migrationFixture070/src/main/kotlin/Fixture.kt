import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification

/**
 * Writes a graph with drivine4j 0.7.0, the last release of store layout 1: the graph a layout-1 to layout-2 migration is
 * tested on (drivine-cypher-spec#256, drivine4j#832). Compiled and run against 0.7.0 alone, on a classpath of its own
 * (build.gradle's `migrationFixture070`).
 *
 * Usage: `sqlite <path> <graph>`, or `postgres|oracle <jdbc url> <user> <password> <graph>`.
 */
fun main(args: Array<String>) {
    val store = args.first()
    val (type, provider) = when (store) {
        "sqlite" -> DatabaseType.SQLITE to JdbcConnectionProvider.sqlite("fixture", args[1], graphName = args[2])
        "postgres", "oracle" -> {
            val type = if (store == "postgres") DatabaseType.POSTGRES else DatabaseType.ORACLE
            type to JdbcConnectionProvider.pooled("fixture", type, args[1], args[2], args[3], graphName = args[4])
        }
        else -> error("No store '$store': sqlite, postgres or oracle.")
    }
    val pm = NonTransactionalPersistenceManager(provider, "fixture", type, SubtypeRegistry())
    fun cypher(statement: String) = pm.execute(QuerySpecification.withStatement(CypherStatement(statement)))
    pm.constraints.ensureUnique("Person", "name")
    pm.constraints.ensureUnique("Event", "at")
    FIXTURE.forEach(::cypher)
    // Two spellings of one moment: distinct texts in layout 1, so its unique index takes both; one value in layout 2.
    cypher("CREATE (:Event {at: '2024-03-15T10:30:00.500Z'})")
    provider.end()
    println("wrote ${args.last()}")
}

/** The graph, but for the second spelling of the Event's moment, which collides in layout 2. */
private val FIXTURE = listOf(
    "CREATE (:Person {name: 'Ada', born: date('1815-12-10'), height: 1.65, visits: 3})",
    "CREATE (:Person {name: 'Charles', born: date('1791-12-26'), height: 2.0})",
    "CREATE (:Person {name: 'Mary', nick: '2024-03-15'})",
    "CREATE (a:Person {name: 'Bob'})-[:KNOWS {since: date('2020-01-01')}]->(b:Person {name: 'Cy'})",
    "CREATE (:Robot {`\$t`: 1, `\$x`: 'y', name: 'R2'})",
    "CREATE (:Event {at: '2024-03-15T10:30:00.5Z'})",
)
