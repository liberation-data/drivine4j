package org.drivine.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A compact JSON image of a graph object, holding exactly what dirty tracking needs.
 *
 * Dirty checking only asks WHETHER a field changed, and relationship diffing only asks WHICH targets
 * are present. So the digest keeps the object's shape, every fragment's `@NodeId` verbatim, and every
 * map key (a `@PropertyBag`'s stored keys among them), and replaces each large value — a long string,
 * a long array of scalars such as an embedding — with a 64-bit hash of it. Two digests of the same
 * object are equal; a digest of a changed field differs except on a hash collision (SHA-256 truncated
 * to 64 bits: about 1 in 2^64 per changed field, and not constructible on purpose).
 *
 * Current state is digested the same way at save time, so every comparison is digest against digest.
 */
internal class SnapshotDigest(private val objectMapper: ObjectMapper) {

    private val fragmentModels = ConcurrentHashMap<Class<*>, FragmentModel>()
    private val viewModels = ConcurrentHashMap<Class<*>, GraphViewModel>()

    /** Digests a `@GraphView` or a fragment, choosing by the object's runtime class. */
    fun of(obj: Any): JsonNode =
        if (obj.javaClass.isAnnotationPresent(GraphView::class.java)) view(obj) else fragment(obj)

    private fun fragment(obj: Any): JsonNode {
        val json = objectMapper.valueToTree<JsonNode>(obj)
        if (json !is ObjectNode) return compact(json)
        val idField = fragmentModels.computeIfAbsent(obj.javaClass) { FragmentModel.from(it) }.nodeIdField
        return JsonNodeFactory.instance.objectNode().apply {
            json.fields().forEach { (name, value) -> set<JsonNode>(name, if (name == idField) value else compact(value)) }
        }
    }

    private fun view(obj: Any): JsonNode {
        val model = viewModels.computeIfAbsent(obj.javaClass) { GraphViewModel.from(it) }
        return JsonNodeFactory.instance.objectNode().apply {
            fieldValue(obj, model.rootFragment.fieldName)?.let { set<JsonNode>(model.rootFragment.fieldName, fragment(it)) }
            model.relationships.forEach { rel -> set<JsonNode>(rel.fieldName, relationship(fieldValue(obj, rel.fieldName), rel)) }
        }
    }

    private fun relationship(value: Any?, rel: RelationshipModel): JsonNode = when {
        value == null -> JsonNodeFactory.instance.nullNode()
        rel.isCollection -> JsonNodeFactory.instance.arrayNode().apply {
            (value as? Collection<*>)?.forEach { item -> add(item?.let { relationshipItem(it, rel) }) }
        }
        else -> relationshipItem(value, rel)
    }

    /** A target, or a relationship fragment: its properties compacted and its target digested. */
    private fun relationshipItem(item: Any, rel: RelationshipModel): JsonNode {
        if (!rel.isRelationshipFragment) return of(item)
        val targetField = requireNotNull(rel.targetFieldName) { "Relationship fragment ${rel.fieldName} has no target field" }
        val json = compact(objectMapper.valueToTree(item))
        if (json is ObjectNode) fieldValue(item, targetField)?.let { json.set<JsonNode>(targetField, of(it)) }
        return json
    }

    private fun compact(node: JsonNode): JsonNode = when {
        node.isTextual && node.textValue().length > MAX_INLINE_TEXT -> hash(node.textValue().toByteArray())
        node is ArrayNode && node.size() > MAX_INLINE_ARRAY && node.all { it.isValueNode } ->
            hash(objectMapper.writeValueAsBytes(node))
        node is ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(compact(it)) } }
        node is ObjectNode -> JsonNodeFactory.instance.objectNode().apply {
            node.fields().forEach { (name, value) -> set<JsonNode>(name, compact(value)) }
        }
        else -> node
    }

    private fun hash(bytes: ByteArray): JsonNode =
        LongNode.valueOf(ByteBuffer.wrap(sha256.get().digest(bytes)).getLong())

    /** Wherever in the class hierarchy the field is declared: a view may inherit its root or a relationship field. */
    private fun fieldValue(obj: Any, name: String): Any? = org.drivine.query.read(obj, name)

    private companion object {
        /** Strings up to this length are kept verbatim: storing them costs about what a hash does. */
        const val MAX_INLINE_TEXT = 64

        /** Scalar arrays up to this size are kept verbatim; longer ones (embeddings) are hashed. */
        const val MAX_INLINE_ARRAY = 16

        val sha256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
    }
}
