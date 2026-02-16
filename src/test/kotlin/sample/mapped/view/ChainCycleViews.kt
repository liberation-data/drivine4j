package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import sample.mapped.fragment.Person
import sample.mapped.fragment.Organization

/**
 * Chain cycle test models: PersonProjectView → OrgPersonView → PersonProjectView
 *
 * Models a cycle where a person works for an organization, and
 * the organization has employees (persons) who in turn work for organizations.
 */
@GraphView
data class PersonOrgView(
    val person: Person,
    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val employer: OrgPersonView?
)

@GraphView
data class OrgPersonView(
    val org: Organization,
    @GraphRelationship(type = "EMPLOYS", direction = Direction.OUTGOING, maxDepth = 2)
    val employees: List<PersonOrgView>
)