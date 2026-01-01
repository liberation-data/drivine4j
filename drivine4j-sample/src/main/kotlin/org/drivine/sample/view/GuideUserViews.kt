package org.drivine.sample.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.sample.fragment.AnonymousWebUser
import org.drivine.sample.fragment.GuideUser
import org.drivine.sample.fragment.WebUser

/**
 * View with NULLABLE WebUser relationship.
 * Should return all GuideUsers, even those without a WebUser.
 */
@GraphView
data class GuideUserWithOptionalWebUser(
    @Root val core: GuideUser,

    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser?  // Nullable - optional relationship
)

/**
 * View with NON-NULLABLE WebUser relationship.
 * Should only return GuideUsers that have a WebUser relationship.
 * This tests the WHERE EXISTS clause generation.
 */
@GraphView
data class GuideUserWithRequiredWebUser(
    @Root val core: GuideUser,

    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser  // Non-nullable - required relationship
)

/**
 * View with NON-NULLABLE AnonymousWebUser relationship.
 * Should only return GuideUsers that have an Anonymous WebUser specifically.
 * This tests both EXISTS clause generation AND specific label matching.
 */
@GraphView
data class AnonymousGuideUser(
    @Root val core: GuideUser,

    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: AnonymousWebUser  // Non-nullable - requires Anonymous specifically
)

/**
 * View for testing polymorphic deserialization.
 * The WebUser should deserialize to the correct subtype based on labels.
 */
@GraphView
data class GuideUserWithPolymorphicWebUser(
    @Root val core: GuideUser,

    @GraphRelationship(type = "IS_WEB_USER", direction = Direction.OUTGOING)
    val webUser: WebUser?  // Nullable, but should deserialize to correct subtype
)