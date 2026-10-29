package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.schema.SimilarityFunction
import org.sqlite.Function
import kotlin.math.sqrt

/**
 * `drivine_similarity(embedding, vector, similarity)`: the score of the embedding against the vector,
 * each a JSON array of numbers, by `COSINE` or `EUCLIDEAN`, as the graph engines score it: in `[0, 1]`,
 * higher the nearer. The cosine score is `(1 + cos) / 2`, and the Euclidean `1 / (1 + d²)`, as on
 * Postgres and Oracle. Null where either is not an array of numbers, where they differ in size, or
 * where a cosine is asked of a vector with no length, which has no direction.
 *
 * Every row of a search is measured against the same vector, so the last one read is kept.
 */
internal class SqliteSimilarityFunction : Function() {

    private val json = ObjectMapper()
    private var lastText: String? = null
    private var last: DoubleArray? = null

    override fun xFunc() {
        val embedding = value_text(0)?.let { vector(json.readTree(it)) }
        val text = value_text(1)
        if (text != lastText) {
            last = text?.let { vector(json.readTree(it)) }
            lastText = text
        }
        val vector = last
        val similarity = SimilarityFunction.valueOf(requireNotNull(value_text(2)))
        if (embedding == null || vector == null || embedding.size != vector.size) return result()
        val score = when (similarity) {
            SimilarityFunction.COSINE -> cosine(embedding, vector)?.let { (1 + it) / 2 }
            SimilarityFunction.EUCLIDEAN -> 1 / (1 + squaredDistance(embedding, vector))
        }
        if (score == null) result() else result(score)
    }

    /** [node] as numbers, when it is an array of numbers and nothing else. */
    private fun vector(node: JsonNode): DoubleArray? {
        if (!node.isArray || node.any { !it.isNumber }) return null
        return DoubleArray(node.size()) { node[it].doubleValue() }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double? {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return null
        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }
}
