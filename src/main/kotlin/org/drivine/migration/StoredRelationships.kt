package org.drivine.migration

import com.fasterxml.jackson.annotation.JsonSubTypes
import java.lang.reflect.Modifier
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.PersistenceManager
import org.drivine.model.AggregateFieldModel
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification

/**
 * Whether the nodes labelled [one] and the nodes labelled [other] can be the same nodes: one list
 * holds every label of the other, in any order. A pattern matches a node that has at least the
 * labels it names, so a subtype's nodes are its supertype's too, and a list of no labels names
 * every node.
 */
internal fun sameLabels(one: List<String>, other: List<String>): Boolean = one.containsAll(other) || other.containsAll(one)

/**
 * Whether a node can have every label of [one] and every label of [other]: they can be the same
 * nodes by [sameLabels], or one of [labelSets], the kinds of node the views name, has them all.
 */
internal fun canBeSame(one: List<String>, other: List<String>, labelSets: Set<List<String>>): Boolean =
    sameLabels(one, other) || labelSets.any { it.containsAll(one) && it.containsAll(other) }

/** The Cypher pattern of a node that has [labels], named [variable]: any node when there are none. */
internal fun nodePattern(labels: List<String>, variable: String = ""): String = "($variable${labels.joinToString("") { ":$it" }})"

/** [labels] as a message names them. */
internal fun named(labels: List<String>): String = labels.joinToString(":").ifEmpty { "any node" }

/**
 * Cypher that is true of a relationship matched between `root` and `target` when each of the two
 * nodes has every label of both ends. Either node can then be the root, so nothing in the store
 * says which way the relationship should point.
 */
internal fun eitherWay(rootLabels: List<String>, targetLabels: List<String>): String =
    (targetLabels.map { "root:$it" } + rootLabels.map { "target:$it" }).joinToString(" AND ").ifEmpty { "true" }

/**
 * Whether relationships read in [direction] from nodes labelled [start] to nodes labelled [end] are
 * stored from nodes labelled [from] to nodes labelled [to], or from nodes that can be the same as those.
 */
private fun storedFrom(
    direction: Direction,
    start: List<String>,
    end: List<String>,
    from: List<String>,
    to: List<String>,
    labelSets: Set<List<String>>,
): Boolean {
    val away = canBeSame(start, from, labelSets) && canBeSame(end, to, labelSets)
    val toward = canBeSame(end, from, labelSets) && canBeSame(start, to, labelSets)
    return when (direction) {
        Direction.OUTGOING -> away
        Direction.INCOMING -> toward
        Direction.UNDIRECTED -> away || toward
    }
}

/**
 * One hop of a `@GraphPath` field, with the labels the path gives the nodes at its two ends: the
 * root's or the target's at the ends of the path, a hop's own label between, and none where a hop
 * names none.
 */
internal class PathHop(val type: String, val direction: Direction, val startLabels: List<String>, val endLabels: List<String>) {

    /**
     * Whether this hop is stored from nodes labelled [from] to nodes labelled [to], or from nodes
     * that can be the same as those.
     */
    fun storesFrom(from: List<String>, to: List<String>, labelSets: Set<List<String>>): Boolean =
        storedFrom(direction, startLabels, endLabels, from, to, labelSets)
}

/**
 * A `@Count` or `@Aggregate` field of a view, with the labels of the view's root. It reads the
 * relationships of its type on the root's side it declares, to a node of any label.
 */
internal class AggregateField(val view: Class<*>, val aggregate: AggregateFieldModel, val rootLabels: List<String>) {

    /**
     * Whether this field reads relationships stored from nodes labelled [from] to nodes labelled [to],
     * or from nodes that can be the same as those.
     */
    fun storesFrom(from: List<String>, to: List<String>, labelSets: Set<List<String>>): Boolean =
        storedFrom(aggregate.direction, rootLabels, emptyList(), from, to, labelSets)
}

/** A relationship or path field of a view, with the labels of the nodes at its two ends. */
internal class ViewField(val view: Class<*>, val relationship: RelationshipModel, val rootLabels: List<String>, val targetLabels: List<String>) {

