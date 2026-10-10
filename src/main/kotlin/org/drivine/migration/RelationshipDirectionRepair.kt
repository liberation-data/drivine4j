package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.manager.DelegatingPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.TransactionalPersistenceManager
import org.drivine.model.Stamps
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
 * round, keeping their properties. Where two nodes have another relationship of the type between
 * them, pointing either way, they all become one, which [DirectionFinding.collisions] counts beforehand.
 *
 * A relationship between two nodes that each have the labels of both the root and the target is
 * counted apart and never turned: either node can be the root, so it may already be right.
 *
 * A relationship that points away from the root is not always a mistake: other code may have meant
 * it. [DirectionFinding.ambiguity] says when the views themselves give a reason to think so. Read the
 * report before repairing, and run this once, as a migration: it is not something to do at startup.
 *
 * Back the store up first, and run the repair when nothing built on a version before 0.1.0 is still
 * writing: what such a writer saves after the repair points the wrong way again. Where a view has a
 * `@GraphPath` field as well, run [PathRelationshipReport] first and deal with what it finds: a
 * relationship an old save wrote for a path field also points away from the root, and turned round
 * it would be loaded by an `INCOMING` field of the same type. A finding is ambiguous where the views
 * given show that this can be so.
 *
 * [report] only reads, so any [PersistenceManager] serves. [repair] commits each batch by itself, so
 * that a run that stops half way can be run again, and is refused inside a transaction: call it
 * outside one, with the default manager of `PersistenceManagerFactory.get(database)` or the one of
 * `PersistenceManagerType.NON_TRANSACTIONAL`. A manager of `PersistenceManagerType.TRANSACTIONAL`
 * cannot run it.
 */
class RelationshipDirectionRepair(private val persistenceManager: PersistenceManager) {

    private val stored = StoredRelationships(persistenceManager)

    /**
     * One finding for each relationship field declared `INCOMING`, in [views] and in the views nested
     * in them. A field declared `@ReadOnly` has one too: `@ReadOnly` came with 0.1.0, so a save before
     * it wrote that field as it wrote every other. A list read over several hops (`maxDepth` above 1)
     * has none: what a save wrote for it is not a relationship of the field turned the wrong way.
     *
     * Give every view of the model: a finding is marked ambiguous when another of the views declares
     * the same relationship pointing away from the root, between nodes that can be the same ones, has
     * a `@Count` or `@Aggregate` field that reads relationships of that type pointing away from such a
     * root, or has a `@GraphPath` field with a hop that is stored so. A `@ReadOnly` field counts:
     * other code writes what it loads. It is ambiguous as well when one of the views has a
     * `@GraphPath` field, or a list read over several hops, that a save before 0.1.0 wrote as that
     * relationship pointing away from the root: what it wrote is not this field's to load.
     *
     * A view nested in one of [views] is looked at with it, and so is a view that is a subtype a
     * sealed class or a `@JsonSubTypes` names. [unexamined] says what could not be. What only Cypher
     * or `edges` writes, no view shows: the counts are by label and type.
     */
    fun report(vararg views: Class<*>): List<DirectionFinding> {
        val fields = stored.declaredFields(views)
        val paths = stored.pathFields(views)
        val aggregates = stored.aggregateFields(views)
        val labelSets = stored.labelSets(views)
        return fields.filter { it.relationship.direction == Direction.INCOMING && !it.relationship.readsSeveralHops }.map { field ->
            DirectionFinding(
                view = field.view,
                field = field.relationship.fieldName,
                type = field.relationship.type,
                rootLabels = field.rootLabels,
                targetLabels = field.targetLabels,
                wrongWay = stored.wrongWay(field.rootLabels, field.relationship.type, field.targetLabels),
                rightWay = stored.rightWay(field.rootLabels, field.relationship.type, field.targetLabels),
                eitherWay = stored.eitherWay(field.rootLabels, field.relationship.type, field.targetLabels),
                ambiguity = ambiguity(field, fields, paths, aggregates, labelSets),
                collisions = stored.collisions(field.rootLabels, field.relationship.type, field.targetLabels),
            )
        }
    }

