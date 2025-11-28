package sample.mapped.fragment

import org.drivine.annotation.GraphNodeId
import java.util.UUID

data class Organization(
    @GraphNodeId val uuid: UUID,
    val name: String,
)
