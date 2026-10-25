package org.drivine.model

import org.drivine.annotation.NodeFragment
import org.drivine.model.javafields.JavaBase
import org.drivine.model.javafields.JavaProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Java fragment's state includes its superclass's private fields, which Kotlin reflection leaves out: the `@NodeId`
 * among them, without which a save has no id to MERGE on (#193).
 */
class JavaSuperclassFieldsTest {

    @Test
    fun `a Java fragment takes its @NodeId from a superclass's private field`() {
        assertEquals("id", FragmentModel.from(JavaProbe::class.java).nodeIdField)
    }

    @Test
    fun `a Java fragment's fields include its superclass's private ones, as the base stores them`() {
        val fields = FragmentModel.from(JavaProbe::class.java).fields.associate { it.name to it.propertyName }
        assertEquals(
            mapOf("createdOn" to "created_on", "id" to "id", "name" to "name", "shadowed" to "shadow"),
            fields,
        )
    }

    /** A Kotlin fragment over a Java base: Kotlin reflection reads its own, and the base's private ones are added. */
    @NodeFragment(labels = ["KotlinOverJava"])
    class KotlinOverJava : JavaBase() {
        var title: String = ""
    }

    @Test
    fun `a Kotlin fragment over a Java base takes the base's private fields and @NodeId too`() {
        val model = FragmentModel.from(KotlinOverJava::class.java)
        assertEquals("id", model.nodeIdField)
        // The base's transient cache is left out, as it is from a Java fragment.
        assertEquals(listOf("createdOn", "id", "shadowed", "title"), model.fields.map { it.name })
    }
}
