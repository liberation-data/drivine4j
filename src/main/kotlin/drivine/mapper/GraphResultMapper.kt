package drivine.mapper

import drivine.query.QuerySpecification

abstract class GraphResultMapper : ResultMapper {

    override fun <T : Any> mapQueryResults(results: List<Any>, spec: QuerySpecification<T>): List<T> {
        // Get the chain of specs from original to final
        val specChain = getSpecChain(spec)

        // Start with mapToNative only on the first (original) spec
        var results: List<Any> = mapToNative(results)

        // Process each spec in the chain
        specChain.forEach { chainSpec ->
            chainSpec.postProcessors.forEach { processor ->
                results = processor.apply(results)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return results as List<T>
    }

    private fun getSpecChain(spec: QuerySpecification<*>): List<QuerySpecification<*>> {
        val chain = mutableListOf<QuerySpecification<*>>()
        var current: QuerySpecification<*>? = spec

        // Build chain from current back to original
        while (current != null) {
            chain.add(0, current)  // Add to beginning to maintain order
            current = current.originalSpec
        }

        return chain
    }

    private fun mapToNative(records: List<Any>): List<Any> {
        return records.map { record ->
            val keys = keys(record)
            if (keys.size == 1) {
                toNative(itemAtIndex(record, 0))
            } else {
                keys.indices.map { index -> toNative(itemAtIndex(record, index)) }
            }
        }
    }

    abstract fun keys(record: Any): List<String>

    abstract fun itemAtIndex(record: Any, index: Int): Any

    abstract fun toNative(value: Any): Any
}
