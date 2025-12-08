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
     * This allows Jackson to correctly deserialize class hierarchies based on Neo4j labels or type properties.
     *
     * Example:
     * ```kotlin
     * // Define hierarchy
     * sealed class WebUser {
     *     abstract val id: String
     * }
     * data class RegisteredUser(override val id: String, val email: String) : WebUser()
     * data class AnonymousUser(override val id: String, val sessionId: String) : WebUser()
     *
     * // Register subtypes
     * manager.registerSubtype(WebUser::class.java, "RegisteredUser", RegisteredUser::class.java)
     * manager.registerSubtype(WebUser::class.java, "AnonymousUser", AnonymousUser::class.java)
     *
     * // Now queries using .transform(WebUser::class.java) will correctly deserialize to subtypes
     * ```
     *
     * @param baseClass The base class or interface
     * @param name The subtype name (should match Neo4j label or type property)
     * @param subClass The concrete subtype class
     */
    fun registerSubtype(baseClass: Class<*>, name: String, subClass: Class<*>)

    /**
     * Registers multiple subtypes at once for a base class.
     *
     * Example:
     * ```kotlin
     * manager.registerSubtypes(
     *     WebUser::class.java,
     *     "RegisteredUser" to RegisteredUser::class.java,
     *     "AnonymousUser" to AnonymousUser::class.java
     * )
     * ```
     *
     * @param baseClass The base class or interface
     * @param subtypes Vararg of name-to-class pairs
     */
    fun registerSubtypes(baseClass: Class<*>, vararg subtypes: Pair<String, Class<*>>)
}
