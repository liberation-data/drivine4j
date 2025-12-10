package sample;

import org.drivine.annotation.Direction;
import org.drivine.annotation.GraphRelationship;
import org.drivine.annotation.GraphView;
import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.Root;
import org.drivine.manager.GraphObjectManager;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.QuerySpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import sample.simple.TestAppContext;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java test demonstrating @GraphView with @GraphRelationship annotations.
 * Shows that GraphViews work fully with Java classes at runtime, including:
 * - Generic collections (List<T>, Set<T>) via Java reflection
 * - Nested GraphView relationships
 * - Polymorphic types and enums
 *
 * NOTE: The type-safe DSL generation (KSP) only works for Kotlin source files.
 * For the best experience in Java projects:
 * - Define @GraphView classes in Kotlin (to get DSL generation)
 * - Your @NodeFragment classes can be in Java or Kotlin
 * - Everything works at runtime regardless of language
 *
 * See README.md "Java Interoperability" section for details and recommended patterns.
 */
@SpringBootTest(classes = TestAppContext.class)
@Transactional
@Rollback(true)
public class JavaGraphViewWithRelationshipsTests {

    @Autowired
    private GraphObjectManager graphObjectManager;

    @Autowired
    private PersistenceManager persistenceManager;

    // ========================================
    // Fragment Classes (Domain Objects)
    // ========================================

    @NodeFragment(labels = {"Person", "Mapped"})
    public static class JavaPerson {
        @NodeId
        public UUID uuid;
        public String name;
        public String bio;

        public JavaPerson() {}

        public JavaPerson(UUID uuid, String name, String bio) {
            this.uuid = uuid;
            this.name = name;
            this.bio = bio;
        }

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBio() { return bio; }
        public void setBio(String bio) { this.bio = bio; }
    }

    @NodeFragment(labels = {"Organization"})
    public static class JavaOrganization {
        @NodeId
        public UUID uuid;
        public String name;

        public JavaOrganization() {}

        public JavaOrganization(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @NodeFragment(labels = {"Issue"})
    public static class JavaIssue {
        @NodeId
        public UUID uuid;
        public Long id;
        public String state;
        public JavaIssueStateReason stateReason;
        public String title;
        public String body;
        public boolean locked;

        public JavaIssue() {}

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public JavaIssueStateReason getStateReason() { return stateReason; }
        public void setStateReason(JavaIssueStateReason stateReason) { this.stateReason = stateReason; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public boolean isLocked() { return locked; }
        public void setLocked(boolean locked) { this.locked = locked; }
    }

    public enum JavaIssueStateReason {
        COMPLETED,
        NOT_PLANNED,
        REOPENED,
        UNKNOWN
    }

    // ========================================
    // GraphView Classes (with relationships)
    // ========================================

    @GraphView
    public static class JavaPersonContext {
        @Root
        public JavaPerson person;

        @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
        public List<JavaOrganization> worksFor;

        public JavaPersonContext() {}

        public JavaPerson getPerson() { return person; }
        public void setPerson(JavaPerson person) { this.person = person; }

        public List<JavaOrganization> getWorksFor() { return worksFor; }
        public void setWorksFor(List<JavaOrganization> worksFor) { this.worksFor = worksFor; }
    }

    @GraphView
    public static class JavaIssueWithAssignees {
        @Root
        public JavaIssue issue;

        @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
        public List<JavaPerson> assignedTo;

        @GraphRelationship(type = "RAISED_BY", direction = Direction.OUTGOING)
        public JavaPersonContext raisedBy;

        public JavaIssueWithAssignees() {}

        public JavaIssue getIssue() { return issue; }
        public void setIssue(JavaIssue issue) { this.issue = issue; }

        public List<JavaPerson> getAssignedTo() { return assignedTo; }
        public void setAssignedTo(List<JavaPerson> assignedTo) { this.assignedTo = assignedTo; }

        public JavaPersonContext getRaisedBy() { return raisedBy; }
        public void setRaisedBy(JavaPersonContext raisedBy) { this.raisedBy = raisedBy; }
    }

    // ========================================
    // Test Setup
    // ========================================

    @BeforeEach
    public void setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.createdBy = 'java-graphview-test' DETACH DELETE n"
            )
        );

        // Create test data: Issue -> RAISED_BY -> Person -> WORKS_FOR -> Organization
        //                   Issue -> ASSIGNED_TO -> Person

        UUID raiserUuid = UUID.randomUUID();
        UUID assignee1Uuid = UUID.randomUUID();
        UUID assignee2Uuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        UUID issueUuid = UUID.randomUUID();

