package org.drivine.autoconfigure

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

/**
 * Enable automatic test configuration for Drivine with smart Testcontainers integration.
 *
 * This annotation provides the best of both worlds for testing:
 * - **Local Development**: Use your local Neo4j instance (fast, inspectable with rollback=false)
 * - **CI/Automated Tests**: Automatically use Testcontainers (no setup needed)
 *
 * ## How It Works
 *
 * 1. **Define your datasource in application-test.yml**:
 * ```yaml
 * database:
 *   datasources:
 *     neo:
 *       host: localhost
 *       port: 7687
 *       username: neo4j
 *       password: password
 *       type: NEO4J
 *       database-name: neo4j
 * ```
 *
 * 2. **Use @EnableDrivineTestConfig**:
 * ```kotlin
 * @Configuration
 * @EnableDrivine
 * @EnableDrivineTestConfig
 * class TestConfig
 * ```
 *
 * 3. **Control behavior with environment variable or system property**:
 * ```bash
 * # Use local Neo4j (for development)
 * export USE_LOCAL_NEO4J=true
 * ./gradlew test
 *
 * # Use Testcontainers (for CI, default)
 * ./gradlew test  # USE_LOCAL_NEO4J defaults to false
 * ```
 *
 * ## What Happens Automatically
 *
 * ### When USE_LOCAL_NEO4J=true (Local Development)
 * - Uses connection settings from application-test.yml as-is
 * - Connects to your local Neo4j instance
 * - Fast test execution
 * - Can inspect database with Neo4j Browser
 * - Set @Rollback(false) to examine data after test
 *
 * ### When USE_LOCAL_NEO4J=false (CI/Testcontainers - Default)
 * - Starts a Neo4j Testcontainer automatically
 * - **Overrides** host, port, and password from your properties with container values
 * - Keeps username, type, database-name from your properties
 * - No Docker/Neo4j setup needed on CI
 * - Tests run in isolation
 *
 * ## Benefits
 *
 * ✅ **One configuration** works for both local dev and CI
 * ✅ **Zero boilerplate** - no manual DrivineTestContainer.getConnectionUrl() parsing
 * ✅ **Fast local development** with real Neo4j
 * ✅ **Reliable CI** with Testcontainers
 * ✅ **Easy debugging** - rollback=false and inspect your local DB
 *
 * ## Example
 *
 * ```kotlin
 * @SpringBootTest
 * @Transactional
 * @Rollback(true)  // Change to false to inspect DB after test
 * class MyTest @Autowired constructor(
 *     private val graphObjectManager: GraphObjectManager
 * ) {
 *     @Test
 *     fun myTest() {
 *         // Just write tests - connection handling is automatic!
 *     }
 * }
 * ```
 */
@Target(CLASS)
@Retention(RUNTIME)
@EnableConfigurationProperties(DrivineProperties::class)
@Import(DrivineTestConfiguration::class)
annotation class EnableDrivineTestConfig
