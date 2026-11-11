package org.drivine.connection

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ConnectionProperties] printed: its password never, as an application may log the properties it builds Drivine from,
 * or the [DataSourceMap] that holds them.
 */
class ConnectionPropertiesTest {

    private val properties = ConnectionProperties(
        type = DatabaseType.POSTGRES, host = "h", port = 5432, userName = "graph", password = "hunter2", databaseName = "db",
    )

    @Test
    fun `the password prints as stars, and the rest as given`() {
        val printed = properties.toString()
        assertTrue("hunter2" !in printed, printed)
        assertTrue("password=***" in printed && "userName=graph" in printed && "host=h" in printed && "databaseName=db" in printed, printed)
    }

    @Test
    fun `no password prints as null, not as stars`() {
        assertTrue("password=null" in properties.copy(password = null).toString())
    }

    @Test
    fun `a data source map of properties prints no password either`() {
        val printed = DataSourceMap(mapOf("main" to properties)).toString()
        assertTrue("hunter2" !in printed, printed)
    }

    @Test
    fun `every property is printed, so one added later isn't left out`() {
        val printed = properties.toString()
        val missing = ConnectionProperties::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            // As `(name=` or `, name=`: a bare `name=` is also found in `databaseName=`.
            .filter { "($it=" !in printed && ", $it=" !in printed }
        assertEquals(emptyList(), missing, printed)
    }

    @Test
    fun `equality and copies still hold the password`() {
        assertEquals("hunter2", properties.copy(host = "other").password)
        assertEquals(properties, properties.copy())
    }
}
