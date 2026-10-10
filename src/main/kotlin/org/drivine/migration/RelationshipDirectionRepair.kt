package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.manager.PersistenceManager
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
 * round, keeping their properties unless one already points the right way.
 *
 * A relationship that points away from the root is not always a mistake: other code may have meant
 * it. [DirectionFinding.ambiguity] says when the views themselves give a reason to think so. Read the
 * report before repairing, and run this once, as a migration: it is not something to do at startup.
 */
class RelationshipDirectionRepair(private val persistenceManager: PersistenceManager) {

    private val stored = StoredRelationships(persistenceManager)

    /**
     * One finding for each relationship field declared `INCOMING` that a save writes, in [views] and
     * in the views nested in them. Give every view of the model: a finding is marked ambiguous when
     * another of the views declares the same relationship pointing away from the root, between nodes
     * that can be the same ones. A `@ReadOnly` field counts: other code writes what it loads.
     */
    fun report(vararg views: Class<*>): List<DirectionFinding> {
        val fields = stored.declaredFields(views)
        return fields.filter { !it.relationship.readOnly && it.relationship.direction == Direction.INCOMING }.map { field ->
            DirectionFinding(
                view = field.view,
                field = field.relationship.fieldName,
                type = field.relationship.type,
                rootLabels = field.rootLabels,
                targetLabels = field.targetLabels,
                wrongWay = stored.count(field.rootLabels, field.relationship.type, field.targetLabels),
                rightWay = stored.count(field.targetLabels, field.relationship.type, field.rootLabels),
                ambiguity = ambiguity(field, fields),
            )
        }
    }

    /**
     * Turns round the relationships [finding] counted as pointing the wrong way, [batchSize] to a
     * statement, and returns how many it turned. A relationship's properties go with it. Where one
     * already points the right way between the same two nodes, the two become one and the one that
     * already pointed the right way wins: it keeps its properties as they are, and those of the
     * relationship turned round are dropped. It was written since the upgrade, so it is the newer.
     *
     * Refused for an ambiguous finding unless [force] is set, and always refused for a finding that
     * is not [DirectionFinding.repairable]: its root and target can be the same nodes, and there a
     * relationship turned round still points away from a root.
     */
    @JvmOverloads
    fun repair(finding: DirectionFinding, force: Boolean = false, batchSize: Int = 10_000): Long {
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        check(finding.repairable) {
            "${finding.view.simpleName}.${finding.field} cannot be repaired: ${finding.ambiguity}"
        }
        check(finding.ambiguity == null || force) {
            "${finding.view.simpleName}.${finding.field} is ambiguous: ${finding.ambiguity} Pass force = true to turn them round anyway."
        }
        val statement = """
            MATCH (root:${finding.rootLabels.joinToString(":")})-[r:${finding.type}]->(target:${finding.targetLabels.joinToString(":")})
            WITH root, r, target LIMIT ${'$'}batch
            MERGE (target)-[turned:${finding.type}]->(root)
            ON CREATE SET turned += properties(r)
            DELETE r
            RETURN count(*)
        """.trimIndent()
        // No more statements than the count needs, so a relationship is never turned twice.
        var turned = 0L
        var remaining = stored.count(finding.rootLabels, finding.type, finding.targetLabels)
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

    /** True when one node can be both the root and the target of [field]. */
    private fun sameNodes(field: ViewField): Boolean = sameLabels(field.rootLabels, field.targetLabels)

    private fun ambiguity(field: ViewField, all: List<ViewField>): String? {
        if (sameNodes(field)) {
            return "its root and its target can be the same nodes, so nothing tells a relationship written the wrong way from one that is meant."
        }
        // Another field that stores the same relationship from this field's root to its target.
        val other = all.firstOrNull { it !== field && it.relationship.type == field.relationship.type && it.storesFrom(field.rootLabels, field.targetLabels) }
            ?: return null
        return "${other.view.simpleName}.${other.relationship.fieldName} declares ${field.relationship.type} from " +
            "${field.rootLabels.joinToString(":")} to ${field.targetLabels.joinToString(":")}, so relationships pointing that way may be meant."
    }
}

/**
 * The relationships of one `INCOMING` relationship field, as [RelationshipDirectionRepair.report] found them.
 *
 * @property wrongWay how many point from a root to a target: what a save before 0.1.0 wrote
 * @property rightWay how many point from a target to a root, as the field reads them
 * @property ambiguity why the relationships counted in [wrongWay] may be meant; null when the views
 *   give no reason to think so
 * @property repairable whether [RelationshipDirectionRepair.repair] can turn the relationships round.
 *   False when a root and a target can be the same nodes, which no `force` overrides; a finding that
 *   is ambiguous and repairable is one `force = true` repairs
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
) {
    val repairable: Boolean get() = !sameLabels(rootLabels, targetLabels)
}