        String query = """
            CREATE (raiser:Person:Mapped {
                uuid: $raiserUuid,
                name: 'Rod Johnson',
                bio: 'Creator of Spring Framework',
                createdBy: 'java-graphview-test'
            })
            CREATE (org:Organization {
                uuid: $orgUuid,
                name: 'Pivotal',
                createdBy: 'java-graphview-test'
            })
            CREATE (assignee1:Person:Mapped {
                uuid: $assignee1Uuid,
                name: 'Jasper Blues',
                bio: 'Drivine maintainer',
                createdBy: 'java-graphview-test'
            })
            CREATE (assignee2:Person:Mapped {
                uuid: $assignee2Uuid,
                name: 'Alice Smith',
                bio: 'Senior Developer',
                createdBy: 'java-graphview-test'
            })
            CREATE (issue:Issue {
                uuid: $issueUuid,
                id: 1001,
                title: 'Implement Java GraphView support',
                body: 'Add support for loading GraphViews from Neo4j in Java',
                state: 'open',
                stateReason: 'REOPENED',
                locked: false,
                createdBy: 'java-graphview-test'
            })
            CREATE (raiser)-[:WORKS_FOR]->(org)
            CREATE (issue)-[:RAISED_BY]->(raiser)
            CREATE (issue)-[:ASSIGNED_TO]->(assignee1)
            CREATE (issue)-[:ASSIGNED_TO]->(assignee2)
            """;

        persistenceManager.execute(
            QuerySpecification.withStatement(query)
                .bind(Map.of(
                    "raiserUuid", raiserUuid.toString(),
                    "assignee1Uuid", assignee1Uuid.toString(),
                    "assignee2Uuid", assignee2Uuid.toString(),
                    "orgUuid", orgUuid.toString(),
                    "issueUuid", issueUuid.toString()
                ))
        );
    }

    // ========================================
    // Tests
    // ========================================

    @Test
    public void testLoadAllIssuesWithNestedGraphView() {
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(JavaIssueWithAssignees.class);

        System.out.println("Loaded " + results.size() + " JavaIssueWithAssignees instances");
        results.forEach(issue -> {
            System.out.println("Issue: " + issue.getIssue().getTitle());
            System.out.println("  Raised by: " + issue.getRaisedBy().getPerson().getName());
            System.out.println("  Works for: " + issue.getRaisedBy().getWorksFor().stream()
                .map(JavaOrganization::getName)
                .collect(Collectors.joining(", ")));
            System.out.println("  Assigned to: " + issue.getAssignedTo().stream()
                .map(JavaPerson::getName)
                .collect(Collectors.joining(", ")));
        });

        assertFalse(results.isEmpty());
        JavaIssueWithAssignees issue = results.get(0);

        // Verify issue properties
        assertNotNull(issue.getIssue());
        assertEquals("Implement Java GraphView support", issue.getIssue().getTitle());
        assertEquals("Add support for loading GraphViews from Neo4j in Java", issue.getIssue().getBody());
        assertEquals("open", issue.getIssue().getState());
        assertEquals(JavaIssueStateReason.REOPENED, issue.getIssue().getStateReason());
        assertFalse(issue.getIssue().isLocked());

        // Verify raisedBy (nested GraphView)
        assertNotNull(issue.getRaisedBy());
        assertEquals("Rod Johnson", issue.getRaisedBy().getPerson().getName());
        assertEquals("Creator of Spring Framework", issue.getRaisedBy().getPerson().getBio());

        // Verify raisedBy worksFor relationship
        assertEquals(1, issue.getRaisedBy().getWorksFor().size());
        assertEquals("Pivotal", issue.getRaisedBy().getWorksFor().get(0).getName());

        // Verify assignedTo (collection)
        assertEquals(2, issue.getAssignedTo().size());
        assertTrue(issue.getAssignedTo().stream()
            .anyMatch(p -> "Jasper Blues".equals(p.getName())));
        assertTrue(issue.getAssignedTo().stream()
            .anyMatch(p -> "Alice Smith".equals(p.getName())));
    }

