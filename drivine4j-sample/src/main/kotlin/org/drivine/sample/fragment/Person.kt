package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.UUID


@NodeFragment(labels = ["Person", "Mapped"])
data class Person(
    @NodeId val uuid: UUID,
    val name: String,
    val bio: String?)


@NodeFragment(labels = ["Person", "Mapped", "GithubPerson"])
data class GithubPerson(
    @NodeId val uuid: UUID,
    val name: String,
    val bio: String?,
    val githubId: String
)


