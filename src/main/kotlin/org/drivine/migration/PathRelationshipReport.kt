package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.manager.PersistenceManager
import org.drivine.model.Stamps

/**
 * Finds the relationships a view save wrote, before 0.1.0, for a field that reads more than one hop.
 *
 * A `@GraphPath` field reads the nodes at the end of several hops. Until 0.1.0, saving a view the
 * manager had not loaded wrote such a field as one direct relationship, of the first hop's type, from
 * the view's root to each node the field held. Nothing reads those relationships as a path, and a
 * relationship field of the same type and target loads them as if they were its own.
 *
 * [report] counts them and changes nothing. It does not remove them: a direct relationship of that
 * type to that kind of node is often meant, and only someone who knows the data can say. A finding
 * the views give no reason to doubt carries the statement that would remove them.
 *
 * A list read over several hops (`maxDepth` above 1) was written the same way: each node it held,
 * however far away, as one relationship from the root. [reportSeveralHopLists] counts what may be
 * left of those.
 *
 * Both only read, so any [PersistenceManager] serves, inside a transaction or outside one.
 */
class PathRelationshipReport(private val persistenceManager: PersistenceManager) {

    private val stored = StoredRelationships(persistenceManager)

    /**
     * One finding for each `@GraphPath` field in [views] and in the views nested in them. Give every
     * view of the model: a finding is marked ambiguous when one of the views declares a relationship
     * of the same type between nodes that can be the same ones, `@ReadOnly` or not, when one has a
     * `@Count` or `@Aggregate` field that reads relationships of that type from such a root, or when a
     * hop of this path or of another path field of the views is stored as such a relationship. Nodes
     * can be the same ones when one kind has every label of the other, or when the views name a kind
     * of node that has the labels of both. A hop that names no label reaches any node, and so does a
     * fragment that has no label.
     *
     * A view nested in one of [views] is looked at with it, and so is a view that is a subtype a
     * sealed class or a `@JsonSubTypes` names. [unexamined] says what could not be.
     */
    fun report(vararg views: Class<*>): List<PathFinding> {
        val declared = stored.declaredFields(views)
        val paths = stored.pathFields(views)
        val aggregates = stored.aggregateFields(views)
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
                ambiguity = ambiguity(path, declared, paths, aggregates, labelSets),
            )
        }
    }

    /**
     * One finding for each list in [views], and in the views nested in them, that is read over
     * several hops (`maxDepth` above 1). It counts the relationships of the field's type that run
     * from a root straight to a node of the kind the list holds, which is how a save before 0.1.0
     * wrote every node of the list. It changes nothing, and gives no statement to remove them: where
     * the field is read away from the root, the first hop of what it reads is such a relationship
     * too, and nothing in the store tells the two apart.
     */
    fun reportSeveralHopLists(vararg views: Class<*>): List<SeveralHopsFinding> =
        stored.declaredFields(views).filter { it.relationship.readsSeveralHops }.map { field ->
            SeveralHopsFinding(
                view = field.view,
                field = field.relationship.fieldName,
                type = field.relationship.type,
                direction = field.relationship.direction,
                rootLabels = field.rootLabels,
                targetLabels = field.targetLabels,
                direct = stored.count(field.rootLabels, field.relationship.type, field.targetLabels),
            )
        }

    /**
     * What of [views] the reports could not look at: each field whose target is a view that is
     * abstract or an interface, where the class names no subtype and none is among [views]. The view
     * of such a subtype may have fields of its own that would make a finding ambiguous. Empty when
     * everything was looked at.
     */
    fun unexamined(vararg views: Class<*>): List<String> = stored.unexamined(views)

    private fun ambiguity(
        path: ViewField, declared: List<ViewField>, paths: List<ViewField>, aggregates: List<AggregateField>, labelSets: Set<List<String>>,
    ): String? {
        val type = path.relationship.hops.first().type
        val between = "$type from ${named(path.rootLabels)} to ${named(path.targetLabels)}"
        declared.firstOrNull { it.relationship.type == type && it.storesFrom(path.rootLabels, path.targetLabels, labelSets) }?.let {
            return "${it.view.simpleName}.${it.relationship.fieldName} declares $between, so those relationships may be meant."
        }
        // A count or an aggregate names no target: it reads every relationship of the type on that side of its root.
        aggregates.firstOrNull { it.aggregate.type == type && it.storesFrom(path.rootLabels, path.targetLabels, labelSets) }?.let {
            return "${it.view.simpleName}.${it.aggregate.fieldName} reads $between, so those relationships may be meant."
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
 * @property view the view that declares the path field
 * @property field the name of the path field
 * @property type the type of the path's first hop
 * @property rootLabels the labels of the view's root; none when its fragment has no label
 * @property targetLabels the labels of the kind of node the path ends at
 * @property direct how many relationships of [type] run from a root straight to a node of the kind the path ends at
 * @property ambiguity why those relationships may be meant, or may be hops of a path; null when the
 *   views give no reason to think so
 */
data class PathFinding(
    val view: Class<*>,
    val field: String,
    val type: String,
    val rootLabels: List<String>,
    val targetLabels: List<String>,
    val direct: Long,
    val ambiguity: String?,
) {
    /**
     * Cypher that deletes every relationship counted in [direct], those a save wrote and any that
     * are meant alike: nothing in the store tells them apart. It gives the node at each end a new
     * relationship token in its stamp, as any change to a node's relationships does, so a save that
     * replaces a list loaded before the removal is refused. It is one statement and not batched, so
     * it deletes them all in one transaction.
     *
     * Null when the finding is ambiguous, and when the root or the kind of node the path ends at has
     * no label: the statement would then match relationships between nodes of any kind.
     * [forcedRemovalStatement] gives it all the same.
     */
    val removalStatement: String?
        get() = if (ambiguity == null && rootLabels.isNotEmpty() && targetLabels.isNotEmpty()) forcedRemovalStatement() else null

    /**
     * The statement [removalStatement] withholds, for someone who knows that every relationship
     * counted in [direct] is one to remove. Where an end has no label it matches a node of any kind
     * at that end: every relationship of [type] from any node, or to any node.
     */
    fun forcedRemovalStatement(): String =
        "MATCH ${nodePattern(rootLabels, "root")}-[r:$type]->${nodePattern(targetLabels, "target")} " +
            "SET ${Stamps.linksClause("root")}, ${Stamps.linksClause("target")} DELETE r"
}

/**
 * The direct relationships that may be what a save before 0.1.0 wrote for a list read over several
 * hops, as [PathRelationshipReport.reportSeveralHopLists] found them.
 *
 * @property view the view that declares the list
 * @property field the name of the list's field
 * @property type the relationship type the list is read over
 * @property direction the direction the field declares. Where it is `INCOMING`, a relationship
 *   counted in [direct] is not one the field reads: it points away from the root. Otherwise the
 *   first hop of what the field reads is counted too
 * @property rootLabels the labels of the view's root
 * @property targetLabels the labels of the kind of node the list holds
 * @property direct how many relationships of [type] run from a root straight to a node of that kind
 */
data class SeveralHopsFinding(
    val view: Class<*>,
    val field: String,
    val type: String,
    val direction: Direction,
    val rootLabels: List<String>,
    val targetLabels: List<String>,
    val direct: Long,
)
