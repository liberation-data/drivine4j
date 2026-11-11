package org.drivine.schema

import com.fasterxml.jackson.databind.JsonNode
import org.drivine.DrivineException
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.StoreError
import org.drivine.cypher.store.schema.StoreSchema
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.exceptions.Neo4jException
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A driver's error as the module's schema planner reads it ([StoreErrors]): each of `store.yaml`'s `errors`, thrown as
 * the drivers throw it, reads back to the causes the case writes, and the module's rules decide of it what the case
 * says on each store. And the edges a driver gives that the cases don't write: a Neo4j error with no code, a SQL error
 * with no vendor code, a chain that comes back on itself. No database is spoken to.
 */
class StoreErrorsTest {

    private val graph = StoreYaml.root["graph"].textValue()

    private val schemas: Map<String, StoreSchema> = mapOf(
        "neo4j" to StoreSchema.of(Neo4jDdl),
        "memgraph" to StoreSchema.of(MemgraphDdl()),
        "falkordb" to StoreSchema.of(FalkorDbDdl),
        "postgres" to StoreSchema.of(PostgresDdl(graph)),
        "oracle" to StoreSchema.of(OracleDdl(graph)),
        "sqlite" to StoreSchema.of(SqliteDdl(graph)),
    )

    @Test
    fun `each error store yaml pins reads back to its causes, and the module decides of it as the case says`() {
        val errors = StoreYaml.root["errors"].toList()
        assertTrue(errors.isNotEmpty())
        for (case in errors) {
            val name = case["name"].textValue()
            val causes = case["causes"].map(::cause)
            val read = StoreErrors.of(thrown(causes))
            // A Neo4j error the case gives no GQL status is given one by the driver, which the rules read as none.
            assertEquals(causes.map { it.copy(gqlStatus = it.gqlStatus ?: if (it.isNeo4jError) NO_STATUS else null) }, read.causes, name)
            for ((store, expected) in case["stores"].properties()) {
                val schema = schemas.getValue(store)
                assertEquals(expected["violation"].booleanValue(), schema.isViolation(read), "$name on $store: violation")
                assertEquals(expected["blockedByIndex"].booleanValue(), schema.isBlockedByIndex(read), "$name on $store: blocked by an index")
                assertEquals(expected["buildTimeout"].booleanValue(), schema.isBuildTimeout(read), "$name on $store: build timeout")
            }
        }
    }

    @Test
    fun `a SQL error is its state and vendor code, a vendor code of 0 where the driver gives none`() {
        val read = StoreErrors.of(SQLIntegrityConstraintViolationException("duplicate key value violates unique constraint", "23505"))
        assertEquals(listOf(Cause(sqlState = "23505", vendorCode = 0, message = "duplicate key value violates unique constraint")), read.causes)
        assertTrue(read.causes.single().isSqlError)
    }

    @Test
    fun `a Neo4j error with no code is given N-A, so that it still reads as a Neo4j driver's`() {
        val read = StoreErrors.of(Neo4jException(null, "no code"))
        assertEquals(StoreErrors.NO_CODE, read.causes.single().code)
        assertTrue(read.causes.single().isNeo4jError)
    }

    @Test
    fun `a subclass of a driver's error is read as the driver's, and any other error by its message alone`() {
        val read = StoreErrors.of(DrivineException("wrapped", ClientException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index")))
        assertEquals(Cause(message = "wrapped"), read.causes[0])
        assertEquals("Neo.ClientError.Schema.IndexAlreadyExists", read.causes[1].code)
        assertEquals("There already exists an index", read.causes[1].message)
        assertTrue(schemas.getValue("neo4j").isBlockedByIndex(read))
        assertEquals(listOf(Cause()), StoreErrors.of(IllegalStateException()).causes, "an error with no message")
    }

    @Test
    fun `a chain is read outermost first, each error once, though it comes back on itself`() {
        val inner = SQLException("ORA-00001: unique constraint violated", "23000", 1)
        val outer = RuntimeException("outer", inner)
        inner.initCause(outer)
        val read = StoreErrors.of(outer)
        assertEquals(listOf(Cause(message = "outer"), Cause(sqlState = "23000", vendorCode = 1, message = "ORA-00001: unique constraint violated")), read.causes)
        assertTrue(schemas.getValue("oracle").isViolation(read))
        assertEquals("outer | ORA-00001: unique constraint violated", read.messages)
    }

    companion object {
        /** The status drivine4j's Neo4j driver gives an error a server sent none for. */
        const val NO_STATUS = "50N42"

        fun cause(node: JsonNode): Cause = Cause(
            sqlState = node["sqlState"]?.textValue(),
            vendorCode = node["vendorCode"]?.intValue(),
            code = node["code"]?.textValue(),
            gqlStatus = node["gqlStatus"]?.textValue(),
            message = node["message"]?.textValue(),
        )

        /** [causes], the outermost first, thrown as the drivers throw them: a JDBC error, a Neo4j driver's, or another. */
        fun thrown(causes: List<Cause>): Throwable = causes.reversed().fold(null as Throwable?) { inner, cause ->
            when {
                cause.isSqlError -> SQLException(cause.message, cause.sqlState, cause.vendorCode ?: 0, inner)
                cause.isNeo4jError -> Neo4jException(cause.gqlStatus ?: NO_STATUS, cause.message ?: "", cause.code, cause.message, emptyMap(), inner)
                else -> RuntimeException(cause.message, inner)
            }
        }!!

        /** [error] as the planner is given it, back from what [thrown] made of it. */
        fun read(error: StoreError): StoreError = StoreErrors.of(thrown(error.causes))
    }
}
