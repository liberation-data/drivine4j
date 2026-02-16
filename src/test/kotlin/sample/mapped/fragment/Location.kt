package sample.mapped.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.UUID

@NodeFragment(labels = ["Location"])
data class Location(
    @NodeId val uuid: UUID,
    val name: String,
    val type: String
)