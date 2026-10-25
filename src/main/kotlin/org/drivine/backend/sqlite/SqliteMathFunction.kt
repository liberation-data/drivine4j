package org.drivine.backend.sqlite

import org.sqlite.Function
import org.sqlite.core.Codes
import java.sql.SQLException

/**
 * `drivine_math(name, number[, number])`: openCypher's function of numbers [name] (`exp`, `log`, `sin`, `atan2` …),
 * worked out by Java's `Math`, as Neo4j works it out, so each gives the double Neo4j gives. Null where a number is null.
 * Where Neo4j gives NaN or an infinity (`log(0)`, `sqrt(-1)`, `exp(1000)`), it fails: JSON holds neither, and Postgres
 * and Oracle fail there too. SQLite's own functions give null for some of them.
 */
internal class SqliteMathFunction : Function() {

    override fun xFunc() {
        val name = requireNotNull(value_text(0))
        val numbers = (1 until args()).map { if (value_type(it) == Codes.SQLITE_NULL) return result() else value_double(it) }
        val x = numbers[0]
        val value = when (name) {
            "exp" -> Math.exp(x)
            "log" -> Math.log(x)
            "log10" -> Math.log10(x)
            "sqrt" -> Math.sqrt(x)
            "sin" -> Math.sin(x)
            "cos" -> Math.cos(x)
            "tan" -> Math.tan(x)
            "cot" -> 1.0 / Math.tan(x)
            "asin" -> Math.asin(x)
            "acos" -> Math.acos(x)
            "atan" -> Math.atan(x)
            "atan2" -> Math.atan2(x, numbers[1])
            "degrees" -> Math.toDegrees(x)
            "radians" -> Math.toRadians(x)
            "haversin" -> (1.0 - Math.cos(x)) / 2.0
            else -> throw SQLException("${SqliteFunctions.MATH} has no function $name.")
        }
        if (!value.isFinite()) {
            throw SQLException("$name() of ${numbers.joinToString(", ")} is $value, which JSON holds no value for: a SQL store does not give it.")
        }
        result(value)
    }
}
