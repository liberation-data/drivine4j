package org.drivine.mapper

import org.drivine.query.QuerySpecification

interface ResultMapper {
    fun <T: Any> mapQueryResults(results: List<Any>, spec: QuerySpecification<T>): List<T>
}
