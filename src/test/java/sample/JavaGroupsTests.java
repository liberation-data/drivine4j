package sample;

import org.drivine.annotation.Direction;
import org.drivine.manager.RelateMode;
import org.drivine.manager.NodeRef;
import org.drivine.manager.StatelessGraphObjectManager;
import org.drivine.query.dsl.Aggregate;
import org.drivine.query.dsl.ComparisonOperator;
import org.drivine.query.dsl.Key;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import sample.groups.Feedback;
import sample.groups.FeedbackQueryDsl;
import sample.groups.Message;
import sample.groups.MessageQueryDsl;
import sample.groups.Role;
import sample.groups.Session;
import sample.groups.User;
import sample.simple.TestAppContext;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A grouped read as a Java caller writes it: handles from the spec, and a function for the rows. */
@SpringBootTest(classes = TestAppContext.class)
@Transactional
@Rollback(true)
public class JavaGroupsTests {

    @Autowired
    private StatelessGraphObjectManager stateless;

    /** Marks the nodes of one test, which shares its database with the others. */
    private final String batch = "/java-" + UUID.randomUUID();

    private final FeedbackQueryDsl dsl = FeedbackQueryDsl.INSTANCE;

    private void vote(String id, String page, Boolean helpful, Long stars) {
        stateless.save(new Feedback(batch + id, batch + page, helpful, Instant.parse("2026-03-01T00:00:00Z"), stars, null, null, null));
    }

    @Test
    public void groupsByAPropertyWithAFilteredCountAHavingAndAnOrder() {
        vote("1", "/install", true, 5L);
        vote("2", "/install", true, 3L);
        vote("3", "/install", false, null);
        vote("4", "/agents", true, 4L);

        List<String> rows = stateless.getGroups().load(Feedback.class, dsl, spec -> {
            spec.onlyIf(dsl.getPage().hasPrefix(batch));
            Key<String> page = spec.key(dsl.getPage());
            Aggregate<Long> votes = spec.count();
            Aggregate<Long> likes = spec.countWhere(dsl.getHelpful().isEqualTo(true));
            Aggregate<Long> stars = spec.sumOfLongs(dsl.getStars());
            Aggregate<Long> liked = spec.filtered(spec.sumOfLongs(dsl.getStars()), dsl.getHelpful().isEqualTo(true), dsl.getStars().isAtLeast(4L));
            spec.having(votes, ComparisonOperator.GREATER_THAN_OR_EQUAL, 1);
            spec.orderBy(votes.descending(), page.ascending());
            spec.limit(10);
            return spec.rowsOf(row -> row.get(page).substring(batch.length()) + " " + row.get(votes) + " " + row.get(likes) + " " + row.get(stars) + " " + row.get(liked));
        });

        assertEquals(List.of("/install 3 2 8 5", "/agents 1 1 4 4"), rows);
    }

    @Test
    public void groupsByANodeSomeHopsAway() {
        MessageQueryDsl messages = MessageQueryDsl.Companion.getINSTANCE();
        stateless.save(new User(batch + "alice", "al"));
        stateless.save(new Session(batch + "s1"));
        NodeRef session = new NodeRef(Session.class, batch + "s1", Set.of());
        stateless.getEdges().relate(session, new NodeRef(User.class, batch + "alice", Set.of()), "OWNED_BY", Map.of(), RelateMode.CREATE);
        for (String id : List.of("m1", "m2")) {
            stateless.save(new Message(batch + id, Role.USER, Instant.parse("2026-03-01T00:00:00Z"), null, null));
            stateless.getEdges().relate(session, new NodeRef(Message.class, batch + id, Set.of()), "HAS_MESSAGE", Map.of(), RelateMode.CREATE);
        }

        List<String> rows = stateless.getGroups().load(Message.class, messages, spec -> {
            spec.onlyIf(messages.getId().hasPrefix(batch));
            Key<User> user = spec.key(spec.path(User.class, spec.hop("HAS_MESSAGE", Direction.INCOMING, "GroupSession"), spec.hop("OWNED_BY")));
            Aggregate<Long> written = spec.count();
            return spec.rowsOf(row -> row.get(user).getUsername() + " " + row.get(written));
        });

        assertEquals(List.of("al 2"), rows);
    }

    @Test
    public void countsByAFieldNamedAsAString() {
        vote("1", "/install", true, null);
        vote("2", "/install", false, null);

        Map<Object, Long> counted = stateless.getGroups().countBy(Feedback.class, "page");

        assertEquals(2L, counted.get(batch + "/install"));
    }

    @Test
    public void readsAKeyThatMayHaveNoValueWithAMeanALeastAndAGreatestValue() {
        Instant march = Instant.parse("2026-03-01T00:00:00Z");
        stateless.save(new Feedback(batch + "1", batch, true, march, 5L, null, "intro", null));
        stateless.save(new Feedback(batch + "2", batch, true, march, 2L, null, "intro", null));
        stateless.save(new Feedback(batch + "3", batch, false, march, 4L, null, "usage", null));
        stateless.save(new Feedback(batch + "4", batch, false, march, null, null, null, null));

        List<List<Object>> rows = stateless.getGroups().load(Feedback.class, dsl, spec -> {
            spec.onlyIf(dsl.getPage().isEqualTo(batch));
            Key<String> section = spec.keyOrNull(dsl.getSection());
            Aggregate<Double> mean = spec.avg(dsl.getStars());
            Aggregate<Long> least = spec.min(dsl.getStars());
            Aggregate<Long> most = spec.max(dsl.getStars());
            spec.orderBy(section.ascending().nullsFirst());
            return spec.rowsOf(row -> Arrays.<Object>asList(row.get(section), row.get(mean), row.get(least), row.get(most)));
        });

        assertEquals(List.of(Arrays.asList(null, null, null, null), List.of("intro", 3.5, 2L, 5L), List.of("usage", 4.0, 4L, 4L)), rows);
    }

    @Test
    public void pagesTheGroupsWithASkipAndPlacesTheNullsOfADescendingOrderLast() {
        Instant march = Instant.parse("2026-03-01T00:00:00Z");
        stateless.save(new Feedback(batch + "1", batch, true, march, null, null, "a", null));
        stateless.save(new Feedback(batch + "2", batch, true, march, null, null, "b", null));
        stateless.save(new Feedback(batch + "3", batch, true, march, null, null, null, null));

        List<String> rows = stateless.getGroups().load(Feedback.class, dsl, spec -> {
            spec.onlyIf(dsl.getPage().isEqualTo(batch));
            Key<String> section = spec.keyOrNull(dsl.getSection());
            spec.orderBy(section.descending().nullsLast());
            spec.skip(1);
            spec.limit(5);
            return spec.rowsOf(row -> String.valueOf(row.get(section)));
        });

        assertEquals(List.of("a", "null"), rows);
    }

    @Test
    public void refusesAHavingThatIsNoComparison() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
            stateless.getGroups().load(Feedback.class, dsl, spec -> {
                Key<String> page = spec.key(dsl.getPage());
                spec.having(spec.count(), ComparisonOperator.CONTAINS, "3");
                return spec.rowsOf(row -> row.get(page));
            }));

        assertTrue(refused.getMessage().contains("CONTAINS is none of them"), refused.getMessage());
    }
}
