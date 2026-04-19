package org.drivine.connection

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "NEPTUNE_IAM_AUTH", matches = "true")
class NeptuneSigV4Test {

    @Test
    fun `debug - print signed URL`() {
        val host = System.getenv("NEPTUNE_HOST")
            ?: "drivine-test.cluster-cchissy84hz0.us-east-1.neptune.amazonaws.com"
        val region = System.getenv("AWS_REGION") ?: "us-east-1"

        val provider = NeptuneSigV4AuthProvider(host, 8182, region)
        val token = provider.authToken()

        // The token is AuthTokens.basic("", signedUrl)
        // Let's inspect what's inside
        println("Auth token class: ${token.javaClass}")
        println("Auth token: $token")
    }
}