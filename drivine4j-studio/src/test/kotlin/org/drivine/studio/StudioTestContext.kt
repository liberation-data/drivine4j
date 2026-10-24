package org.drivine.studio

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy

/** The sample domain behind the studio endpoints. */
@Configuration
@EnableAutoConfiguration
@EnableDrivine
@EnableDrivineTestConfig
@EnableDrivineStudio(basePackages = ["org.drivine.sample"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class StudioTestContext {
    @Bean
    fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("default")
}

/** A few issues, the people on them and where those people work, each marked with [createdBy]. */
object SampleGraph {
    fun seed(persistenceManager: PersistenceManager, createdBy: String) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (acme:Organization:Mapped {uuid: randomUUID(), name: 'Acme Corp', createdBy: ${'$'}by})
                CREATE (globex:Organization:Mapped {uuid: randomUUID(), name: 'Globex', createdBy: ${'$'}by})
                CREATE (alice:Person:Mapped {uuid: randomUUID(), name: 'Alice Developer', bio: 'Senior Engineer', createdBy: ${'$'}by})
                CREATE (bob:Person:Mapped {uuid: randomUUID(), name: 'Bob Reviewer', bio: 'Staff Engineer', createdBy: ${'$'}by})
                CREATE (carol:Person:Mapped {uuid: randomUUID(), name: 'Carol Reporter', bio: null, createdBy: ${'$'}by})
                CREATE (alice)-[:WORKS_FOR {startDate: date('2021-03-01'), role: 'Engineer'}]->(acme)
                CREATE (bob)-[:WORKS_FOR {startDate: date('2019-06-15'), role: 'Reviewer'}]->(acme)
                CREATE (carol)-[:WORKS_FOR {startDate: date('2023-01-09'), role: 'Analyst'}]->(globex)
                CREATE (i1:Issue {uuid: randomUUID(), id: 101, title: 'Studio: implement feature X', body: 'First', state: 'open', locked: false, createdBy: ${'$'}by})
                CREATE (i2:Issue {uuid: randomUUID(), id: 102, title: 'Studio: fix the flaky test', body: 'Second', state: 'open', locked: false, createdBy: ${'$'}by})
                CREATE (i3:Issue {uuid: randomUUID(), id: 103, title: 'Studio: retire the old endpoint', body: 'Third', state: 'closed', locked: true, createdBy: ${'$'}by})
                CREATE (i1)-[:ASSIGNED_TO]->(alice), (i1)-[:ASSIGNED_TO]->(bob), (i1)-[:RAISED_BY]->(carol)
                CREATE (i2)-[:ASSIGNED_TO]->(alice), (i2)-[:RAISED_BY]->(bob)
                CREATE (i3)-[:ASSIGNED_TO]->(bob), (i3)-[:RAISED_BY]->(carol)
                """.trimIndent()
            ).bind(mapOf("by" to createdBy))
        )
    }
}
