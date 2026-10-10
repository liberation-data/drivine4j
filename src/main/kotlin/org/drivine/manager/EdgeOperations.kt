package org.drivine.manager

import org.drivine.annotation.Direction
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.query.EdgeStatements
import org.drivine.query.FragmentQueryBuilder
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification

/**
 * Relationships whose type is known only at runtime, between stored nodes that are named by a
 * [NodeRef] and never created. Reached as [GraphObjectOperations.edges].
 *
 * For a relationship that is part of an object's declared shape, use a `@GraphView` with
 * `@GraphRelationship`. This is for the case where the type is data: a graph whose relationship
 * types are not known when the model is written.
 */
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class EdgeOperations internal constructor(
    private val manager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    /**
     * Joins [from] to [to] with a [type] relationship.
     *
     * Both nodes are matched, never created: if either is absent, or lacks a label its [NodeRef]
     * names, nothing is written and the result is false. Neither node is loaded, and neither's own
     * properties are touched. When the relationship is made, or its properties change, both get a new
     * relationship token in their stamp, so a save that replaces a relationship list of either, from an
     * object loaded before this call, is refused. A call that finds the relationship as it is marks neither.
     *
     * Under [RelateMode.MERGE] (the default) there is at most one [type] relationship from [from] to
     * [to], and its [properties] are set whether it was made or found. Under [RelateMode.CREATE] each
     * call makes another. A null property value is left out.
     *
     * @return whether the two nodes were found and joined
     */
    @JvmOverloads
    fun relate(
        from: NodeRef,
        to: NodeRef,
        type: String,
        properties: Map<String, Any?> = emptyMap(),
        mode: RelateMode = RelateMode.MERGE,
    ): Boolean {
        val statement = EdgeStatements.relate(from, to, type, properties, mode)
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(statement.statement)
                .bind(statement.bindings)
                .transform(Long::class.java)
        ) > 0
    }

    /**
     * Removes every [type] relationship from [from] to [to].
     *
     * Neither node is deleted or loaded, and neither's own properties are touched. Both get a new
     * relationship token in their stamp when a relationship was removed.
     *
     * @return how many relationships were removed: 0 when there was none, or either node is absent
     */
    fun unrelate(from: NodeRef, to: NodeRef, type: String): Int = count(EdgeStatements.unrelate(from, to, type))

    /**
     * Removes every [type] relationship that [from] has in [direction].
     *
     * No node is deleted or loaded, and none's own properties are touched. [from], and each node it
     * was joined to, gets a new relationship token in its stamp.
     *
     * @return how many relationships were removed
     */
    @JvmOverloads
    fun unrelateAll(from: NodeRef, type: String, direction: Direction = Direction.OUTGOING): Int =
        count(EdgeStatements.unrelateAll(from, type, direction))

    private fun count(statement: MergeStatement): Int = persistenceManager.getOne(
        QuerySpecification
            .withStatement(statement.statement)
            .bind(statement.bindings)
            .transform(Long::class.java)
    ).toInt()

    /**
     * Loads the [targetClass] nodes joined to [from] by a [type] relationship in [direction].
     *
     * [targetClass] is a `@NodeFragment`. Each related node is returned once, however many such
     * relationships join it. Empty when [from] is absent.
     */
    fun <T : Any> loadRelated(from: NodeRef, type: String, direction: Direction, targetClass: Class<T>): List<T> {
        require(targetClass.isAnnotationPresent(NodeFragment::class.java)) {
            "loadRelated loads a @NodeFragment; ${targetClass.simpleName} is not one."
        }
        manager.autoRegisterSubtypesIfNeeded(targetClass)
        val model = FragmentModel.from(targetClass)
        val match = EdgeStatements.relatedMatch(from, type, direction, model.labels)
        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(FragmentQueryBuilder(model).buildQueryMatching(match.statement))
                .bind(match.bindings)
                .transform(targetClass)
        )
        manager.snapshotResults(targetClass, results)
        return results
    }
}

/**
 * Loads the [T] nodes joined to [from] by a [type] relationship in [direction], with a reified type
 * parameter. See [EdgeOperations.loadRelated].
 */
inline fun <reified T : Any> EdgeOperations.loadRelated(
    from: NodeRef,
    type: String,
    direction: Direction = Direction.OUTGOING,
): List<T> = loadRelated(from, type, direction, T::class.java)
