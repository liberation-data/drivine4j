package sample.vector

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

@NodeFragment(labels = ["Author"])
data class AuthorNode(
    @NodeId val id: String,
    val name: String,
)