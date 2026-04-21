package org.drivine.connection

/**
 * Authentication mode for Amazon Neptune connections.
 */
enum class NeptuneAuthMode {
    /**
     * SigV4 IAM authentication. Requires AWS credentials available via
     * DefaultCredentialsProvider (env vars, ~/.aws/credentials, IAM role, etc.)
     * and the AWS SDK on the classpath.
     */
    IAM,

    /**
     * No authentication. Use with an SSH tunnel to a Neptune cluster
     * that has IAM auth disabled.
     */
    NONE,
}