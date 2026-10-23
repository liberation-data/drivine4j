package org.drivine.autoconfigure

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * A runner whose environment leaves out the process's environment variables. Drivine reads
 * `DATABASE_DATASOURCES_*` variables too, and CI sets some, so a test must see only the datasources
 * it declares.
 */
internal fun isolatedRunner() = ApplicationContextRunner {
    AnnotationConfigApplicationContext().apply {
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
    }
}

/** Gives the runner these environment variables, as the process's environment would. */
internal fun ApplicationContextRunner.withEnvironmentVariables(variables: Map<String, String>) =
    withInitializer { context ->
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)
        )
    }
