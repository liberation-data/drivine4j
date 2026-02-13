package org.drivine.mapper

import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

abstract class GraphResultMapper(
    protected val subtypeRegistry: SubtypeRegistry? = null
) : ResultMapper {

    private val logger = LoggerFactory.getLogger(GraphResultMapper::class.java)
    private var multiColumnWarningLogged = false

    override fun <T : Any> mapQueryResults(results: List<Any>, spec: QuerySpecification<T>): List<T> {
        // Get the chain of specs from original to final
        val specChain = getSpecChain(spec)

        // Start with mapToNative only on the first (original) spec
        @Suppress("UNCHECKED_CAST")
        var results: List<Any> = mapToNative(results) as List<Any>

        // Process each spec in the chain
        specChain.forEach { chainSpec ->
            chainSpec.postProcessors.forEach { processor ->
                // Inject subtypeRegistry into TransformPostProcessor
                val processedProcessor = if (processor is TransformPostProcessor<*, *> && subtypeRegistry != null) {
                    @Suppress("UNCHECKED_CAST")
                    processor.withSubtypeRegistry(subtypeRegistry) as ResultPostProcessor<Any, Any>
                } else {
                    processor
                }
                results = processedProcessor.apply(results)
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

    private fun mapToNative(records: List<Any>): List<Any?> {
        return records.map { record ->
            val keys = keys(record)
            if (keys.size == 1) {
                toNative(itemAtIndex(record, 0))
            } else {
                if (!multiColumnWarningLogged) {
                    multiColumnWarningLogged = true
                    logger.warn(
                        "Query returned {} columns ({}). When using PersistenceManager, prefer returning a " +
                        "single map or scalar value. For example: RETURN {{ key1: val1, key2: val2 }} AS result",
                        keys.size, keys.joinToString()
                    )
                }
                keys.indices.map { index -> toNative(itemAtIndex(record, index)) }
            }
        }
    }

    abstract fun keys(record: Any): List<String>

    abstract fun itemAtIndex(record: Any, index: Int): Any

    abstract fun toNative(value: Any): Any?
}
