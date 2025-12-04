package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.UUID

@NodeFragment(labels = ["Organization"])
data class Organization(
    @NodeId val uuid: UUID,
    val name: String,
)
