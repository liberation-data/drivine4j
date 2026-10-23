package org.drivine.starter

import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.sample.fragment.Person
import org.drivine.schema.SchemaManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A Spring Boot application that has the starter and no `@EnableDrivine`: the datasource in
 * `application.yaml` is enough for Drivine's beans, and they reach the store.
 *
 * `@EnableDrivineTestConfig` only points that datasource at a Testcontainer. The package is outside
 * `org.drivine.sample`, so `SampleAppContext`'s component scan does not bring this auto-configured
 * context into the other tests.
 */
@SpringBootTest(classes = [StarterAutoConfigurationTest.StarterApp::class])
class StarterAutoConfigurationTest @Autowired constructor(
    private val factory: GraphObjectManagerFactory,
    private val schemaManager: SchemaManager,
) {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableDrivineTestConfig
    class StarterApp

    @Test
    fun `the starter's beans save and load through the configured datasource`() {
        val manager = factory.stateless()
        val person = Person(UUID.randomUUID(), "Starter Person", "auto-configured")

        manager.save(person)
        try {
            assertEquals(person, manager.load(person.uuid.toString(), Person::class.java))
        } finally {
            manager.delete(person.uuid.toString(), Person::class.java)
        }
        assertNull(manager.load(person.uuid.toString(), Person::class.java))
        assertNotNull(schemaManager)
    }
}
