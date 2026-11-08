package org.drivine.backend.sqlite

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.drivine.cypher.store.SqliteFunctions as Shared

/**
 * The compiler names the functions its SQL calls on SQLite ([Shared]), and drivine4j registers them ([SqliteFunctions.register]):
 * a name the compiler gains when the pin moves fails here, not in the first statement that calls it.
 */
class SqliteFunctionsTest {

    @Test
    fun `every function the compiler's SQL calls is registered on a connection`() {
        val names = Shared::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
        assertTrue(names.size > 20, "Found only $names in the compiler's SqliteFunctions")
        val registered = DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            SqliteFunctions.register(connection)
            connection.createStatement().executeQuery("SELECT name FROM pragma_function_list").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
            }
        }
        assertEquals(emptySet(), names - registered, "Functions the compiler calls that SqliteFunctions.register doesn't register")
    }
}
