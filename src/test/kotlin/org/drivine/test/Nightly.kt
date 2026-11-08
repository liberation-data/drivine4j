package org.drivine.test

import org.junit.jupiter.api.Tag

/**
 * Marks a test class, or one test, that a pull request's CI leaves out, and the full set runs: each night
 * (`nightly.yml`), on a push to main or develop, in a release's gate, and on a pull request labelled `full-ci`. They
 * are the timings, the plans Oracle makes, the texts past 32 KB on Oracle, the Postgres tests that vacuum or analyze a
 * table to a state, and Oracle's slow APOC batch (#675). A local `./gradlew test` runs them as any other:
 * `gradle/ci-test-sets.gradle` picks the sets, and TEST_CONTAINERS.md says how.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("nightly")
annotation class Nightly
