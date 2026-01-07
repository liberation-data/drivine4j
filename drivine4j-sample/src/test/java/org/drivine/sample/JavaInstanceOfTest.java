package org.drivine.sample;

import org.drivine.manager.GraphObjectManager;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.QuerySpecification;
import org.drivine.query.dsl.JavaQueryBuilderKt;
import org.drivine.sample.fragment.AnonymousWebUser;
import org.drivine.sample.fragment.RegisteredWebUser;
import org.drivine.sample.view.GuideUserWithPolymorphicWebUser;
import org.drivine.sample.view.GuideUserWithPolymorphicWebUserQueryDsl;
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
 * Tests for the Java instanceOf() method for polymorphic type filtering.
 *
 * Demonstrates filtering by @NodeFragment subtype:
 * - instanceOf(AnonymousWebUser.class) filters to nodes with WebUser:Anonymous labels
 * - instanceOf(RegisteredWebUser.class) filters to nodes with WebUser:Registered labels
 */
@SpringBootTest(classes = SampleAppContext.class)
@Transactional
@Rollback(true)
public class JavaInstanceOfTest {

    @Autowired
    private GraphObjectManager graphObjectManager;

    @Autowired
    private PersistenceManager persistenceManager;

    private UUID guideWithAnonymous;
    private UUID guideWithRegistered;

    @BeforeEach
    void setupTestData() {
        // Clean up previous test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.testMarker = 'java-instanceof-test' DETACH DELETE n"
            )
        );

        guideWithAnonymous = UUID.randomUUID();
        guideWithRegistered = UUID.randomUUID();
        UUID anonymousUserId = UUID.randomUUID();
        UUID registeredUserId = UUID.randomUUID();

        // Create test data with different WebUser subtypes
        String cypher = String.format("""
            CREATE (g1:GuideUser {uuid: '%s', guideProgress: 10, testMarker: 'java-instanceof-test'})
            CREATE (w1:WebUser:Anonymous {uuid: '%s', displayName: 'Anon User', anonymousToken: 'token-abc', testMarker: 'java-instanceof-test'})
            CREATE (g1)-[:IS_WEB_USER]->(w1)

            CREATE (g2:GuideUser {uuid: '%s', guideProgress: 20, testMarker: 'java-instanceof-test'})
            CREATE (w2:WebUser:Registered {uuid: '%s', displayName: 'Registered User', email: 'test@example.com', testMarker: 'java-instanceof-test'})
            CREATE (g2)-[:IS_WEB_USER]->(w2)
            """,
            guideWithAnonymous, anonymousUserId, guideWithRegistered, registeredUserId
        );
        persistenceManager.execute(QuerySpecification.withStatement(cypher));
    }

    @Test
    void testInstanceOfAnonymousWebUser() {
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
            .loadAll();

        assertEquals(1, results.size(), "Should return 1 guide with anonymous web user");
        assertEquals(guideWithAnonymous, results.getFirst().getCore().getUuid());
        assertInstanceOf(AnonymousWebUser.class, results.getFirst().getWebUser());
    }

    @Test
    void testInstanceOfRegisteredWebUser() {
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getWebUser().instanceOf(RegisteredWebUser.class))
            .loadAll();

        assertEquals(1, results.size(), "Should return 1 guide with registered web user");
        assertEquals(guideWithRegistered, results.getFirst().getCore().getUuid());
        assertInstanceOf(RegisteredWebUser.class, results.getFirst().getWebUser());
    }

    @Test
    void testInstanceOfWithOtherConditions() {
        // Combine instanceOf with property filters
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getCore().getGuideProgress().gte(5))
            .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
            .loadAll();

        assertEquals(1, results.size());
        assertEquals(guideWithAnonymous, results.getFirst().getCore().getUuid());
        assertEquals(10, results.getFirst().getCore().getGuideProgress());
    }

    @Test
    void testInstanceOfWithNoMatches() {
        // Filter for anonymous users with high progress (none exist)
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getCore().getGuideProgress().gte(100))
            .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
            .loadAll();

        assertTrue(results.isEmpty(), "Should return empty when no matches");
    }

    @Test
    void testInstanceOfInWhereAny() {
        // OR condition: anonymous OR registered (should return both)
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .whereAny(dsl -> java.util.Arrays.asList(
                dsl.getWebUser().instanceOf(AnonymousWebUser.class),
                dsl.getWebUser().instanceOf(RegisteredWebUser.class)
            ))
            .loadAll();

        assertEquals(2, results.size(), "Should return both anonymous and registered guides");
    }

    @Test
    void testInstanceOfLoadFirst() {
        GuideUserWithPolymorphicWebUser result = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getWebUser().instanceOf(RegisteredWebUser.class))
            .loadFirst();

        assertNotNull(result);
        assertInstanceOf(RegisteredWebUser.class, result.getWebUser());
        assertEquals("test@example.com", ((RegisteredWebUser) result.getWebUser()).getEmail());
    }

    @Test
    void testInstanceOfWithPropertyAccessOnSubtype() {
        // First filter to get only anonymous users
        List<GuideUserWithPolymorphicWebUser> results = JavaQueryBuilderKt
            .query(graphObjectManager, GuideUserWithPolymorphicWebUser.class)
            .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
            .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
            .loadAll();

        // Then verify we can access subtype-specific properties
        assertEquals(1, results.size());
        AnonymousWebUser webUser = (AnonymousWebUser) results.getFirst().getWebUser();
        assertEquals("token-abc", webUser.getAnonymousToken());
        assertEquals("Anon User", webUser.getDisplayName());
    }
}