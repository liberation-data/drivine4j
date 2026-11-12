package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.util.RawValue
import org.drivine.cypher.store.SqliteCypherValues as Shared

/**
 * Cypher's values as a compiled statement holds them on SQLite, the JSON text of each, as drivine4j's own functions
 * write them ([SqliteSplitFunction]). The functions that compare, add, group and order them are the shared module's
 * (`org.drivine.cypher.host.HostFunctions`, over its own `SqliteCypherValues`).
 *
 * An infinity is the number SQLite writes for one, `9.0e+999`, which reads as the double it is. NaN, which SQLite's JSON holds
 * as no number, is [NAN], as a statement given NaN writes it: a number equal to nothing and ordered with nothing.
 */
internal object SqliteCypherValues {

    private val json = ObjectMapper()

    /** [node] as JSON text: an infinity as SQLite writes one, and NaN as [NAN]. */
    fun write(node: JsonNode): String = json.writeValueAsString(nonFinite(node))

    /**
     * NaN, as a statement given one writes it, where SQLite's JSON holds no NaN: see [SqliteCypherCompiler][org.drivine.cypher.sql.SqliteCypherCompiler].
     * Jackson's node of [Shared.NAN], which is the compiler's own tree's.
     */
    val NAN: JsonNode = JsonNodeFactory.instance.objectNode().put("\$numberDouble", "NaN")

    /** [node] with each NaN in it, at any depth, [NAN], and each infinity the number SQLite writes for one. */
    fun nonFinite(node: JsonNode): JsonNode = when {
        node.isFloatingPointNumber && node.doubleValue().isNaN() -> NAN
        node.isFloatingPointNumber && node.doubleValue().isInfinite() ->
            JsonNodeFactory.instance.rawValueNode(RawValue(if (node.doubleValue() > 0) INFINITY else "-$INFINITY"))
        node is ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, nonFinite(value)) } }
        node is ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(nonFinite(it)) } }
        else -> node
    }

    /** An infinity as SQLite reads it, as it reads `9.0e+999`, which its JSON writes for one: as [Shared.INFINITY]. */
    const val INFINITY = Shared.INFINITY
}
