package org.drivine.studio

import org.drivine.manager.PersistenceManager
import org.drivine.test.TestCleanup
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** The sample domain behind the studio, seeded, for trying the browser app against. */
@Configuration
@Import(StudioTestContext::class)
class StudioDemoApplication {
    @Bean
    fun seedSampleGraph(persistenceManager: PersistenceManager) = ApplicationRunner {
        TestCleanup.beforeEach(persistenceManager, "studio-demo")
        SampleGraph.seed(persistenceManager, "studio-demo")
    }
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(StudioDemoApplication::class.java)
        .properties("server.port=8087")
        .run(*args)
}
