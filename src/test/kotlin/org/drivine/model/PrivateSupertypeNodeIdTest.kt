package org.drivine.model

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A supertype's private `@NodeId` isn't a subclass's node id (#628): a private property isn't overridden, so a
 * subclass's property of the same name is its own, as the code generator reads it. A class left with no node id that
 * way is warned of, once.
 */
class PrivateSupertypeNodeIdTest {

    @NodeFragment(labels = ["Hidden"])
    open class Hidden { @NodeId private val key: String = "" }

    @NodeFragment(labels = ["NotOverridden"])
    class NotOverridden : Hidden() { val key: String = "" }

    @NodeFragment(labels = ["HiddenOnly"])
    class HiddenOnly : Hidden() { val name: String = "" }

    @NodeFragment(labels = ["Shown"])
    open class Shown { @NodeId protected open val key: String = "" }

    @NodeFragment(labels = ["Overridden"])
    class Overridden : Shown() { public override val key: String = "" }

    @NodeFragment(labels = ["Inherited"])
    open class Public { @NodeId val key: String = "" }

    @NodeFragment(labels = ["Inheriting"])
    class Inheriting : Public() { val name: String = "" }

    @NodeFragment(labels = ["OwnId"])
    class OwnId : Hidden() { @NodeId val uuid: String = "" }

    @NodeFragment(labels = ["HiddenGetter"])
    open class HiddenGetter { @get:NodeId private val key: String get() = "" }

    @NodeFragment(labels = ["NotOverriddenGetter"])
    class NotOverriddenGetter : HiddenGetter() { val key: String = "" }

    interface HiddenInInterface { @get:NodeId private val key: String get() = "" }

    @NodeFragment(labels = ["Implementing"])
    class Implementing : HiddenInInterface { val key: String = "" }

    @NodeFragment(labels = ["Middle"])
    open class Middle : Hidden() { val name: String = "" }

    @NodeFragment(labels = ["Deep"])
    class Deep : Middle() { val key: String = "" }

    private val logger = LoggerFactory.getLogger(FragmentModel::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
    }

    private fun warningsOf(clazz: Class<*>) =
        appender.list.filter { it.formattedMessage.startsWith("${clazz.kotlin.qualifiedName} has no @NodeId") }

    @Test
    fun `a supertype's private @NodeId isn't the id of a subclass's property of the same name`() {
        assertNull(FragmentModel.from(NotOverridden::class.java).nodeIdField)
    }

    @Test
    fun `a supertype's private @NodeId isn't a subclass's id at all`() {
        assertNull(FragmentModel.from(HiddenOnly::class.java).nodeIdField)
    }

    @Test
    fun `the class that declares a private @NodeId has it as its id`() {
        assertEquals("key", FragmentModel.from(Hidden::class.java).nodeIdField)
        assertTrue(warningsOf(Hidden::class.java).isEmpty())
    }

    @Test
    fun `a protected @NodeId overridden in a subclass is its id`() {
        assertEquals("key", FragmentModel.from(Overridden::class.java).nodeIdField)
        assertTrue(warningsOf(Overridden::class.java).isEmpty())
    }

    @Test
    fun `a public @NodeId inherited by a subclass is its id`() {
        assertEquals("key", FragmentModel.from(Inheriting::class.java).nodeIdField)
    }

    @Test
    fun `a subclass that marks its own @NodeId has it, and isn't warned of`() {
        assertEquals("uuid", FragmentModel.from(OwnId::class.java).nodeIdField)
        assertTrue(warningsOf(OwnId::class.java).isEmpty())
    }

    @Test
    fun `a supertype's private @get-NodeId getter isn't a subclass's id`() {
        assertEquals("key", FragmentModel.from(HiddenGetter::class.java).nodeIdField)
        assertNull(FragmentModel.from(NotOverriddenGetter::class.java).nodeIdField)
    }

    @Test
    fun `an interface's private @NodeId isn't an implementing class's id`() {
        assertNull(FragmentModel.from(Implementing::class.java).nodeIdField)
    }

    @Test
    fun `a private @NodeId further up the hierarchy isn't a subclass's id`() {
        assertNull(FragmentModel.from(Deep::class.java).nodeIdField)
    }

    /** Read only by the warning's test, as a class is warned of only once in a JVM. */
    @NodeFragment(labels = ["Warned"])
    class Warned : Hidden() { val key: String = "" }

    @Test
    fun `a class left with no id by a private supertype @NodeId is warned of once, naming the property`() {
        FragmentModel.from(Warned::class.java)
        FragmentModel.from(Warned::class.java)
        val warnings = warningsOf(Warned::class.java)
        assertEquals(1, warnings.size, "warned ${warnings.size} times")
        assertEquals(
            "${Warned::class.qualifiedName} has no @NodeId: ${Hidden::class.qualifiedName}.key is private, and a " +
                "supertype's private property isn't inherited as a node id. Make it protected or public, or put @NodeId " +
                "on Warned's own property.",
            warnings.single().formattedMessage,
        )
        assertEquals(ch.qos.logback.classic.Level.WARN, warnings.single().level)
    }
}
