package sample.mapped.fragment

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphNodeId
import java.util.UUID


@GraphFragment(labels = ["Person", "Mapped"])
data class Person(
    @GraphNodeId val uuid: UUID,
    val name: String,
    val bio: String?)


@GraphFragment(labels = ["Person", "Mapped", "GithubPerson"])
data class GithubPerson(
    @GraphNodeId val uuid: UUID,
    val name: String,
    val bio: String?,
    val githubId: String
)


