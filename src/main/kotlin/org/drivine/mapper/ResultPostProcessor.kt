package org.drivine.mapper

interface ResultPostProcessor<S, T> {
    fun apply(results: List<S>): List<T>
}
