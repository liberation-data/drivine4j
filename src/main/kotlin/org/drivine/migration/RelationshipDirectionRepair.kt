package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.PersistenceManager
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification

/**
 * Finds and turns round the relationships that a view save wrote the wrong way before 0.1.0.
 *
 * Until then a save wrote every relationship field from the view's root to the target, whatever
 * direction the field declared. A field declared [Direction.INCOMING] reads relationships from the
 * target to the root, so what the save wrote was stored pointing the wrong way and the view did not
 * load it back.
 *
 * [report] looks at the views it is given and counts, for each such field, the relationships that
 * point away from the root. It changes nothing. [repair] turns the relationships of one finding
 * round, keeping their properties.
 *
 * A relationship that points away from the root is not always a mistake: other code may have meant
 * it. [DirectionFinding.ambiguity] says when the views themselves give a reason to think so. Read the
 * report before repairing, and run this once, as a migration: it is not something to do at startup.
 */
class RelationshipDirectionRepair(private val persistenceManager: PersistenceManager) {

    /**
     * One finding for each relationship field declared `INCOMING` that a save writes, in [views] and
     * in the views nested in them. Give every view of the model: a finding is marked ambiguous when
     * another of the views declares the same relationship pointing away from the root.
     */
    fun report(vararg views: Class<*>): List<DirectionFinding> {
        val fields = views.flatMap { fieldsOf(it, mutableSetOf()) }.distinctBy { it.view to it.relationship.fieldName }
        return fields.filter { it.relationship.direction == Direction.INCOMING }.map { field ->
            DirectionFinding(
                view = field.view,
                field = field.relationship.fieldName,
                type = field.relationship.type,
                rootLabels = field.rootLabels,
                targetLabels = field.targetLabels,
                wrongWay = count(field.rootLabels, field.relationship.type, field.targetLabels),
                rightWay = count(field.targetLabels, field.relationship.type, field.rootLabels),
                ambiguity = ambiguity(field, fields),
                sameNodes = sameNodes(field),
            )
        }
    }

    /**
     * Turns round the relationships [finding] counted as pointing the wrong way, [batchSize] to a
     * statement, and returns how many it turned. A relationship's properties go with it. Where one
     * already points the right way between the same two nodes, the two become one.
     *
     * Refused for an ambiguous finding unless [force] is set, and always refused for a field whose
     * root and target can be the same nodes: there a relationship turned round still points away from
     * a root.
     */
    @JvmOverloads
    fun repair(finding: DirectionFinding, force: Boolean = false, batchSize: Int = 10_000): Long {
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        check(!finding.sameNodes) {
            "${finding.view.simpleName}.${finding.field} cannot be repaired: ${finding.ambiguity}"
        }
        check(finding.ambiguity == null || force) {
            "${finding.view.simpleName}.${finding.field} is ambiguous: ${finding.ambiguity} Pass force = true to turn them round anyway."
        }
        val statement = """
            MATCH (root:${finding.rootLabels.joinToString(":")})-[r:${finding.type}]->(target:${finding.targetLabels.joinToString(":")})
            WITH root, r, target LIMIT ${'$'}batch
            MERGE (target)-[turned:${finding.type}]->(root)
            SET turned += properties(r)
            DELETE r
            RETURN count(*)
        """.trimIndent()
        // No more statements than the count needs, so a relationship is never turned twice.
        var turned = 0L
        var remaining = count(finding.rootLabels, finding.type, finding.targetLabels)
        while (remaining > 0) {
            val batch = persistenceManager.getOne(
                QuerySpecification.withStatement(statement).bind(mapOf("batch" to batchSize)).transform(Long::class.java)
            )
            if (batch == 0L) break
            turned += batch
            remaining -= batchSize
        }
        return turned
    }

    private class Field(val view: Class<*>, val relationship: RelationshipModel, val rootLabels: List<String>, val targetLabels: List<String>)

    /** The relationship fields a save writes, in [view] and in the views nested in it. */
    private fun fieldsOf(view: Class<*>, seen: MutableSet<Class<*>>): List<Field> {
        if (!seen.add(view)) return emptyList()
        require(view.isAnnotationPresent(GraphView::class.java)) { "${view.simpleName} is not a @GraphView." }
        val model = GraphViewModel.from(view)
        val rootLabels = FragmentModel.from(model.rootFragment.fragmentType).labels
        return model.relationships.filterNot { it.readOnly }.flatMap { relationship ->
            val target = if (relationship.isRelationshipFragment) {
                requireNotNull(relationship.targetNodeType) { "Relationship fragment '${relationship.fieldName}' has no target" }
            } else {
                relationship.elementType
            }
            val nested = target.isAnnotationPresent(GraphView::class.java)
            val targetFragment = if (nested) GraphViewModel.from(target).rootFragment.fragmentType else target
            listOf(Field(view, relationship, rootLabels, FragmentModel.from(targetFragment).labels)) +
                if (nested) fieldsOf(target, seen) else emptyList()
        }
    }

    /** True when one node can be both the root and the target of [field]: one's labels include the other's. */
    private fun sameNodes(field: Field): Boolean =
        field.rootLabels.containsAll(field.targetLabels) || field.targetLabels.containsAll(field.rootLabels)

    private fun ambiguity(field: Field, all: List<Field>): String? {
        if (sameNodes(field)) {
            return "its root and its target can be the same nodes, so nothing tells a relationship written the wrong way from one that is meant."
        }
        // Another field that stores the same relationship from this field's root to its target.
        val other = all.firstOrNull { it !== field && it.relationship.type == field.relationship.type && storesFrom(it, field.rootLabels, field.targetLabels) }
            ?: return null
        return "${other.view.simpleName}.${other.relationship.fieldName} declares ${field.relationship.type} from " +
            "${field.rootLabels.joinToString(":")} to ${field.targetLabels.joinToString(":")}, so relationships pointing that way may be meant."
    }

    /** Whether [field] reads relationships stored from nodes labelled [from] to nodes labelled [to]. */
    private fun storesFrom(field: Field, from: List<String>, to: List<String>): Boolean = when (field.relationship.direction) {
        Direction.OUTGOING -> field.rootLabels == from && field.targetLabels == to
        Direction.INCOMING -> field.targetLabels == from && field.rootLabels == to
        Direction.UNDIRECTED -> (field.rootLabels == from && field.targetLabels == to) || (field.targetLabels == from && field.rootLabels == to)
    }

    private fun count(from: List<String>, type: String, to: List<String>): Long = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (:${from.joinToString(":")})-[r:$type]->(:${to.joinToString(":")}) RETURN count(r)")
            .transform(Long::class.java)
    )
}

/**
 * The relationships of one `INCOMING` relationship field, as [RelationshipDirectionRepair.report] found them.
 *
 * @property wrongWay how many point from a root to a target: what a save before 0.1.0 wrote
 * @property rightWay how many point from a target to a root, as the field reads them
 * @property ambiguity why the relationships counted in [wrongWay] may be meant; null when the views
 *   give no reason to think so
 */
data class DirectionFinding(
    val view: Class<*>,
    val field: String,
    val type: String,
    val rootLabels: List<String>,
    val targetLabels: List<String>,
    val wrongWay: Long,
    val rightWay: Long,
    val ambiguity: String?,
    internal val sameNodes: Boolean = false,
)