    /**
     * Whether a save before 0.1.0 wrote this field as a relationship of [type] from nodes labelled
     * [from] to nodes labelled [to], or from nodes that can be the same as those. That save wrote a
     * `@GraphPath` field as one relationship of its first hop's type, and each node of a list read
     * over several hops as one relationship of the field's type, from the root straight to the node
     * in both cases, whatever direction was declared.
     */
    fun wasWrittenDirect(type: String, from: List<String>, to: List<String>, labelSets: Set<List<String>>): Boolean {
        val written = if (relationship.isPath) relationship.hops.first().type else relationship.type
        return (relationship.isPath || relationship.readsSeveralHops) && written == type &&
            canBeSame(rootLabels, from, labelSets) && canBeSame(targetLabels, to, labelSets)
    }

    /**
     * Whether this field reads relationships stored from nodes labelled [from] to nodes labelled [to],
     * or from nodes that can be the same as those.
     */
    fun storesFrom(from: List<String>, to: List<String>, labelSets: Set<List<String>>): Boolean =
        storedFrom(relationship.direction, rootLabels, targetLabels, from, to, labelSets)

    /** The hops of a path field, in the order the path takes them. The last ends at the field's target, whatever label it names. */
    fun pathHops(): List<PathHop> {
        val hops = relationship.hops
        return hops.mapIndexed { i, hop ->
            PathHop(
                type = hop.type,
                direction = hop.direction,
                startLabels = if (i == 0) rootLabels else listOfNotNull(hops[i - 1].intermediateLabel),
                endLabels = if (i == hops.lastIndex) targetLabels else listOfNotNull(hop.intermediateLabel),
            )
        }
    }
}

/** What a set of views declares about relationships, and what the store holds of them. */
internal class StoredRelationships(private val persistenceManager: PersistenceManager) {

    /**
     * The relationship fields declared in [views] and in the views nested in them, those a save skips
     * among them. A `@GraphPath` field is not one: it names no single relationship.
     */
    fun declaredFields(views: Array<out Class<*>>): List<ViewField> = declared(views).fields.filterNot { it.relationship.isPath }

    /** The `@GraphPath` fields in [views] and in the views nested in them. */
    fun pathFields(views: Array<out Class<*>>): List<ViewField> = declared(views).fields.filter { it.relationship.isPath }

    /** The `@Count` and `@Aggregate` fields in [views] and in the views nested in them. */
    fun aggregateFields(views: Array<out Class<*>>): List<AggregateField> = declared(views).aggregates

    /** The labels of each kind of node [views] name: their roots and the targets of their fields, nested views included. */
    fun labelSets(views: Array<out Class<*>>): Set<List<String>> = declared(views).labelSets

    /**
     * What of [views] could not be looked at: a field whose target is a view that is abstract or an
     * interface, with no subtype the class names and none among [views]. A view of such a subtype
     * may declare relationships of its own, and nothing here has seen them.
     */
    fun unexamined(views: Array<out Class<*>>): List<String> = declared(views).unexamined.toList()

    /** How many relationships of [type] run from a node labelled [from] to a node labelled [to]. */
    fun count(from: List<String>, type: String, to: List<String>): Long =
        count("MATCH ${nodePattern(from)}-[r:$type]->${nodePattern(to)} RETURN count(r)")

    /**
     * How many relationships of [type] run from a root to a target and can only be the wrong way
     * round for a field that reads them from a target to a root: those that can point [eitherWay]
     * are left out.
     */
    fun wrongWay(rootLabels: List<String>, type: String, targetLabels: List<String>): Long = count(
        "MATCH ${nodePattern(rootLabels, "root")}-[r:$type]->${nodePattern(targetLabels, "target")} " +
            "WHERE NOT (${eitherWay(rootLabels, targetLabels)}) RETURN count(r)"
    )

    /**
     * How many of the relationships [wrongWay] counts run between two nodes that have another of the
     * type between them, pointing either way: a repair makes one relationship of them all.
     */
    fun collisions(rootLabels: List<String>, type: String, targetLabels: List<String>): Long = count(
        "MATCH ${nodePattern(rootLabels, "root")}-[r:$type]->${nodePattern(targetLabels, "target")} " +
            "WHERE NOT (${eitherWay(rootLabels, targetLabels)}) " +
            "WITH root, target, count(r) AS wrong " +
            "OPTIONAL MATCH (target)-[k:$type]->(root) " +
            "WITH root, target, wrong, count(k) AS right " +
            "RETURN coalesce(sum(CASE WHEN wrong + right > 1 THEN wrong ELSE 0 END), 0)"
    )

