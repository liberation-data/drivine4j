package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.UUID

/**
 * Base web user fragment - models a web user with basic properties.
 * Sealed class enables automatic subtype registration for polymorphic deserialization.
 *
 * TODO: Document in README that polymorphic fragments should be sealed classes
 *       (or use @JsonSubTypes for open class hierarchies).
 */
@NodeFragment(labels = ["WebUser"])
sealed class WebUser(
    @NodeId open val uuid: UUID,
    open val displayName: String
)

/**
 * Anonymous web user - extends WebUser with an additional "Anonymous" label.
 * Used to test label-based polymorphism.
 */
@NodeFragment(labels = ["WebUser", "Anonymous"])
data class AnonymousWebUser(
    @NodeId override val uuid: UUID,
    override val displayName: String,
    val anonymousToken: String
) : WebUser(uuid, displayName)

/**
 * Registered web user - extends WebUser with "Registered" label.
 */
@NodeFragment(labels = ["WebUser", "Registered"])
data class RegisteredWebUser(
    @NodeId override val uuid: UUID,
    override val displayName: String,
    val email: String
) : WebUser(uuid, displayName)