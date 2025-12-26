package org.drivine.test

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
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

            assertNotNull(result, "APOC version should be available")
            assertTrue(result.startsWith("5."), "APOC version should be 5.x, got: $result")
            println("APOC version: $result")
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
}