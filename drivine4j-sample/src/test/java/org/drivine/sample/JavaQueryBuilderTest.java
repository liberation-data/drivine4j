package org.drivine.sample;

import org.drivine.manager.GraphObjectManager;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.QuerySpecification;
import org.drivine.query.dsl.JavaQueryBuilderKt;
import org.drivine.sample.view.RaisedAndAssignedIssue;
import org.drivine.sample.view.RaisedAndAssignedIssueQueryDsl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the Java query builder API.
 *
 * Tests all DSL operations:
 * - Equality: eq, neq
 * - Comparisons: gt, gte, lt, lte
 * - String operations: contains, startsWith, endsWith
 * - Null checks: isNull, isNotNull
 * - Collections: isIn
 * - Boolean logic: where (AND), whereAny (OR)
 * - Ordering: orderBy with asc/desc
 */
@SpringBootTest(classes = SampleAppContext.class)
@Transactional
@Rollback(true)
public class JavaQueryBuilderTest {

    @Autowired
    private GraphObjectManager graphObjectManager;

    @Autowired
    private PersistenceManager persistenceManager;

    @BeforeEach
    void setupTestData() {
        // Clean up previous test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.testMarker = 'java-builder-test' DETACH DELETE n"
            )
        );

        UUID issue1Uuid = UUID.randomUUID();
        UUID issue2Uuid = UUID.randomUUID();
        UUID issue3Uuid = UUID.randomUUID();
        UUID person1Uuid = UUID.randomUUID();
        UUID person2Uuid = UUID.randomUUID();

        // Create test data with varied values for comprehensive testing
        // Issue 1: id=100, state=open, title starts with "Bug", body is null
        // Issue 2: id=200, state=closed, title starts with "Feature", body has content
        // Issue 3: id=300, state=open, title starts with "Enhancement", body is null
        String cypher = String.format("""
            CREATE (i1:Issue {uuid: '%s', id: 100, title: 'Bug in login', state: 'open', locked: false, testMarker: 'java-builder-test'})
            CREATE (i2:Issue {uuid: '%s', id: 200, title: 'Feature request', state: 'closed', locked: true, body: 'Please add this feature', testMarker: 'java-builder-test'})
            CREATE (i3:Issue {uuid: '%s', id: 300, title: 'Enhancement needed', state: 'open', locked: false, testMarker: 'java-builder-test'})
            CREATE (p1:Person:Mapped {uuid: '%s', name: 'Alice Developer', bio: 'Senior Engineer', testMarker: 'java-builder-test'})
            CREATE (p2:Person:Mapped {uuid: '%s', name: 'Bob Designer', bio: 'UX Designer', testMarker: 'java-builder-test'})
            CREATE (i1)-[:RAISED_BY]->(p1)
            CREATE (i1)-[:ASSIGNED_TO]->(p2)
            CREATE (i2)-[:RAISED_BY]->(p2)
            CREATE (i2)-[:ASSIGNED_TO]->(p1)
            CREATE (i3)-[:RAISED_BY]->(p1)
            CREATE (i3)-[:ASSIGNED_TO]->(p1)
            """,
            issue1Uuid, issue2Uuid, issue3Uuid, person1Uuid, person2Uuid
        );
        persistenceManager.execute(QuerySpecification.withStatement(cypher));
    }

    // ==================== Basic Equality Tests ====================

    @Test
    void testEq() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("open"))
            .loadAll();

        assertEquals(2, results.size(), "Should return 2 open issues");
    }

    @Test
    void testNeq() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().neq("closed"))
            .loadAll();

        assertEquals(2, results.size(), "Should return 2 non-closed issues");
        results.forEach(r -> assertNotEquals("closed", r.getIssue().getState()));
    }

    // ==================== Numeric Comparison Tests ====================

    @Test
    void testGt() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().gt(100L))
            .loadAll();

        assertEquals(2, results.size(), "Should return issues with id > 100");
        results.forEach(r -> assertTrue(r.getIssue().getId() > 100));
    }

    @Test
    void testGte() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().gte(200L))
            .loadAll();

        assertEquals(2, results.size(), "Should return issues with id >= 200");
        results.forEach(r -> assertTrue(r.getIssue().getId() >= 200));
    }

    @Test
    void testLt() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().lt(300L))
            .loadAll();

        assertEquals(2, results.size(), "Should return issues with id < 300");
        results.forEach(r -> assertTrue(r.getIssue().getId() < 300));
    }

    @Test
    void testLte() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().lte(200L))
            .loadAll();

        assertEquals(2, results.size(), "Should return issues with id <= 200");
        results.forEach(r -> assertTrue(r.getIssue().getId() <= 200));
    }

    @Test
    void testNumericRange() {
        // Combine gt and lt for range query
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().gt(100L))
            .where(dsl -> dsl.getIssue().getId().lt(300L))
            .loadAll();

        assertEquals(1, results.size(), "Should return 1 issue with 100 < id < 300");
        assertEquals(200L, results.getFirst().getIssue().getId());
    }

    // ==================== String Operation Tests ====================

    @Test
    void testContains() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getTitle().contains("in"))
            .loadAll();

        assertEquals(1, results.size(), "Should return issue with 'in' in title");
        assertEquals("Bug in login", results.getFirst().getIssue().getTitle());
    }

    @Test
    void testStartsWith() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getTitle().startsWith("Bug"))
            .loadAll();

        assertEquals(1, results.size(), "Should return issue starting with 'Bug'");
        assertTrue(results.getFirst().getIssue().getTitle().startsWith("Bug"));
    }

    @Test
    void testEndsWith() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getTitle().endsWith("login"))
            .loadAll();

        assertEquals(1, results.size(), "Should return issue ending with 'login'");
        assertTrue(results.getFirst().getIssue().getTitle().endsWith("login"));
    }

    // ==================== Null Check Tests ====================

    @Test
    void testIsNull() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getBody().isNull())
            .loadAll();

        assertEquals(2, results.size(), "Should return 2 issues with null body");
        results.forEach(r -> assertNull(r.getIssue().getBody()));
    }

    @Test
    void testIsNotNull() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getBody().isNotNull())
            .loadAll();

        assertEquals(1, results.size(), "Should return 1 issue with non-null body");
        assertNotNull(results.getFirst().getIssue().getBody());
        assertEquals("Please add this feature", results.getFirst().getIssue().getBody());
    }

    // ==================== Collection (IN) Tests ====================

    @Test
    void testIsIn() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().isIn(java.util.Arrays.asList(100L, 300L)))
            .loadAll();

        assertEquals(2, results.size(), "Should return 2 issues with id IN [100, 300]");
        results.forEach(r -> assertTrue(r.getIssue().getId() == 100L || r.getIssue().getId() == 300L));
    }

    @Test
    void testIsInWithStrings() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().isIn(java.util.Arrays.asList("open", "reopened")))
            .loadAll();

        assertEquals(2, results.size(), "Should return 2 open issues");
    }

    // ==================== Boolean Logic Tests ====================

    @Test
    void testMultipleWhereConditions() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("open"))
            .where(dsl -> dsl.getIssue().getLocked().eq(false))
            .loadAll();

        assertEquals(2, results.size());
        results.forEach(r -> {
            assertEquals("open", r.getIssue().getState());
            assertFalse(r.getIssue().getLocked());
        });
    }

    @Test
    void testWhereAny() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .whereAny(dsl -> java.util.Arrays.asList(
                dsl.getIssue().getState().eq("closed"),
                dsl.getIssue().getId().gt(250L)
            ))
            .loadAll();

        assertEquals(2, results.size(), "Should return issues that are closed OR have id > 250");
    }

    @Test
    void testCombinedAndOr() {
        // locked=false AND (state='open' OR id > 250)
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getLocked().eq(false))
            .whereAny(dsl -> java.util.Arrays.asList(
                dsl.getIssue().getState().eq("open"),
                dsl.getIssue().getId().gt(250L)
            ))
            .loadAll();

        assertEquals(2, results.size());
        results.forEach(r -> assertFalse(r.getIssue().getLocked()));
    }

    // ==================== OrderBy Tests ====================

    @Test
    void testOrderByAsc() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .orderBy(dsl -> dsl.getIssue().getId().asc())
            .loadAll();

        assertEquals(3, results.size());
        assertEquals(100L, results.get(0).getIssue().getId());
        assertEquals(200L, results.get(1).getIssue().getId());
        assertEquals(300L, results.get(2).getIssue().getId());
    }

    @Test
    void testOrderByDesc() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .orderBy(dsl -> dsl.getIssue().getId().desc())
            .loadAll();

        assertEquals(3, results.size());
        assertEquals(300L, results.get(0).getIssue().getId());
        assertEquals(200L, results.get(1).getIssue().getId());
        assertEquals(100L, results.get(2).getIssue().getId());
    }

    @Test
    void testOrderByWithFilter() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("open"))
            .orderBy(dsl -> dsl.getIssue().getId().desc())
            .loadAll();

        assertEquals(2, results.size());
        assertEquals(300L, results.get(0).getIssue().getId());
        assertEquals(100L, results.get(1).getIssue().getId());
    }

    @Test
    void testOrderByString() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .orderBy(dsl -> dsl.getIssue().getTitle().asc())
            .loadAll();

        assertEquals(3, results.size());
        // Alphabetical order: Bug < Enhancement < Feature
        assertEquals("Bug in login", results.get(0).getIssue().getTitle());
        assertEquals("Enhancement needed", results.get(1).getIssue().getTitle());
        assertEquals("Feature request", results.get(2).getIssue().getTitle());
    }

    // ==================== LoadFirst Tests ====================

    @Test
    void testLoadFirst() {
        RaisedAndAssignedIssue result = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("closed"))
            .loadFirst();

        assertNotNull(result);
        assertEquals("closed", result.getIssue().getState());
    }

    @Test
    void testLoadFirstWithOrder() {
        RaisedAndAssignedIssue result = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .orderBy(dsl -> dsl.getIssue().getId().desc())
            .loadFirst();

        assertNotNull(result);
        assertEquals(300L, result.getIssue().getId(), "Should return highest ID first");
    }

    @Test
    void testLoadFirstNoMatches() {
        RaisedAndAssignedIssue result = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("nonexistent"))
            .loadFirst();

        assertNull(result, "Should return null when no matches");
    }

    // ==================== Edge Cases ====================

    @Test
    void testNoFilters() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .loadAll();

        assertEquals(3, results.size(), "Should return all 3 issues");
    }

    @Test
    void testNoMatches() {
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getId().gt(1000L))
            .loadAll();

        assertTrue(results.isEmpty(), "Should return empty list for no matches");
    }

    @Test
    void testComplexQuery() {
        // Real-world scenario: Find open issues with id > 50, title containing text, ordered by id
        List<RaisedAndAssignedIssue> results = JavaQueryBuilderKt
            .query(graphObjectManager, RaisedAndAssignedIssue.class)
            .filterWith(RaisedAndAssignedIssueQueryDsl.class)
            .where(dsl -> dsl.getIssue().getState().eq("open"))
            .where(dsl -> dsl.getIssue().getId().gte(100L))
            .where(dsl -> dsl.getIssue().getLocked().eq(false))
            .orderBy(dsl -> dsl.getIssue().getId().asc())
            .loadAll();

        assertEquals(2, results.size());
        assertEquals(100L, results.get(0).getIssue().getId());
        assertEquals(300L, results.get(1).getIssue().getId());
    }
}