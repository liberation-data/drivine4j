package org.drivine.springfree

import org.drivine.Drivine
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * These tests run on the library's own runtime classpath, which the build checks has no Spring on it.
 * This says so from inside: a class from each Spring jar the library once brought is not to be found.
 */
class NoSpringTest {

    @Test
    fun `no Spring class is on the classpath`() {
        listOf(
            "org.springframework.core.SpringVersion",
            "org.springframework.context.ApplicationContext",
            "org.springframework.transaction.PlatformTransactionManager",
            "org.springframework.boot.SpringApplication",
            "org.springframework.test.context.TestContext",
            "org.aspectj.weaver.Advice",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test
    fun `the library's classes load without it`() {
        Class.forName(Drivine::class.java.name)
        Class.forName(Transactions::class.java.name)
        Class.forName("org.drivine.manager.PersistenceManagerFactory")
    }
}
