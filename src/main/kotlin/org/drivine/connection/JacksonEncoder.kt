package org.drivine.connection

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.databind.node.POJONode
import org.drivine.cypher.json.CypherEncoder
import org.drivine.mapper.Neo4jObjectMapper
import java.util.Base64
import org.drivine.cypher.json.ArrayNode as CompilerArray
import org.drivine.cypher.json.BigIntegerNode as CompilerBigInteger
import org.drivine.cypher.json.BooleanNode as CompilerBoolean
import org.drivine.cypher.json.DecimalNode as CompilerDecimal
import org.drivine.cypher.json.DoubleNode as CompilerDouble
import org.drivine.cypher.json.FloatNode as CompilerFloat
import org.drivine.cypher.json.JsonNode as CompilerNode
import org.drivine.cypher.json.LongNode as CompilerLong
import org.drivine.cypher.json.NullNode as CompilerNull
import org.drivine.cypher.json.ObjectNode as CompilerObject
import org.drivine.cypher.json.TextNode as CompilerText

/**
 * The values a statement is given, written as JSON by drivine4j's Jackson mapper ([Neo4jObjectMapper]) exactly as the
 * compiler wrote them when it was drivine4j's own: as a tree where it made one ([tree], the mapper's `valueToTree`), and as
 * text where it wrote one ([text], the mapper's `writeValueAsString`). The two differ: a tree's decimal is written with
 * its trailing zeros dropped, and text holds a `Duration` to the nanosecond. An object of the application's, an enum, a
 * `LocalDateTime` and a `Date` are each written as the mapper writes them, which the compiler's own writing of the JVM's
 * types does not always match.
 *
 * A value that holds a tree of the compiler's own, as a structure the compiler writes of its own may, is left to the
 * compiler, which asks again of each value in it. The compiler still reads each value's own type: this is asked only how
 * one is written.
 */
internal object JacksonEncoder : CypherEncoder {

    override fun tree(value: Any?): CompilerNode? =
        if (value == null || holdsCompilerTree(value)) null else tree(Neo4jObjectMapper.instance.valueToTree(value))

    override fun text(value: Any?): String? =
        if (holdsCompilerTree(value)) null else Neo4jObjectMapper.instance.writeValueAsString(value)

    /** Whether [value] is, or holds at any depth, a tree of the compiler's, which Jackson knows nothing of. */
    private fun holdsCompilerTree(value: Any?): Boolean = when (value) {
        is CompilerNode -> true
        is Map<*, *> -> value.any { (k, v) -> holdsCompilerTree(k) || holdsCompilerTree(v) }
        is Iterable<*> -> value.any(::holdsCompilerTree)
        is Array<*> -> value.any(::holdsCompilerTree)
        else -> false
    }

    /** Jackson's [node] as the compiler's tree of the same shape. */
    fun tree(node: JsonNode): CompilerNode = when (node.nodeType) {
        JsonNodeType.NULL, JsonNodeType.MISSING, null -> CompilerNull
        JsonNodeType.STRING -> CompilerText(node.textValue())
        JsonNodeType.BOOLEAN -> CompilerBoolean(node.booleanValue())
        // As Jackson writes a byte[]: its base64 text.
        JsonNodeType.BINARY -> CompilerText(Base64.getEncoder().encodeToString(node.binaryValue()))
        JsonNodeType.NUMBER -> when {
            node.isIntegralNumber && node.canConvertToLong() -> CompilerLong(node.longValue())
            node.isIntegralNumber -> CompilerBigInteger(com.ionspin.kotlin.bignum.integer.BigInteger.parseString(node.bigIntegerValue().toString()))
            node.isFloat -> CompilerFloat(node.floatValue())
            node.isBigDecimal -> node.decimalValue().let {
                CompilerDecimal(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString(it.toString()), it.toPlainString(), it.toString())
            }
            else -> CompilerDouble(node.doubleValue())
        }
        JsonNodeType.ARRAY -> CompilerArray().apply { node.forEach { add(tree(it)) } }
        JsonNodeType.OBJECT -> CompilerObject().apply { node.properties().forEach { (k, v) -> set<CompilerNode>(k, tree(v)) } }
        JsonNodeType.POJO -> tree(Neo4jObjectMapper.instance.valueToTree((node as POJONode).pojo))
    }
}