    @Test
    public void testLoadIssueById() {
        // First get all to find the UUID
        List<JavaIssueWithAssignees> all = graphObjectManager.loadAll(JavaIssueWithAssignees.class);
        assertFalse(all.isEmpty());
        JavaIssueWithAssignees expectedIssue = all.get(0);

        // Now load by ID
        JavaIssueWithAssignees loaded = graphObjectManager.load(
            expectedIssue.getIssue().getUuid().toString(),
            JavaIssueWithAssignees.class
        );

        assertNotNull(loaded);
        assertEquals(expectedIssue.getIssue().getUuid(), loaded.getIssue().getUuid());
        assertEquals(expectedIssue.getIssue().getTitle(), loaded.getIssue().getTitle());
        assertEquals(expectedIssue.getRaisedBy().getPerson().getName(),
                     loaded.getRaisedBy().getPerson().getName());
        assertEquals(expectedIssue.getAssignedTo().size(), loaded.getAssignedTo().size());

        System.out.println("Successfully loaded issue by ID: " + loaded.getIssue().getTitle());
    }

    @Test
    public void testReturnNullForNonExistentId() {
        String nonExistentId = UUID.randomUUID().toString();
        JavaIssueWithAssignees result = graphObjectManager.load(nonExistentId, JavaIssueWithAssignees.class);

        assertNull(result);
        System.out.println("Correctly returned null for non-existent ID");
    }

    @Test
    public void testLoadPersonContextGraphView() {
        List<JavaPersonContext> results = graphObjectManager.loadAll(JavaPersonContext.class);

        System.out.println("Loaded " + results.size() + " JavaPersonContext instances");
        results.forEach(ctx -> {
            System.out.println("Person: " + ctx.getPerson().getName());
            System.out.println("  Works for: " + ctx.getWorksFor().stream()
                .map(JavaOrganization::getName)
                .collect(Collectors.joining(", ")));
        });

        assertFalse(results.isEmpty());

        // Find Rod Johnson
        Optional<JavaPersonContext> rod = results.stream()
            .filter(ctx -> "Rod Johnson".equals(ctx.getPerson().getName()))
            .findFirst();

        assertTrue(rod.isPresent());
        assertEquals(1, rod.get().getWorksFor().size());
        assertEquals("Pivotal", rod.get().getWorksFor().get(0).getName());
    }

    @Test
    public void testNestedGraphViewWithMultipleRelationships() {
        List<JavaIssueWithAssignees> issues = graphObjectManager.loadAll(JavaIssueWithAssignees.class);

        assertFalse(issues.isEmpty());
        JavaIssueWithAssignees issue = issues.get(0);

        // Verify the entire graph structure is loaded correctly
        assertNotNull(issue.getIssue(), "Root issue should be loaded");
        assertNotNull(issue.getRaisedBy(), "RaisedBy GraphView should be loaded");
        assertNotNull(issue.getRaisedBy().getPerson(), "Nested person should be loaded");
        assertNotNull(issue.getRaisedBy().getWorksFor(), "Nested worksFor relationship should be loaded");
        assertNotNull(issue.getAssignedTo(), "AssignedTo list should be loaded");

        // Verify relationship cardinalities
        assertEquals(1, issue.getRaisedBy().getWorksFor().size(),
                     "Person should work for one organization");
        assertEquals(2, issue.getAssignedTo().size(),
                     "Issue should have two assignees");

        System.out.println("Successfully verified nested GraphView structure with multiple relationships");
    }

    @Test
    public void testEnumSerializationInGraphView() {
        List<JavaIssueWithAssignees> issues = graphObjectManager.loadAll(JavaIssueWithAssignees.class);

        assertFalse(issues.isEmpty());
        JavaIssueWithAssignees issue = issues.get(0);

        // Verify enum is properly deserialized
        assertNotNull(issue.getIssue().getStateReason());
        assertEquals(JavaIssueStateReason.REOPENED, issue.getIssue().getStateReason());

        System.out.println("Enum properly deserialized: " + issue.getIssue().getStateReason());
    }

    // ========================================
    // Simple String-based Filtering (No DSL Required)
    // ========================================

    @Test
    public void testSimpleWhereClauseFilteringOnRootProperty() {
        // Simple WHERE clause - no DSL required!
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(
            JavaIssueWithAssignees.class,
            "issue.state = 'open'"
        );

        System.out.println("Found " + results.size() + " open issues using simple WHERE clause");

        assertFalse(results.isEmpty());
        results.forEach(issue -> {
            assertEquals("open", issue.getIssue().getState());
            System.out.println("  - " + issue.getIssue().getTitle() + " (state: " + issue.getIssue().getState() + ")");
        });
    }

