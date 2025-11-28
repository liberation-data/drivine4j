package sample.mapped.view

import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test
import sample.mapped.fragment.Issue
import sample.mapped.fragment.IssueStateReason
import sample.mapped.fragment.Person
import java.util.UUID

class RaisedAndAssignedIssueSerializationTests {

    @Test
    fun `issue with raised by should serialize to json`() {
        val mapper = Neo4jObjectMapper.instance;

        val personContext = PersonContext(
            person = Person(
                uuid = UUID.randomUUID(),
                name = "Rod",
                "The one and only"
            ),
            worksFor = emptyList()
        )

        val assignee = Person(
            uuid = UUID.randomUUID(),
            name = "Jasper",
            bio = "The one and only",
        )

        val issue = Issue(
            uuid = UUID.randomUUID(),
            id = 23L,
            state = "foobar",
            stateReason = IssueStateReason.REOPENED,
            title = "Widgets",
            body = "Implement a widget for the wigwom",
            locked = false
        )

        val raisedAndAssignedIssue = RaisedAndAssignedIssue(
            issue = issue,
            assignedTo = listOf(assignee),
            raisedBy = personContext
        )

        val json = mapper.writeValueAsString(raisedAndAssignedIssue);
        println(json)


    }

    @Test
    fun `should deserialize from json`() {
        val mapper = Neo4jObjectMapper.instance
        val json = """
            {
              "issue" : {
                "locked" : false,
                "id" : 23,
                "title" : "Widgets",
                "uuid" : "9abed314-18dd-4dde-a2f3-7d2592b7c4f0",
                "body" : "Implement a widget for the wigwom",
                "state" : "foobar",
                "stateReason" : "REOPENED"
              },
              "assignedTo" : [
                {
                  "name" : "Jasper",
                  "bio" : "The one and only",
                  "uuid" : "085f31b9-3910-44ba-9751-38dadc974e90"
                }
              ],
              "raisedBy" : {
                "worksFor" : [

                ],
                "person" : {
                  "name" : "Rod",
                  "bio" : "The one and only",
                  "uuid" : "84e2ef04-4c8c-4d81-a6d8-2638030c75e1"
                }
              }
            }
        """.trimIndent()
        val result = mapper.readValue<RaisedAndAssignedIssue>(json);
        println(result)
    }

}
