package org.drivine.manager

//import drivine.cursor.Cursor
//import drivine.cursor.CursorSpecification
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import java.util.*

interface PersistenceManager {

    val database: String
    val type: DatabaseType

    /**
     * Cypher grammar for this database, controlling dialect-specific query generation.
     */
    val grammar: CypherGrammar

    /**
     * Index management (vector / range) for this database.
     *
     * Operations are idempotent and drift-aware, and always execute in auto-commit mode —
     * schema DDL cannot run inside an open data transaction.
     *
     * ```kotlin
     * persistenceManager.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 1536))
     * persistenceManager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
     * ```
     */
    val indexes: IndexManager

    /**
     * Constraint management (uniqueness) for this database.
     *
     * Operations are idempotent and drift-aware, and always execute in auto-commit mode.
     *
     * ```kotlin
     * persistenceManager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
     * ```
     */
    val constraints: ConstraintManager

    /**
     * Queries for a set of results according to the supplied specification.
     * @param spec
     * TODO: Return Map type.
     */
    fun <T: Any> query(spec: QuerySpecification<T>): List<T>

    /**
     * Execute a statement and disregard any results that are returned.
     * @param spec
     */
    fun execute(spec: QuerySpecification<*>)

    /**
     * Executes a list of statements **atomically** — all succeed or none do. When already inside a
     * transaction the statements join it; otherwise they run together in a single transaction on one
     * connection (fewer round trips than N separate [execute] calls). Used by batch operations such as
     * [GraphObjectManager.saveAll].
     *
     * The default loops [execute] (no extra atomicity — a fallback for impls that don't override);
     * the real managers override to provide the single-transaction guarantee.
     */
    fun executeBatch(specs: List<QuerySpecification<*>>) {
        specs.forEach { execute(it) }
    }

    /**
     * Queries for a single result according to the supplied specification. Expects exactly one result or throws.
     * @param spec
     * @throws DrivineError
     */
    fun <T: Any> getOne(spec: QuerySpecification<T>): T

    /**
     * Queries for a single result according to the supplied specification. Expects zero or one result, otherwise
     * throws.
     * @param spec
     * @throws DrivineError
     */
    fun <T: Any> maybeGetOne(spec: QuerySpecification<T>): T?

    /**
     * Queries for a single result according to the supplied specification. Returns Optional.empty() if no result.
     * Expects zero or one result, otherwise throws.
     * @param spec
     * @throws DrivineError
     */
    fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): Optional<T>

    /**
     * Returns an object that streams results.
     * @param spec
     */
//    fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T>

    /**
     * Registers a subtype for polymorphic deserialization.
     * This allows Jackson to correctly deserialize class hierarchies based on Neo4j labels.
     *
     * Example:
     * ```kotlin
     * // Define hierarchy
     * interface SessionUser {
     *     val id: String
     *     val displayName: String
     * }
     *
     * @NodeFragment(labels = ["SessionUser", "GuideUser"])
     * data class GuideUser(
     *     override val id: String,
     *     override val displayName: String,
     *     val guideProgress: Int
     * ) : SessionUser
     *
     * // Register subtype with its Neo4j labels
     * manager.registerSubtype(
     *     SessionUser::class.java,
     *     listOf("SessionUser", "GuideUser"),
     *     GuideUser::class.java
     * )
     *
     * // Now queries using .transform(SessionUser::class.java) will correctly deserialize to GuideUser
     * ```
     *
     * @param baseClass The base class or interface
     * @param labels The Neo4j labels that identify this subtype (order doesn't matter)
     * @param subClass The concrete subtype class
     */
    fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>)
}
