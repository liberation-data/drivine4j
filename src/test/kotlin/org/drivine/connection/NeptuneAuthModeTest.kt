package org.drivine.connection

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NeptuneAuthModeTest {

    @Test
    fun `NeptuneAuthMode NONE is configurable via ConnectionProperties`() {
        val props = ConnectionProperties(
            type = DatabaseType.NEPTUNE,
            host = "localhost",
            port = 8182,
            neptuneAuth = NeptuneAuthMode.NONE,
        )
        assertEquals(NeptuneAuthMode.NONE, props.neptuneAuth)
    }

    @Test
    fun `NeptuneAuthMode IAM is configurable via ConnectionProperties`() {
        val props = ConnectionProperties(
            type = DatabaseType.NEPTUNE,
            host = "example.neptune.amazonaws.com",
            port = 8182,
            neptuneAuth = NeptuneAuthMode.IAM,
            region = "us-east-1",
        )
        assertEquals(NeptuneAuthMode.IAM, props.neptuneAuth)
        assertEquals("us-east-1", props.region)
    }

    @Test
    fun `NeptuneAuthMode defaults to IAM when not specified`() {
        val props = ConnectionProperties(
            type = DatabaseType.NEPTUNE,
            host = "example.neptune.amazonaws.com",
        )
        // null in properties → builder defaults to IAM
        assertEquals(null, props.neptuneAuth)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEPTUNE_BOLT_URL", matches = ".+")
    fun `SigV4 auth provider generates valid token`() {
        val host = System.getenv("NEPTUNE_BOLT_URL")
            ?.removePrefix("bolt://")?.substringBefore(":") ?: return
        val region = System.getenv("AWS_REGION") ?: "us-east-1"

        val provider = NeptuneSigV4AuthProvider(host, 8182, region)
        val token = provider.authToken()
        assertNotNull(token)
    }
}