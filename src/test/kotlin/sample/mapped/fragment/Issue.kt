package sample.mapped.fragment

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphNodeId
import java.util.*

@GraphFragment(labels = ["Issue"])
data class Issue(
    @GraphNodeId val uuid: UUID,
    val id: Long,
    val state: String?,
    val stateReason: IssueStateReason?,
    val title: String?,
    val body: String?,
    val locked: Boolean,
)

