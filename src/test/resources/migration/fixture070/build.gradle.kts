plugins {
    kotlin("jvm") version "2.2.20"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.drivine:drivine4j:0.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("FixtureKt") }
