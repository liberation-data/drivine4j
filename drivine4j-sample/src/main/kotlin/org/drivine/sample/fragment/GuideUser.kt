package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.UUID

/**
 * Guide user fragment - models a user in a guide/tutorial system.
 * Can be linked to a WebUser via IS_WEB_USER relationship.
 */
@NodeFragment(labels = ["GuideUser"])
data class GuideUser(
    @NodeId val uuid: UUID,
    val guideProgress: Int = 0
)