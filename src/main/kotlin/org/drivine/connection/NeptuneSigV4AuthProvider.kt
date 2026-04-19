package org.drivine.connection

import com.fasterxml.jackson.databind.ObjectMapper
import org.neo4j.driver.AuthToken
import org.neo4j.driver.AuthTokens
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.signer.Aws4Signer
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.http.SdkHttpFullRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.regions.Region
import java.net.URI

/**
 * Generates SigV4-signed auth tokens for Neptune IAM authentication via Bolt.
 *
 * Neptune's Bolt auth expects the password to be a JSON object containing
 * the SigV4 signing headers (Authorization, X-Amz-Date, Host, HttpMethod).
 * This differs from the pre-signed URL approach used for HTTPS.
 *
 * Requires AWS credentials via DefaultCredentialsProvider.
 */
class NeptuneSigV4AuthProvider(
    private val host: String,
    private val port: Int,
    private val region: String,
) {
    private val logger = LoggerFactory.getLogger(NeptuneSigV4AuthProvider::class.java)
    private val credentialsProvider = DefaultCredentialsProvider.create()
    private val signer = Aws4Signer.create()
    private val json = ObjectMapper()

    fun authToken(): AuthToken {
        logger.debug("Generating Neptune SigV4 auth token for {}:{}", host, port)

        val credentials = credentialsProvider.resolveCredentials()

        val request = SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.GET)
            .uri(URI.create("https://$host:$port/opencypher"))
            .putHeader("Host", "$host:$port")
            .build()

        val signerParams = Aws4SignerParams.builder()
            .signingRegion(Region.of(region))
            .signingName("neptune-db")
            .awsCredentials(credentials)
            .build()

        val signedRequest = signer.sign(request, signerParams)

        // Build the auth JSON that Neptune expects as the Bolt password
        val authInfo = mutableMapOf<String, Any?>(
            "Authorization" to signedRequest.firstMatchingHeader("Authorization").orElse(null),
            "HttpMethod" to "GET",
            "X-Amz-Date" to signedRequest.firstMatchingHeader("X-Amz-Date").orElse(null),
            "Host" to signedRequest.firstMatchingHeader("Host").orElse("$host:$port"),
        )

        // Include security token for temporary credentials (STS/assumed roles)
        signedRequest.firstMatchingHeader("X-Amz-Security-Token").ifPresent {
            authInfo["X-Amz-Security-Token"] = it
        }

        val authJson = json.writeValueAsString(authInfo)
        logger.debug("Neptune auth JSON keys: {}", authInfo.keys)

        return AuthTokens.basic("username", authJson, "realm")
    }
}