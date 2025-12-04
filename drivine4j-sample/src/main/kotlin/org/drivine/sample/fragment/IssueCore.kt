package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.*

interface Issue {
    val uuid: UUID
    val id: Long
    val state: String?
    val stateReason: IssueStateReason?
    val title: String?
    val body: String?
    val locked: Boolean
}

@NodeFragment(labels = ["Issue"])
data class IssueCore(
    @NodeId override val uuid: UUID,
    override val id: Long,
    override val state: String?,
    override val stateReason: IssueStateReason?,
    override val title: String?,
    override val body: String?,
    override val locked: Boolean,
) : Issue

