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
        if (value == null || holdsCompilerTree(value)) null else tree(Neo4jObjectMapper.instance.valueToTree(plain(value)))

    override fun text(value: Any?): String? =
        if (holdsCompilerTree(value)) null else Neo4jObjectMapper.instance.writeValueAsString(plain(value))

    /**
     * [value] with each list, set and map of Kotlin's own classes, at any depth, as a `java.util` one, in the same order,
     * so that Jackson writes it as it would the copy: Jackson's Kotlin module asks kotlin-reflect of a Kotlin class it
     * writes, as Kotlin's `EmptyList`, which `emptyList()` gives and the compiler's values hold, and in the drivine CLI's
     * native image kotlin-reflect knows no such class, and fails. So in a native image ([native]) only what holds one is
     * copied: a value with none, as most are, `listOf(a, b)` and `mapOf` being the JDK's, is [value] itself. On a JVM it
     * is [value] itself always. A collection of the application's own class is left as it is, for Jackson to write as that
     * class says.
     */
    internal fun plain(value: Any?, native: Boolean = NATIVE): Any? = if (native && holdsKotlin(value)) copied(value) else value

    /**
     * Whether this runs as a native image, as the drivine CLI's binary does: GraalVM sets the property to `runtime` there,
     * as `ImageInfo.inImageRuntimeCode()` reads it, so drivine4j needs no GraalVM API. On a JVM, where kotlin-reflect reads
     * every class, [plain] copies nothing, and a write pays nothing for it. Read when the object is first used, which the
     * native image does when it runs: the CLI's build initializes no class of drivine4j's while it builds.
     */
    private val NATIVE: Boolean = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"

    /** Whether [value] is, or holds at any depth, a collection of Kotlin's own class: a walk that makes nothing. */
    private fun holdsKotlin(value: Any?): Boolean = when (value) {
        is Map<*, *> -> own(value) && (kotlin(value) || value.any { (k, v) -> holdsKotlin(k) || holdsKotlin(v) })
        is Collection<*> -> own(value) && (kotlin(value) || value.any(::holdsKotlin))
        is Array<*> -> value.any(::holdsKotlin)
        else -> false
    }

    /**
     * [value] with each list, set and map of the JDK's or Kotlin's own, and each array of objects, as a `java.util` list or
     * map. A set is copied to a list, which Jackson writes alike, so that two of its items that are equal only as copies, as
     * two arrays of the same items are, stay two; and a map's keys are kept as they are, which Jackson writes as their text.
     */
    private fun copied(value: Any?): Any? = when {
        value is Map<*, *> && own(value) -> value.entries.associateTo(LinkedHashMap(value.size)) { (k, v) -> k to copied(v) }
        value is Collection<*> && own(value) -> value.mapTo(ArrayList(value.size), ::copied)
        value is Array<*> -> value.mapTo(ArrayList(value.size), ::copied)
        else -> value
    }

    /** Whether [value]'s class is the JDK's or Kotlin's own, not the application's. */
    private fun own(value: Any): Boolean = value.javaClass.name.let { it.startsWith("java.util.") || kotlin(value) }

    /** Whether [value]'s class is Kotlin's own, which the native image's kotlin-reflect can't read. */
    private fun kotlin(value: Any): Boolean = value.javaClass.name.startsWith("kotlin.")

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
        JsonNodeType.POJO -> tree(Neo4jObjectMapper.instance.valueToTree(plain((node as POJONode).pojo)))
    }
}
