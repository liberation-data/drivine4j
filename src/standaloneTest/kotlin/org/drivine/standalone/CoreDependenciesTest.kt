package org.drivine.standalone

import org.drivine.Drivine
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * These tests run on the core's own runtime classpath. Its Spring support is in drivine4j-spring and the
 * starter, so a class from each Spring jar the core once brought along is not on it.
 */
class CoreDependenciesTest {

    @Test
    fun `the core brings no Spring module of its own`() {
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
    fun `the core's classes load on its own classpath`() {
        Class.forName(Drivine::class.java.name)
        Class.forName(Transactions::class.java.name)
        Class.forName("org.drivine.manager.PersistenceManagerFactory")
    }
}
