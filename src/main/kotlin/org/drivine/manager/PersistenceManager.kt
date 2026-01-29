package org.drivine.manager

//import drivine.cursor.Cursor
//import drivine.cursor.CursorSpecification
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import java.util.*

interface PersistenceManager {

    /**
     * Unique name of the database, as provided when it was enrolled in the DatabaseRegistry.
     */
    val database: String

    /**
     * Type of the database, as provided when it was enrolled in the DatabaseRegistry.
     */
    val type: DatabaseType

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