    /** How many relationships of [type] run from a target to a root, those that can point [eitherWay] left out. */
    fun rightWay(rootLabels: List<String>, type: String, targetLabels: List<String>): Long = count(
        "MATCH ${nodePattern(targetLabels, "target")}-[r:$type]->${nodePattern(rootLabels, "root")} " +
            "WHERE NOT (${eitherWay(rootLabels, targetLabels)}) RETURN count(r)"
    )

    /** How many relationships of [type] run between two nodes that each have every label of both ends. */
    fun eitherWay(rootLabels: List<String>, type: String, targetLabels: List<String>): Long = count(
        "MATCH ${nodePattern(rootLabels, "root")}-[r:$type]->${nodePattern(targetLabels, "target")} " +
            "WHERE ${eitherWay(rootLabels, targetLabels)} RETURN count(r)"
    )

    private fun count(statement: String): Long =
        persistenceManager.getOne(QuerySpecification.withStatement(statement).transform(Long::class.java))

    private class Declared {
        val fields = mutableListOf<ViewField>()
        val aggregates = mutableListOf<AggregateField>()
        val labelSets = linkedSetOf<List<String>>()
        val unexamined = linkedSetOf<String>()
    }

    private fun declared(views: Array<out Class<*>>): Declared {
        val seen = mutableSetOf<Class<*>>()
        return Declared().also { into -> views.forEach { collect(it, seen, into, views) } }
    }

    private fun collect(view: Class<*>, seen: MutableSet<Class<*>>, into: Declared, given: Array<out Class<*>>) {
        if (!seen.add(view)) return
        require(view.isAnnotationPresent(GraphView::class.java)) { "${view.simpleName} is not a @GraphView." }
        val model = GraphViewModel.from(view)
        val rootLabels = labelsOf(model.rootFragment.fragmentType, into)
        model.aggregateFields.forEach { into.aggregates += AggregateField(view, it, rootLabels) }
        model.relationships.forEach { relationship ->
            val target = if (relationship.isRelationshipFragment) {
                requireNotNull(relationship.targetNodeType) { "Relationship fragment '${relationship.fieldName}' has no target" }
            } else {
                relationship.elementType
            }
            val nested = target.isAnnotationPresent(GraphView::class.java)
            val targetFragment = if (nested) GraphViewModel.from(target).rootFragment.fragmentType else target
            val targetLabels = labelsOf(targetFragment, into)
            into.fields += ViewField(view, relationship, rootLabels, targetLabels)
            if (nested) {
                collect(target, seen, into, given)
                // A view of a subtype declares relationships of its own.
                val subtypes = subtypesOf(target).filter { it.isAnnotationPresent(GraphView::class.java) }
                subtypes.forEach { collect(it, seen, into, given) }
                if (isAbstract(target) && subtypes.isEmpty() && given.none { it != target && target.isAssignableFrom(it) }) {
                    into.unexamined += "${view.simpleName}.${relationship.fieldName} holds ${target.simpleName}, which is abstract " +
                        "and names no subtype: the views of its subtypes were not examined. Give them to the report."
                }
            }
        }
        // A view given by its supertype is its subtypes' views too.
        subtypesOf(view).filter { it.isAnnotationPresent(GraphView::class.java) }.forEach { collect(it, seen, into, given) }
    }

    /** The labels of [fragment], recorded with those of each subtype it names: a node of the subtype is one of [fragment]'s. */
    private fun labelsOf(fragment: Class<*>, into: Declared): List<String> {
        val labels = FragmentModel.from(fragment).labels
        into.labelSets += labels
        subtypesOf(fragment).forEach { subtype -> runCatching { FragmentModel.from(subtype).labels }.onSuccess { into.labelSets += it } }
        return labels
    }

    /**
     * The subtypes [type] names, and theirs: the subclasses of a sealed class and the classes of a
     * `@JsonSubTypes`. A subtype registered only at run time is not among them.
     */
    private fun subtypesOf(type: Class<*>, found: MutableSet<Class<*>> = linkedSetOf()): Set<Class<*>> {
        val named = type.getAnnotation(JsonSubTypes::class.java)?.value?.map { it.value.java }.orEmpty() +
            runCatching { type.kotlin.sealedSubclasses.map { it.java } }.getOrDefault(emptyList())
        named.forEach { if (it != type && found.add(it)) subtypesOf(it, found) }
        return found
    }

    private fun isAbstract(type: Class<*>): Boolean = type.isInterface || Modifier.isAbstract(type.modifiers)
}
