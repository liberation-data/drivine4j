package org.drivine.studio

import org.drivine.manager.GraphObjectOperations
import org.drivine.query.dsl.OrderBuilder
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.WhereBuilder
import org.drivine.query.dsl.WhereCondition

/** What a [StudioQuery] loaded. */
data class StudioResult(
    val graph: StudioGraph,
    /** How many objects `loadAll` returned. */
    val count: Int,
    val limit: Int,
    /** True when [count] reached [limit], so there may be more than was loaded. */
    val truncated: Boolean,
)

/**
 * Runs a [StudioQuery]. The studio reads and never writes: the only thing it asks of
 * [GraphObjectOperations] is `loadAll`.
 */
class StudioService(
    val catalog: StudioCatalog,
    private val translator: StudioQueryTranslator,
    private val operations: GraphObjectOperations,
) {
    fun run(query: StudioQuery): StudioResult {
        val translated = translator.translate(query)
        @Suppress("UNCHECKED_CAST")
        val loaded = operations.loadAll(translated.type as Class<Any>, NoQueryObject) {
            if (translated.where.isNotEmpty()) where { conditions(translated.where) }
            if (translated.orderBy.isNotEmpty()) orderBy { orders(translated.orderBy) }
            translated.skip?.let { skip(it) }
            limit(translated.limit)
        }
        val graph = StudioGraphBuilder().apply { loaded.forEach { add(it) } }.build()
        return StudioResult(graph, loaded.size, translated.limit, loaded.size >= translated.limit)
    }

    /** The DSL's query object supplies property references; the studio has its conditions already. */
    private object NoQueryObject
}

context(builder: WhereBuilder<T>)
private fun <T : Any> conditions(conditions: List<WhereCondition>) {
    builder.conditions.addAll(conditions)
}

context(builder: OrderBuilder<T>)
private fun <T : Any> orders(orders: List<OrderSpec>) {
    orders.forEach { builder(it) }
}
