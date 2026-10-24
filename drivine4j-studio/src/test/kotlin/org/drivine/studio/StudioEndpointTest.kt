package org.drivine.studio

import org.drivine.manager.PersistenceManager
import org.drivine.test.TestCleanup
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.annotation.Rollback
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/** The endpoints over the sample domain, against a real graph. */
@SpringBootTest(classes = [StudioTestContext::class])
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class StudioEndpointTest @Autowired constructor(
    private val mvc: MockMvc,
    private val persistenceManager: PersistenceManager,
) {
    private val view = "org.drivine.sample.view.RaisedAndAssignedIssue"

    @BeforeEach
    fun seed() {
        TestCleanup.beforeEach(persistenceManager, "studio-test")
        SampleGraph.seed(persistenceManager, "studio-test")
    }

    private fun query(body: String) = mvc.post("/drivine/studio/api/query") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `the catalogue lists the application's views and fragments`() {
        mvc.get("/drivine/studio/api/catalog").andExpect {
            status { isOk() }
            jsonPath("$.views[*].name", hasItem("RaisedAndAssignedIssue"))
            jsonPath("$.fragments[*].name", hasItem("Person"))
        }
    }

    @Test
    fun `a view is loaded as the graph it was read from`() {
        query(
            """
            {"type": "$view",
             "where": [{"kind": "property", "property": "title", "op": "STARTS_WITH", "value": "Studio:"},
                       {"kind": "property", "property": "state", "op": "EQUALS", "value": "open"}],
             "orderBy": [{"property": "id", "direction": "DESC"}]}
            """
        ).andExpect {
            status { isOk() }
            jsonPath("$.count") { value(2) }
            jsonPath("$.truncated") { value(false) }
            // 102 before 101, and each root is a node
            jsonPath("$.graph.roots", hasSize<Any>(2))
            jsonPath("$.graph.nodes[?(@.fragment == 'IssueCore')].properties.id", containsInAnyOrder(102, 101))
            // Alice and Bob are assigned, Bob and Carol raised: three people, each once
            jsonPath("$.graph.nodes[?(@.fragment == 'Person')].properties.name",
                containsInAnyOrder("Alice Developer", "Bob Reviewer", "Carol Reporter"))
            jsonPath("$.graph.nodes[?(@.fragment == 'Organization')].properties.name", containsInAnyOrder("Acme Corp", "Globex"))
            jsonPath("$.graph.edges[?(@.type == 'ASSIGNED_TO')]", hasSize<Any>(3))
            jsonPath("$.graph.edges[?(@.type == 'RAISED_BY')]", hasSize<Any>(2))
        }
    }

    @Test
    fun `roots come back in the order asked for`() {
        val body = query(
            """{"type": "$view", "where": [{"kind": "property", "property": "title", "op": "STARTS_WITH", "value": "Studio:"}],
                "orderBy": [{"property": "id", "direction": "DESC"}], "limit": 2}"""
        ).andExpect {
            status { isOk() }
            jsonPath("$.count") { value(2) }
            jsonPath("$.truncated") { value(true) }
        }.andReturn().response.contentAsString

        val ids = com.jayway.jsonpath.JsonPath.read<List<Int>>(
            body, "$.graph.roots"
        ).let { roots ->
            (roots as List<*>).map { root ->
                com.jayway.jsonpath.JsonPath.read<List<Int>>(body, "$.graph.nodes[?(@.id == '$root')].properties.id").single()
            }
        }
        kotlin.test.assertEquals(listOf(103, 102), ids)
    }

    @Test
    fun `a condition on a relationship filters the roots`() {
        query(
            """
            {"type": "$view",
             "where": [{"kind": "property", "property": "title", "op": "STARTS_WITH", "value": "Studio:"},
                       {"kind": "related", "relationship": "assignedTo",
                        "conditions": [{"kind": "property", "property": "name", "op": "EQUALS", "value": "Alice Developer"}]}]}
            """
        ).andExpect {
            status { isOk() }
            jsonPath("$.graph.nodes[?(@.fragment == 'IssueCore')].properties.id", containsInAnyOrder(101, 102))
        }
    }

    @Test
    fun `a fragment is loaded as its nodes alone`() {
        query(
            """{"type": "org.drivine.sample.fragment.Organization",
                "where": [{"kind": "property", "property": "name", "op": "EQUALS", "value": "Globex"}]}"""
        ).andExpect {
            status { isOk() }
            jsonPath("$.graph.nodes[*].properties.name", hasItem("Globex"))
            jsonPath("$.graph.edges", hasSize<Any>(0))
        }
    }

    @Test
    fun `a query the catalogue does not allow is refused with the reason`() {
        query("""{"type": "$view", "where": [{"kind": "property", "property": "status", "op": "EQUALS", "value": "open"}]}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error", containsString("no property 'status'"))
            }
    }
}
