package sample.mapped.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.*

@NodeFragment(labels = ["Issue"])
data class Issue(
    @NodeId val uuid: UUID,
    val id: Long,
    val state: String?,
    val stateReason: IssueStateReason?,
    val title: String?,
    val body: String?,
    val locked: Boolean,
)

