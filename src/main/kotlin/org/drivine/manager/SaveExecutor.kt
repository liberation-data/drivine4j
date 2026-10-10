package org.drivine.manager

import org.drivine.StaleObjectException
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.StampWrite

/**
 * Runs the statements of a save, in order. A checked statement returns no row when the node is not as
 * it was when the object was loaded, and the save stops there with a [StaleObjectException].
 */
internal class SaveExecutor(private val persistenceManager: PersistenceManager) {

    fun execute(statements: List<MergeStatement>) = statements.forEach { execute(it) }

    /** Runs [statement]. Returns the stamp its node is left with, or null when the statement does not say. */
    fun execute(statement: MergeStatement): String? {
        val spec = QuerySpecification.withStatement(statement.statement).bind(statement.bindings)
        val stamp = statement.stamp
        if (stamp?.returned != true) {
            persistenceManager.execute(spec)
            return null
        }
        val left = persistenceManager.query(spec.transform(String::class.java)).firstOrNull()
        if (left == null && stamp.expected != null) throw staleObject(stamp, stamp.expected)
        return left
    }

    /** Says why a checked save matched nothing: the node is gone, or carries another stamp. */
    private fun staleObject(stamp: StampWrite, expected: String): StaleObjectException {
        // An absent stamp is read as '' so a node without one still gives a row on every engine.
        val found = persistenceManager.query(
            QuerySpecification
                .withStatement("MATCH (n:${stamp.labels} {${stamp.idProperty}: \$id}) RETURN coalesce(n.${Stamps.QUOTED}, '')")
                .bind(mapOf("id" to stamp.id))
                .transform(String::class.java)
        )
        return StaleObjectException(
            type = stamp.fragmentClass,
            id = stamp.id,
            expectedStamp = expected,
            foundStamp = found.firstOrNull()?.takeIf { it.isNotEmpty() },
            deleted = found.isEmpty(),
        )
    }
}
