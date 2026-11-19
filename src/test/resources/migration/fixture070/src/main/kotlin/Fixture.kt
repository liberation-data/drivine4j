import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification

/**
 * Writes a SQLite graph with drivine4j 0.7.0, the last release of store layout 1: the fixture a layout-1 to layout-2
 * migration is tested on (drivine-cypher-spec#256, drivine4j#832). Usage: fixture <path of the file to write>.
 */
fun main(args: Array<String>) {
    val path = args.single()
    val provider = JdbcConnectionProvider.sqlite("fixture", path, graphName = "drivine")
    val pm = NonTransactionalPersistenceManager(provider, "fixture", DatabaseType.SQLITE, SubtypeRegistry())
    fun cypher(statement: String) = pm.execute(QuerySpecification.withStatement(CypherStatement(statement)))
    pm.constraints.ensureUnique("Person", "name")
    pm.constraints.ensureUnique("Event", "at")
    cypher("CREATE (:Person {name: 'Ada', born: date('1815-12-10'), height: 1.65, visits: 3})")
    cypher("CREATE (:Person {name: 'Charles', born: date('1791-12-26'), height: 2.0})")
    cypher("CREATE (:Person {name: 'Mary', nick: '2024-03-15'})")
    cypher("CREATE (a:Person {name: 'Bob'})-[:KNOWS {since: date('2020-01-01')}]->(b:Person {name: 'Cy'})")
    cypher("CREATE (:Robot {`\$t`: 1, `\$x`: 'y', name: 'R2'})")
    // Two spellings of one moment: distinct texts in layout 1, so its unique index takes both; one value in layout 2.
    cypher("CREATE (:Event {at: '2024-03-15T10:30:00.5Z'})")
    cypher("CREATE (:Event {at: '2024-03-15T10:30:00.500Z'})")
    provider.end()
    println("wrote $path")
}
