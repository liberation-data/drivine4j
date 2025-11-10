package org.drivine.mapper

class MapPostProcessor<S, T>(private val mapFunction: (S) -> T) : ResultPostProcessor<S, T> {

    override fun apply(results: List<S>): List<T> {
        return results.map { mapFunction(it) }
    }
}