    @Test
    public void testSimpleWhereClauseFilteringOnDirectRelationship() {
        // Filtering on relationship collections requires EXISTS or ANY in Cypher.
        // The relationships are bound as arrays in pattern comprehensions.
        //
        // Example using EXISTS for relationship filtering:
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(
            JavaIssueWithAssignees.class,
            "EXISTS { (issue)-[:ASSIGNED_TO]->(p:Person) WHERE p.name = 'Jasper Blues' }"
        );

        System.out.println("Found " + results.size() + " issues assigned to Jasper Blues");

        results.forEach(issue -> {
            boolean hasJasper = issue.getAssignedTo().stream()
                .anyMatch(p -> "Jasper Blues".equals(p.getName()));
            assertTrue(hasJasper);
            System.out.println("  - " + issue.getIssue().getTitle());
        });
    }

    @Test
    public void testSimpleWhereClauseWithMultipleConditions() {
        // Multiple conditions with AND
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(
            JavaIssueWithAssignees.class,
            "issue.state = 'open' AND issue.locked = false"
        );

        System.out.println("Found " + results.size() + " open, unlocked issues");

        results.forEach(issue -> {
            assertEquals("open", issue.getIssue().getState());
            assertFalse(issue.getIssue().isLocked());
        });
    }

    @Test
    public void testSimpleWhereClauseWithContains() {
        // String contains operation
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(
            JavaIssueWithAssignees.class,
            "issue.title CONTAINS 'Java'"
        );

        System.out.println("Found " + results.size() + " issues with 'Java' in title");

        results.forEach(issue -> {
            assertTrue(issue.getIssue().getTitle().contains("Java"));
            System.out.println("  - " + issue.getIssue().getTitle());
        });
    }

    @Test
    public void testSimpleWhereClauseComplexityNoteForNestedGraphViews() {
        // NOTE: Filtering on nested GraphViews is complex with string-based WHERE clauses.
        //
        // For 'raisedBy' (which is a nested GraphView, not a direct relationship),
        // the generated Cypher creates nested WITH clauses and the variable names
        // don't map directly to Java property paths.
        //
        // For nested GraphView filtering, use Kotlin @GraphView definitions to get
        // the type-safe DSL which handles this complexity automatically:
        //
        // graphObjectManager.loadAll(RaisedAndAssignedIssue.class, spec -> {
        //     spec.where(ctx -> {
        //         ctx.getQuery().getRaisedBy().getPerson().getName().eq("Rod Johnson");
        //     });
        // });
        //
        // Simple string WHERE clauses work best for:
        // - Root fragment properties: "issue.state = 'open'"
        // - Direct relationship targets: "assignedTo_target.name = 'Alice'"
        //
        // They are NOT recommended for:
        // - Nested GraphView properties (requires DSL)
        // - Complex nested relationship hierarchies (requires DSL)

        System.out.println("NOTE: For complex nested filters, use Kotlin GraphViews with DSL instead of string WHERE clauses");
    }

    @Test
    public void testSimpleWhereClauseWithIsNull() {
        // Test IS NULL - create data without bio first
        UUID personUuid = UUID.randomUUID();
        persistenceManager.execute(
            QuerySpecification.withStatement("""
                CREATE (p:Person:Mapped {
                    uuid: $uuid,
                    name: 'John Doe',
                    createdBy: 'java-graphview-test'
                })
                """)
                .bind(Map.of("uuid", personUuid.toString()))
        );

        List<JavaPersonContext> results = graphObjectManager.loadAll(
            JavaPersonContext.class,
            "person.bio IS NULL"
        );

        System.out.println("Found " + results.size() + " people with no bio");

        boolean foundJohnDoe = results.stream()
            .anyMatch(p -> "John Doe".equals(p.getPerson().getName()));
        assertTrue(foundJohnDoe, "Should find John Doe with null bio");
    }

    @Test
    public void testSimpleWhereClauseWithIsNotNull() {
        // Filter for people with bio
        List<JavaPersonContext> results = graphObjectManager.loadAll(
            JavaPersonContext.class,
            "person.bio IS NOT NULL"
        );

        System.out.println("Found " + results.size() + " people with bio");

        results.forEach(person -> {
            assertNotNull(person.getPerson().getBio());
            System.out.println("  - " + person.getPerson().getName() + ": " + person.getPerson().getBio());
        });
    }

    @Test
    public void testSimpleWhereClauseWithComparison() {
        // Numeric comparison
        List<JavaIssueWithAssignees> results = graphObjectManager.loadAll(
            JavaIssueWithAssignees.class,
            "issue.id > 1000"
        );

        System.out.println("Found " + results.size() + " issues with id > 1000");

        results.forEach(issue -> {
            assertTrue(issue.getIssue().getId() > 1000);
            System.out.println("  - Issue #" + issue.getIssue().getId() + ": " + issue.getIssue().getTitle());
        });
    }
}
