package org.drivine.manager

import org.drivine.StaleObjectException
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.StampWrite

/**
 * Runs the statements of a save, in order. A checked statement reports how many nodes it matched:
 * none means the node is not as it was when the object was loaded, and the save stops there with a
 * [StaleObjectException].
 */
internal class SaveExecutor(private val persistenceManager: PersistenceManager) {

    fun execute(statements: List<MergeStatement>) = statements.forEach(::execute)

    fun execute(statement: MergeStatement) {
        val spec = QuerySpecification.withStatement(statement.statement).bind(statement.bindings)
        val stamp = statement.stamp
        if (stamp?.expected == null) {
            persistenceManager.execute(spec)
        } else if (persistenceManager.getOne(spec.transform(Int::class.java)) == 0) {
            throw staleObject(stamp, stamp.expected)
        }
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
