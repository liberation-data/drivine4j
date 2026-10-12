package org.drivine.test

import org.drivine.test.MiniCypherGraph.Edge
import org.drivine.test.MiniCypherGraph.Node
import java.util.UUID

/**
 * A function a fixture may call: its [name], how many arguments it takes, and what it gives for
 * them. The ones the interpreter knows are those [named] finds. The parser looks a call's function
 * up there, so a function not listed, or one given more or fewer arguments than it takes, is
 * refused where it is written and never called.
 *
 * `count` is not among them. It is no function of one row, and is read as the whole of a `RETURN`.
 */
internal class MiniCypherFunction private constructor(val name: String, val arity: IntRange, private val body: (List<Any?>) -> Any?) {

    /** What the function gives for [arguments], which are as many as it takes. */
    operator fun invoke(arguments: List<Any?>): Any? = body(arguments)

    override fun toString(): String = name

    companion object {
        /**
         * The functions that wrap one value in an engine's own type, as a temporal or vector
         * constructor does, and here give the value as it is. A moment is so kept as its ISO text.
         */
        private val WRAPPERS = listOf("datetime", "localdatetime", "date", "localtime", "time", "duration", "vecf32", "vector32", "toFloat", "toInteger")

        private val KNOWN: Map<String, MiniCypherFunction> = (
            listOf(
                MiniCypherFunction("toString", 1..1) { it[0]?.toString() },
                MiniCypherFunction("coalesce", 1..Int.MAX_VALUE) { arguments -> arguments.firstOrNull { it != null } },
                MiniCypherFunction("type", 1..1) { (it[0] as? Edge)?.type },
                MiniCypherFunction("labels", 1..1) { (it[0] as? Node)?.labels?.toList() },
                // The stamp is a property on the graph engines, and a node that has one lists it.
                MiniCypherFunction("properties", 1..1) {
                    when (val element = it[0]) {
                        is Node -> element.all()
                        is Edge -> element.properties.toMap()
                        else -> null
                    }
                },
                MiniCypherFunction("keys", 1..1) {
                    when (val element = it[0]) {
                        is Node -> element.all().keys.toList()
                        is Edge -> element.properties.keys.toList()
                        else -> null
                    }
                },
                MiniCypherFunction("size", 1..1) {
                    when (val value = it[0]) {
                        is String -> value.length.toLong()
                        is Collection<*> -> value.size.toLong()
                        else -> null
                    }
                },
                MiniCypherFunction("substring", 2..3) { arguments ->
                    (arguments[0] as String?)?.let { value ->
                        val start = (arguments[1] as Number).toInt().coerceAtMost(value.length)
                        val length = (arguments.getOrNull(2) as Number?)?.toInt() ?: (value.length - start)
                        value.substring(start, (start + length).coerceAtMost(value.length))
                    }
                },
                MiniCypherFunction("left", 2..2) { (it[0] as String?)?.take((it[1] as Number).toInt()) },
                MiniCypherFunction("right", 2..2) { (it[0] as String?)?.takeLast((it[1] as Number).toInt()) },
                MiniCypherFunction("replace", 3..3) { (it[0] as String?)?.replace(it[1] as String, it[2] as String) },
                MiniCypherFunction("randomUUID", 0..0) { UUID.randomUUID().toString() },
            ) + WRAPPERS.map { wrapper -> MiniCypherFunction(wrapper, 1..1) { it[0] } }
            ).associateBy { it.name }

        /** The function a fixture calls by [name]; null when the interpreter knows none of that name. */
        fun named(name: String): MiniCypherFunction? = KNOWN[name]
    }
}
