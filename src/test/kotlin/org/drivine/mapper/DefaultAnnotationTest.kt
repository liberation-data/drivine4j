package org.drivine.mapper

import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.annotation.Default
import org.drivine.annotation.EmptyWhenAbsent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Verifies the [Default] / [EmptyWhenAbsent] annotations against Drivine's real configured mapper
 * ([Neo4jObjectMapper.instance]). The input mirrors how Drivine's `{ roles: n.roles }` projection
 * surfaces a missing property: the key is present with a null value.
 */
class DefaultAnnotationTest {

    data class KotlinNode(
        val id: String,
        @Default val roles: List<String> = emptyList(),
        @Default val status: String = "active",
        @EmptyWhenAbsent val tags: List<String>,   // no declared default
    )

    /** Kotlin fixture using only @EmptyWhenAbsent, with no declared defaults, on a list and a map. */
    data class KotlinEmptyNode(
        val id: String,
        @EmptyWhenAbsent val roles: List<String>,
        @EmptyWhenAbsent val attributes: Map<String, String>,
    )

    private val mapper = Neo4jObjectMapper.instance

    @Test
    fun `Default falls back to the declared default on present-null`() {
        val node = mapper.readValue<KotlinNode>(
            """{ "id": "n1", "roles": null, "status": null, "tags": null }"""
        )
        assertEquals(emptyList(), node.roles)
        assertEquals("active", node.status)
        assertEquals(emptyList(), node.tags)   // EmptyWhenAbsent, no default needed
    }

    @Test
    fun `provided values always win`() {
        val node = mapper.readValue<KotlinNode>(
            """{ "id": "n1", "roles": ["admin"], "status": "banned", "tags": ["x"] }"""
        )
        assertEquals(listOf("admin"), node.roles)
        assertEquals("banned", node.status)
        assertEquals(listOf("x"), node.tags)
    }

    @Test
    fun `EmptyWhenAbsent works for Kotlin - list and map, no declared defaults`() {
        val node = mapper.readValue<KotlinEmptyNode>(
            """{ "id": "n1", "roles": null, "attributes": null }"""
        )
        assertEquals(emptyList(), node.roles)
        assertEquals(emptyMap(), node.attributes)
    }

    @Test
    fun `EmptyWhenAbsent works for a Java record (no field initializer)`() {
        val rec = mapper.readValue<JavaRecordDefaults>(
            """{ "name": "alice", "roles": null, "attributes": null }"""
        )
        assertEquals(emptyList(), rec.roles)
        assertEquals(emptyMap(), rec.attributes)
    }
}
