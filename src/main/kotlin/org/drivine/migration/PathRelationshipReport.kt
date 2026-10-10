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
     * of the same type between nodes that can be the same ones, `@ReadOnly` or not, or when a hop of
     * this path or of another path field of the views is stored as such a relationship. Nodes can be
     * the same ones when one kind has every label of the other, or when the views name a kind of node
     * that has the labels of both. A hop that names no label reaches any node, and so does a fragment
     * that has no label.
     */
    fun report(vararg views: Class<*>): List<PathFinding> {
        val declared = stored.declaredFields(views)
        val paths = stored.pathFields(views)
        val labelSets = stored.labelSets(views)
        return paths.map { path ->
            val firstHop = path.relationship.hops.first()
            PathFinding(
                view = path.view,
                field = path.relationship.fieldName,
                type = firstHop.type,
                rootLabels = path.rootLabels,
                targetLabels = path.targetLabels,
                direct = stored.count(path.rootLabels, firstHop.type, path.targetLabels),
                ambiguity = ambiguity(path, declared, paths, labelSets),
                removalStatement = "MATCH ${nodePattern(path.rootLabels)}-[r:${firstHop.type}]->${nodePattern(path.targetLabels)} DELETE r",
            )
        }
    }

    private fun ambiguity(path: ViewField, declared: List<ViewField>, paths: List<ViewField>, labelSets: Set<List<String>>): String? {
        val type = path.relationship.hops.first().type
        val between = "$type from ${named(path.rootLabels)} to ${named(path.targetLabels)}"
        declared.firstOrNull { it.relationship.type == type && it.storesFrom(path.rootLabels, path.targetLabels, labelSets) }?.let {
            return "${it.view.simpleName}.${it.relationship.fieldName} declares $between, so those relationships may be meant."
        }
        // A hop of this path or of another, stored as the old save stored the field: from a root to a node the path can end at.
        return paths.firstNotNullOfOrNull { other ->
            other.pathHops().firstOrNull { it.type == type && it.storesFrom(path.rootLabels, path.targetLabels, labelSets) }?.let { hop ->
                val whose = if (other === path) "the path" else "the path ${other.view.simpleName}.${other.relationship.fieldName}"
                "$whose has a $type hop between ${named(hop.startLabels)} and ${named(hop.endLabels)}, which can be $between, so those relationships may be hops of it."
            }
        }
    }
}

/**
 * The direct relationships that look like a `@GraphPath` field written as one, as
 * [PathRelationshipReport.report] found them.
 *
 * @property type the type of the path's first hop
 * @property direct how many relationships of [type] run from a root straight to a node of the kind the path ends at
 * @property ambiguity why those relationships may be meant, or may be hops of a path; null when the
 *   views give no reason to think so
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
