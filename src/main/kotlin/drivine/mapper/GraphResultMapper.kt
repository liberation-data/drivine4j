package drivine.mapper

import drivine.query.QuerySpecification

abstract class GraphResultMapper : ResultMapper {

    override fun <T : Any> mapQueryResults(records: List<Any>, spec: QuerySpecification<T>): List<T> {
        // TODO: The default mapper(s) can be a result post processor too - then users can specify their own, if desired.
        var results: List<Any> = mapToNative(records)
        spec.postProcessors.forEach { processor ->
            results = processor.apply(results)
        }
        @Suppress("UNCHECKED_CAST")
        return results as List<T>
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
