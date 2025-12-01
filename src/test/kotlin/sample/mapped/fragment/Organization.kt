package sample.mapped.fragment

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphNodeId
import java.util.UUID

@GraphFragment(labels = ["Organization"])
data class Organization(
    @GraphNodeId val uuid: UUID,
    val name: String,
)
