package org.drivine.mapper

class FilterPostProcessor<T>(private val filterFunction: (T) -> Boolean) : ResultPostProcessor<T, T> {

    override fun apply(results: List<T>): List<T> {
        return results.filter { it ->
            filterFunction(it)
        }
    }
}
