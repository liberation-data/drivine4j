package org.drivine.studio

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** Loaded objects as the nodes and edges they were read from. */
data class StudioGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    /** The ids of the nodes that are the roots of the loaded objects, in the order they were loaded. */
    val roots: List<String>,
)

data class GraphNode(
    val id: String,
    /** The simple name of the fragment class the node was loaded as. */
    val fragment: String,
    val labels: List<String>,
    val properties: Map<String, Any?>,
    /** A view's `@Count` and `@Aggregate` fields, when the node is that view's root. */
    val aggregates: Map<String, Any?> = emptyMap(),
)

data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    val type: String,
    val directed: Boolean,
    val properties: Map<String, Any?> = emptyMap(),
)

/**
 * Reads the graph back out of loaded views and fragments. A view is a tree; the same node met twice,
 * under two roots or down two relationships, is one node here, told apart by its `@NodeId`.
 */
class StudioGraphBuilder {
    private val nodes = linkedMapOf<String, GraphNode>()
    private val edges = linkedMapOf<String, GraphEdge>()
    private val roots = linkedSetOf<String>()
    private val anonymous = IdentityHashMap<Any, String>()

    fun add(loaded: Any): StudioGraphBuilder {
        emit(loaded)?.let { roots += it }
        return this
    }

    fun build() = StudioGraph(nodes.values.toList(), edges.values.toList(), roots.toList())

    /** Adds [value] and whatever it reaches, and answers the id of the node it stands for. */
    private fun emit(value: Any?): String? {
        if (value == null) return null
        val viewClass = annotated(value.javaClass, GraphView::class.java)
        if (viewClass == null) return emitFragment(value)

        val view = views.computeIfAbsent(viewClass) { GraphViewModel.from(it) }
        val rootId = emitFragment(read(value, view.rootFragment.fieldName)) ?: return null
        if (view.aggregateFields.isNotEmpty()) {
            val aggregates = view.aggregateFields.associate { it.fieldName to read(value, it.fieldName) }
            nodes[rootId] = nodes.getValue(rootId).let { it.copy(aggregates = it.aggregates + aggregates) }
        }
        view.relationships.forEach { rel ->
            val elements = when (val held = read(value, rel.fieldName)) {
                null -> emptyList()
                is Collection<*> -> held.filterNotNull()
                else -> listOf(held)
            }
            elements.forEach { element ->
                val target = if (rel.isRelationshipFragment) read(element, rel.targetFieldName!!) else element
                val targetId = emit(target) ?: return@forEach
                val properties = if (rel.isRelationshipFragment) {
                    rel.relationshipProperties.associateWith { read(element, it) }
                } else emptyMap()
                val type = if (rel.isPath) rel.hops.joinToString("/") { it.type } else rel.type
                val (from, to) = if (rel.direction == Direction.INCOMING) targetId to rootId else rootId to targetId
                edges.getOrPut("$from-[$type]-$to") {
                    GraphEdge("$from-[$type]-$to", from, to, type, rel.direction != Direction.UNDIRECTED, properties)
                }
            }
        }
        return rootId
    }

    private fun emitFragment(value: Any?): String? {
        if (value == null) return null
        val fragmentClass = annotated(value.javaClass, NodeFragment::class.java) ?: return null
        val model = fragments.computeIfAbsent(fragmentClass) { FragmentModel.from(it) }
        val id = model.nodeIdField?.let { read(value, it) }?.toString()
            ?: anonymous.getOrPut(value) { "anonymous-${anonymous.size + 1}" }
        val properties = model.fields
            // An embedding is hundreds of numbers nobody reads in a graph.
            .filterNot { it.vectorIndexed }
            .associate { it.name to read(value, it.name) }
        val seen = nodes[id]
        // Two views may read the same node through different fragments; keep every property either saw.
        nodes[id] = if (seen == null) {
            GraphNode(id, fragmentClass.simpleName, model.labels, properties)
        } else {
            seen.copy(labels = (seen.labels + model.labels).distinct(), properties = seen.properties + properties)
        }
        return id
    }

    private companion object {
        val views = ConcurrentHashMap<Class<*>, GraphViewModel>()
        val fragments = ConcurrentHashMap<Class<*>, FragmentModel>()

        /** [clazz] or the nearest supertype carrying [annotation]: a loaded subtype need not carry it itself. */
        fun annotated(clazz: Class<*>, annotation: Class<out Annotation>): Class<*>? =
            generateSequence(clazz) { it.superclass }.firstOrNull { it.isAnnotationPresent(annotation) }

        fun read(target: Any?, name: String): Any? {
            if (target == null) return null
            val field = generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == name } }
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            // A property with no backing field, such as one delegated to another object.
            val getter = "get" + name.replaceFirstChar { it.uppercase() }
            return target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.invoke(target)
        }
    }
}
