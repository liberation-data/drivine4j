package org.drivine.mapper

/**
 * Interface for mapping a result row (represented as a Map) to a domain object.
 * Similar to Spring JDBC's RowMapper, but for graph database results.
 *
 * Example usage:
 * ```
 * class PersonRowMapper : RowMapper<Person> {
 *     override fun map(row: Map<String, Any?>): Person {
 *         return Person(
 *             uuid = row["uuid"] as String,
 *             firstName = row["firstName"] as String,
 *             lastName = row["lastName"] as String?
 *         )
 *     }
 * }
 *
 * val people = manager.query(
 *     QuerySpecification
 *         .withStatement("MATCH (p:Person) RETURN p")
 *         .mapWith(PersonRowMapper())
 * )
 * ```
 */
interface RowMapper<T> {
    /**
     * Maps a result row to a domain object.
     * @param row The result row as a Map of column names to values
     * @return The mapped domain object
     */
    fun map(row: Map<String, Any?>): T
}