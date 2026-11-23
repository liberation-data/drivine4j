// drivine4j 0.7.0, the last release of store layout 1, in a build of its own: drivine4j's own build is version 0.7.0
// until a release sets it, so there a dependency on 0.7.0 would be taken to be the checkout. See Fixture.kt.
plugins {
    kotlin("jvm") version "2.2.20"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.drivine:drivine4j:0.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.oracle.database.jdbc:ojdbc11:23.7.0.25.01")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("FixtureKt") }
