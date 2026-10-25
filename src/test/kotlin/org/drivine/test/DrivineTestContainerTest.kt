package org.drivine.test

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for DrivineTestContainer functionality.
 */
@SpringBootTest(classes = [DrivineTestContainerTest.TestConfig::class])
class DrivineTestContainerTest @Autowired constructor(
    private val persistenceManager: PersistenceManager
) {

    @Configuration
    @EnableDrivine
    @EnableDrivineTestConfig
    class TestConfig {
        @Bean
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
            return factory.get("neo")
        }
    }

    /**
     * Tests that verify APOC Core and APOC Extended procedures are available.
     */
    @Nested
    inner class ApocAvailabilityTest {

        @Test
        fun `APOC Core procedures are available`() {
            // apoc.help is a core APOC procedure
            val result = persistenceManager.query(
                QuerySpecification
                    .withStatement("CALL apoc.help('help') YIELD name RETURN name LIMIT 1")
                    .transform<String>()
            )

            assertNotNull(result)
            assertTrue(result.isNotEmpty(), "APOC Core should be available - apoc.help returned no results")
        }

        @Test
        fun `APOC version is available`() {
            // Check APOC version function
            val result = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("RETURN apoc.version() AS version")
                    .transform<String>()
            )

            val server = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("CALL dbms.components() YIELD name, versions WHERE name = 'Neo4j Kernel' RETURN versions[0] AS version")
                    .transform<String>()
            )

            assertNotNull(result, "APOC version should be available")
            // NEO4J_PLUGINS installs the APOC Core release of the server's own version, whatever the image.
            assertEquals(server, result, "APOC Core should be the server's own release")
            println("APOC version: $result")
        }

        /**
         * `apoc.text.clean` and `apoc.convert.fromJsonMap` below are in APOC Core, so they pass without
         * APOC Extended. This one fails unless the APOC Extended release `build.gradle` downloads is
         * mounted, and loads on the default image.
         */
        @Test
        fun `APOC Extended is loaded on the default image`() {
            assumeTrue(DrivineTestContainer.image() == DrivineTestContainer.DEFAULT_IMAGE, "APOC Extended is mounted into the default image only")
            val extended = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("CALL apoc.help('') YIELD core WHERE NOT core RETURN count(*) AS extended")
                    .transform<Long>()
            )

            assertTrue(extended > 0, "APOC Extended should give procedures and functions beyond APOC Core")
        }

        @Test
        fun `APOC Extended procedures are available`() {
            // apoc.text.clean is an APOC Extended procedure (not in core)
            // It removes whitespace and lowercases text
            val result = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("RETURN apoc.text.clean('  Hello   World  ') AS cleaned")
                    .transform<String>()
            )

            assertNotNull(result, "APOC Extended should be available - apoc.text.clean failed")
            assertEquals("helloworld", result, "apoc.text.clean should clean and lowercase text")
            println("APOC Extended verified with apoc.text.clean: $result")
        }

        @Test
        fun `APOC Extended JSON procedures are available`() {
            // apoc.convert.fromJsonMap is an Extended procedure
            val result = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("""RETURN apoc.convert.fromJsonMap('{"name":"test"}').name AS name""")
                    .transform<String>()
            )

            assertNotNull(result, "APOC Extended JSON functions should be available")
            assertEquals("test", result, "JSON parsing should work")
        }
    }

    /**
     * Neo4j 5.26.0 to 5.26.3, 2025.01 and 2025.02 cache the plan of a statement with a dynamic relationship type
     * together with the first type it ran with, and write that type for every type after it
     * (neo4j/neo4j#13597). The container's image must not be one of them.
     */
    @Nested
    inner class DynamicRelationshipTypeTest {

        @Test
        fun `the second of two dynamic relationship types is written with its own type`() {
            val statement = "MERGE (a:DynamicTypeProbe {id: \$from}) MERGE (b:DynamicTypeProbe {id: \$to}) " +
                "CREATE (a)-[:\$(\$relType)]->(b)"
            try {
                persistenceManager.execute(
                    QuerySpecification.withStatement(statement)
                        .bind(mapOf("from" to "e1", "to" to "e2", "relType" to "FIRST_TYPE"))
                )
                persistenceManager.execute(
                    QuerySpecification.withStatement(statement)
                        .bind(mapOf("from" to "e3", "to" to "e4", "relType" to "SECOND_TYPE"))
                )
                val written = persistenceManager.query(
                    QuerySpecification
                        .withStatement(
                            "MATCH (a:DynamicTypeProbe)-[r]->(b:DynamicTypeProbe) RETURN a.id + '>' + type(r) AS edge ORDER BY edge"
                        )
                        .transform<String>()
                )
                assertEquals(listOf("e1>FIRST_TYPE", "e3>SECOND_TYPE"), written, "on ${DrivineTestContainer.image()}")
            } finally {
                persistenceManager.execute(
                    QuerySpecification.withStatement("MATCH (n:DynamicTypeProbe) DETACH DELETE n")
                )
            }
        }
    }
}
