package org.drivine.migration

import org.drivine.manager.PersistenceManager

/**
 * Finds the relationships a view save wrote for a `@GraphPath` field before 0.1.0.
 *
 * A path field reads the nodes at the end of several hops. Until 0.1.0, saving a view the manager
 * had not loaded wrote such a field as one direct relationship, of the first hop's type, from the
 * view's root to each node the field held. Nothing reads those relationships as a path, and a
 * relationship field of the same type and target loads them as if they were its own.
 *
 * [report] counts them and changes nothing. It does not remove them: a direct relationship of that
 * type to that kind of node is often meant, and only someone who knows the data can say. Each
 * finding carries the statement that would remove them.
 */
class PathRelationshipReport(private val persistenceManager: PersistenceManager) {

    private val stored = StoredRelationships(persistenceManager)

    /**
     * One finding for each `@GraphPath` field in [views] and in the views nested in them. Give every
     * view of the model: a finding is marked ambiguous when one of the views declares a relationship
     * of the same type between nodes that can be the same ones, `@ReadOnly` or not, or names a kind of
     * node that the first hop can reach and the path can end at.
     */
    fun report(vararg views: Class<*>): List<PathFinding> {
        val declared = stored.declaredFields(views)
        val labelSets = stored.labelSets(views)
        return stored.pathFields(views).map { path ->
            val firstHop = path.relationship.hops.first()
            val root = path.rootLabels.joinToString(":")
            val target = path.targetLabels.joinToString(":")
            PathFinding(
                view = path.view,
                field = path.relationship.fieldName,
                type = firstHop.type,
                rootLabels = path.rootLabels,
                targetLabels = path.targetLabels,
                direct = stored.count(path.rootLabels, firstHop.type, path.targetLabels),
                ambiguity = ambiguity(path, declared, labelSets),
                removalStatement = "MATCH (:$root)-[r:${firstHop.type}]->(:$target) DELETE r",
            )
        }
    }

    private fun ambiguity(path: ViewField, declared: List<ViewField>, labelSets: Set<List<String>>): String? {
        val firstHop = path.relationship.hops.first()
        val target = path.targetLabels.joinToString(":")
        val between = "${firstHop.type} from ${path.rootLabels.joinToString(":")} to $target"
        declared.firstOrNull { it.relationship.type == firstHop.type && it.storesFrom(path.rootLabels, path.targetLabels) }?.let {
            return "${it.view.simpleName}.${it.relationship.fieldName} declares $between, so those relationships may be meant."
        }
        val reached = firstHop.intermediateLabel
            ?: return "the path's first hop names no label, so it may itself reach a $target node."
        if (reached in path.targetLabels) return "the path's first hop reaches a $reached node, which is also what the path ends at."
        // A kind of node that has the first hop's label and every label of the path's end.
        return labelSets.firstOrNull { reached in it && it.containsAll(path.targetLabels) }?.let {
            "the path's first hop reaches a $reached node, and a ${it.joinToString(":")} node is also a $target node, so the first hop may itself end at one."
        }
    }
}

/**
 * The direct relationships that look like a `@GraphPath` field written as one, as
 * [PathRelationshipReport.report] found them.
 *
 * @property type the type of the path's first hop
 * @property direct how many relationships of [type] run from a root straight to a node of the kind the path ends at
 * @property ambiguity why those relationships may be meant; null when the views give no reason to think so
 * @property removalStatement Cypher that deletes every relationship counted in [direct], those a
 *   save wrote and any that are meant alike: nothing in the store tells them apart. It is one
 *   statement and not batched, so it deletes them all in one transaction
 */
data class PathFinding(
    val view: Class<*>,
    val field: String,
    val type: String,
    val rootLabels: List<String>,
    val targetLabels: List<String>,
    val direct: Long,
    val ambiguity: String?,
    val removalStatement: String,
)