    /**
     * Turns round the relationships [finding] counted as pointing the wrong way, [batchSize] to a
     * statement. A relationship's properties go with it. Where one already points the right way
     * between the same two nodes, the two become one: the one that already pointed the right way
     * keeps every property it has, and takes from the one turned round the properties it lacks.
     * Nothing in the store says which of the two is the newer, so where both have a property with
     * different values, the value on the one turned round is lost. Where several already point the
     * right way between the two nodes, each of them takes those properties.
     *
     * Several relationships that point the wrong way between the same two nodes become one as well,
     * with the properties of them all. Where two have a property with different values, the value
     * kept is that of the one whose id in the store is the lowest, whatever the batch size and however
     * often the repair is run. [DirectionFinding.collisions] counts every relationship that is made
     * one with another, and the report gives it before anything is changed.
     *
     * The node at each end of a relationship it deals with gets a new relationship token in its
     * stamp, as any change to a node's relationships gives: an object loaded before the repair does
     * not hold the relationship, and a save of it that replaces the field's list is refused.
     *
     * Returns how many relationships that pointed the wrong way it dealt with: each one it turned
     * round and each one it dropped for another between the same two nodes. A relationship that
     * already pointed the right way is not counted, however many there are.
     *
     * A relationship counted in [DirectionFinding.eitherWay] is left as it is, forced or not, so a
     * relationship turned round is never matched again and a second run changes nothing.
     *
     * Refused for an ambiguous finding unless [force] is set, and always refused for a finding that
     * is not [DirectionFinding.repairable]: its root and target can be the same nodes, and there a
     * relationship turned round still points away from a root. Refused inside a transaction too: each
     * batch is one statement, committed by itself.
     */
    @JvmOverloads
    fun repair(finding: DirectionFinding, force: Boolean = false, batchSize: Int = 10_000): Long {
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        val name = "${finding.view.simpleName}.${finding.field}"
        check(!inTransaction()) {
            "$name cannot be repaired in a transaction: each batch is to be committed by itself, so that a run that stops " +
                "can be run again. Call repair outside a transaction, with a manager that needs none: " +
                "PersistenceManagerFactory.get(database, PersistenceManagerType.NON_TRANSACTIONAL), or the default one."
        }
        // The labels decide this, not what the finding says of itself: a finding can be built by hand.
        check(finding.repairable) { "$name cannot be repaired: $SAME_NODES" }
        check(finding.ambiguity == null || force) {
            "$name is ambiguous: ${finding.ambiguity} Pass force = true to turn them round anyway."
        }
        // The relationships are taken in the order of the store's own ids, so which of two keeps its
        // value does not depend on the batch each falls in. Those between one pair are turned as one
        // row: the lowest id is set last and wins among them, and what a relationship already there
        // held is set after them all, so it keeps its own values. The MERGE gives a row for each
        // relationship that already points the right way, so a pair is counted once.
        val statement = """
            MATCH ${nodePattern(finding.rootLabels, "root")}-[r:${finding.type}]->${nodePattern(finding.targetLabels, "target")}
            WHERE NOT (${eitherWay(finding.rootLabels, finding.targetLabels)})
            WITH root, target, r ORDER BY id(r) LIMIT ${'$'}batch
            WITH root, target, collect(r) AS rs
            MERGE (target)-[turned:${finding.type}]->(root)
            WITH root, target, rs, turned, properties(turned) AS kept
            FOREACH (i IN range(size(rs) - 1, 0, -1) | SET turned += properties(rs[i]))
            SET turned += kept, ${Stamps.linksClause("root")}, ${Stamps.linksClause("target")}
            WITH DISTINCT root, target, rs, size(rs) AS dealt
            FOREACH (x IN rs | DELETE x)
            RETURN coalesce(sum(dealt), 0)
        """.trimIndent()
        // No more statements than the count needs.
        var turned = 0L
        var remaining = stored.wrongWay(finding.rootLabels, finding.type, finding.targetLabels)
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

    /**
     * What of [views] [report] could not look at: each field whose target is a view that is abstract
     * or an interface, where the class names no subtype and none is among [views]. The view of such a
     * subtype may declare the relationship pointing away from a root, which would make a finding
     * ambiguous. Empty when everything was looked at.
     */
    fun unexamined(vararg views: Class<*>): List<String> = stored.unexamined(views)

    /** Whether a statement run now would be part of a transaction a caller holds open. */
    private fun inTransaction(): Boolean = when (persistenceManager) {
        is TransactionalPersistenceManager -> true
        is DelegatingPersistenceManager -> persistenceManager.contextHolder.currentTransaction != null
        else -> false
    }

    /** True when one node can be both the root and the target of [field]. */
    private fun sameNodes(field: ViewField): Boolean = sameLabels(field.rootLabels, field.targetLabels)

    private fun ambiguity(
        field: ViewField, all: List<ViewField>, paths: List<ViewField>, aggregates: List<AggregateField>, labelSets: Set<List<String>>,
    ): String? {
        if (sameNodes(field)) return SAME_NODES
        val type = field.relationship.type
        val between = "$type from ${named(field.rootLabels)} to ${named(field.targetLabels)}"
        // Another field that stores the same relationship from this field's root to its target.
        all.firstOrNull { it !== field && it.relationship.type == type && it.storesFrom(field.rootLabels, field.targetLabels, labelSets) }?.let {
            return "${it.view.simpleName}.${it.relationship.fieldName} declares $between, so relationships pointing that way may be meant."
        }
        // A count or an aggregate names no target: it reads every relationship of the type on that side of its root.
        aggregates.firstOrNull { it.aggregate.type == type && it.storesFrom(field.rootLabels, field.targetLabels, labelSets) }?.let {
            return "${it.view.simpleName}.${it.aggregate.fieldName} reads $between, so relationships pointing that way may be meant."
        }
        // A hop of a path that is stored so: a relationship pointing that way may be part of the path.
        paths.firstNotNullOfOrNull { path ->
            path.pathHops().firstOrNull { it.type == type && it.storesFrom(field.rootLabels, field.targetLabels, labelSets) }?.let {
                "The path ${path.view.simpleName}.${path.relationship.fieldName} has a $type hop that can be $between, " +
                    "so relationships pointing that way may be hops of it."
            }
        }?.let { return it }
        // What the old save wrote for a field that reads several hops points that way too, and is not this field's.
        return (paths + all).firstOrNull { it !== field && it.wasWrittenDirect(type, field.rootLabels, field.targetLabels, labelSets) }?.let {
            val what = if (it.relationship.isPath) "the path" else "the list read over several hops"
            "A save before 0.1.0 wrote $what ${it.view.simpleName}.${it.relationship.fieldName} as $between, so relationships pointing " +
                "that way may be what it wrote, and turned round this field would load them. See PathRelationshipReport."
        }
    }

    private companion object {
        const val SAME_NODES =
            "its root and its target can be the same nodes, so nothing tells a relationship written the wrong way from one that is meant."
    }
}

/**
 * The relationships of one `INCOMING` relationship field, as [RelationshipDirectionRepair.report] found them.
 *
 * @property view the view that declares the field
 * @property field the name of the field
 * @property type the relationship type the field declares
 * @property rootLabels the labels of the view's root; none when its fragment has no label
 * @property targetLabels the labels of the kind of node the field holds
 * @property wrongWay how many point from a root to a target, which is what a save before 0.1.0
 *   wrote, those counted in [eitherWay] left out
 * @property rightWay how many point from a target to a root, as the field reads them, those counted
 *   in [eitherWay] left out
 * @property eitherWay how many run between two nodes that each have every label of the root and of
 *   the target. Either node can be the root, so nothing in the store says which way such a
 *   relationship should point, and [RelationshipDirectionRepair.repair] leaves it as it is
 * @property ambiguity why the relationships counted in [wrongWay] may be meant; null when the views
 *   give no reason to think so
 * @property collisions how many of those counted in [wrongWay] run between two nodes that have
 *   another relationship of the type between them: one that already points the right way, or
 *   another that points the wrong way. [RelationshipDirectionRepair.repair] makes one relationship
 *   of them all, and where two have a property with different values, one value is lost: it keeps
 *   that of one that already pointed the right way, and among those turned round that of the one
 *   whose id in the store is the lowest
 * @property repairable whether [RelationshipDirectionRepair.repair] can turn the relationships round.
 *   False when a root and a target can be the same nodes, as they can when either has no label,
 *   which no `force` overrides; a finding that is ambiguous and repairable is one `force = true` repairs
 */
data class DirectionFinding(
    val view: Class<*>,
    val field: String,
    val type: String,
    val rootLabels: List<String>,
    val targetLabels: List<String>,
    val wrongWay: Long,
    val rightWay: Long,
    val eitherWay: Long,
    val ambiguity: String?,
    val collisions: Long = 0,
) {
    val repairable: Boolean get() = !sameLabels(rootLabels, targetLabels)
}
