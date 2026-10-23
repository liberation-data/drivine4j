package sample;

import org.drivine.StaleObjectException;
import org.drivine.manager.Add;
import org.drivine.manager.NullPolicy;
import org.drivine.manager.RemovedTargets;
import org.drivine.manager.Replace;
import org.drivine.manager.StatelessGraphObjectManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import sample.simple.TestAppContext;
import sample.stateless.Claim;
import sample.stateless.ClaimView;
import sample.stateless.Human;
import sample.stateless.JavaNote;
import sample.stateless.JavaRecordNote;
import sample.stateless.Memo;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The stateless manager's saves as a Java caller writes them. */
@SpringBootTest(classes = TestAppContext.class)
@Transactional
@Rollback(true)
public class JavaStatelessSaveTests {

    @Autowired
    private StatelessGraphObjectManager stateless;

    private final String id = "java-" + UUID.randomUUID();

    @Test
    public void savesAndReturnsTheStamp() {
        Claim saved = stateless.save(new Claim(id, "Ada founded Acme", null, null));

        assertNotNull(saved.getStamp());
        assertEquals(saved.getStamp(), stateless.load(id, Claim.class).getStamp());
    }

    @Test
    public void refusesAStaleObject() {
        Claim first = stateless.save(new Claim(id, "Ada founded Acme", null, null));
        stateless.save(new Claim(id, "changed", null, first.getStamp()));

        assertThrows(StaleObjectException.class, () -> stateless.save(new Claim(id, "late", null, first.getStamp())));
    }

    @Test
    public void savesFromTheObjectAsLoaded() {
        stateless.save(new Claim(id, "Ada founded Acme", null, null));
        Claim loaded = stateless.load(id, Claim.class);

        // Built anew from what was loaded, without its stamp: checked as what it was loaded from.
        Claim saved = stateless.save(new Claim(id, "Ada founded Acme in 1999", null, null), loaded);

        assertEquals("Ada founded Acme in 1999", stateless.load(id, Claim.class).getText());
        assertNotEquals(loaded.getStamp(), saved.getStamp());
        assertThrows(StaleObjectException.class, () -> stateless.save(new Claim(id, "late", null, null), loaded));
        // The object saved itself no longer says what was loaded, once it is changed in place.
        assertThrows(IllegalArgumentException.class, () -> stateless.save(saved, saved));
    }

    @Test
    public void replacesANamedRelationshipField() {
        Human ada = new Human(id + "-ada", "Ada");
        Human bob = new Human(id + "-bob", "Bob");
        ClaimView view = stateless.save(new ClaimView(new Claim(id, "Ada met Bob", null, null), List.of(ada, bob), List.of()));

        stateless.save(new ClaimView(view.getClaim(), List.of(ada), List.of()), Replace.of(Set.of("people")));

        assertEquals(List.of("Ada"), stateless.load(id, ClaimView.class).getPeople().stream().map(Human::getName).toList());
    }

    @Test
    public void savesManyWritingOnlyTheNamedFields() {
        stateless.saveAll(List.of(new Claim(id, "Ada founded Acme", "checked", null), new Claim(id + "-2", "Bob joined", "checked", null)));

        List<Claim> saved = stateless.saveAllFields(
            List.of(new Claim(id, "ignored", "revised", null), new Claim(id + "-2", "ignored", "revised too", null)),
            Add.INSTANCE, NullPolicy.IGNORE, Set.of("note"));
        stateless.saveAllFields(saved, Add.INSTANCE, NullPolicy.IGNORE, Set.of(), Set.of("text", "note"));

        Claim first = stateless.load(id, Claim.class);
        Claim second = stateless.load(id + "-2", Claim.class);
        assertEquals(List.of("Ada founded Acme", "revised"), List.of(first.getText(), first.getNote()));
        assertEquals(List.of("Bob joined", "revised too"), List.of(second.getText(), second.getNote()));
        assertNotNull(saved.get(0).getStamp());
        assertThrows(IllegalArgumentException.class, () -> stateless.saveAllFields(saved, Add.INSTANCE, NullPolicy.IGNORE, Set.of("id")));
    }

    @Test
    public void aNameIsWrittenOfEachClassThatHasIt() {
        stateless.saveAll(List.of(new Claim(id, "Ada founded Acme", "checked", null), new Memo(id + "-memo", "a memo")));

        stateless.saveAllFields(
            List.<Object>of(new Claim(id, "Ada founded Acme in 1999", "ignored", null), new Memo(id + "-memo", "a longer memo")),
            Add.INSTANCE, NullPolicy.IGNORE, Set.of("text"));

        Claim claim = stateless.load(id, Claim.class);
        assertEquals(List.of("Ada founded Acme in 1999", "checked"), List.of(claim.getText(), claim.getNote()));
        assertEquals("a longer memo", stateless.load(id + "-memo", Memo.class).getText());
        // A name one of the classes lacks is refused, and nothing is written.
        assertThrows(IllegalArgumentException.class, () -> stateless.saveAllFields(
            List.<Object>of(new Claim(id, "late", "late", null), new Memo(id + "-memo", "late")), Add.INSTANCE, NullPolicy.IGNORE, Set.of("note")));
        assertEquals("checked", stateless.load(id, Claim.class).getNote());
    }

    @Test
    public void writesOnlyTheNamedFields() {
        Claim saved = stateless.save(new Claim(id, "Ada founded Acme", "checked", null));

        stateless.saveFields(new Claim(id, "Ada founded Acme in 1999", "ignored", saved.getStamp()), Add.INSTANCE, NullPolicy.IGNORE, Set.of("text"));

        Claim loaded = stateless.load(id, Claim.class);
        assertEquals("Ada founded Acme in 1999", loaded.getText());
        assertEquals("checked", loaded.getNote());
    }

    @Test
    public void updatesWithAFunction() {
        stateless.save(new Claim(id, "Ada founded Acme", null, null));

        Claim updated = stateless.update(id, Claim.class, claim -> new Claim(claim.getId(), "updated", claim.getNote(), claim.getStamp()));

        assertEquals("updated", updated.getText());
    }

    @Test
    public void replacesEveryFieldOfALoadedView() {
        Human ada = new Human(id + "-ada", "Ada");
        Human bob = new Human(id + "-bob", "Bob");
        stateless.save(new ClaimView(new Claim(id, "Ada met Bob", null, null), List.of(ada, bob), List.of()));
        ClaimView loaded = stateless.load(id, ClaimView.class);

        stateless.save(new ClaimView(loaded.getClaim(), List.of(bob), List.of()), Replace.all());

        assertEquals(List.of("Bob"), stateless.load(id, ClaimView.class).getPeople().stream().map(Human::getName).toList());
        assertNotNull(stateless.load(ada.getId(), Human.class), "a node that lost its relationship is kept");
    }

    @Test
    public void replacesANamedFieldAndDeletesWhatNothingElseRefersTo() {
        Human ada = new Human(id + "-ada", "Ada");
        Human bob = new Human(id + "-bob", "Bob");
        ClaimView view = stateless.save(new ClaimView(new Claim(id, "Ada met Bob", null, null), List.of(ada, bob), List.of()));

        stateless.save(
            new ClaimView(view.getClaim(), List.of(ada), List.of()),
            Replace.of(Set.of("people"), RemovedTargets.DELETE_UNREFERENCED));

        assertEquals(List.of("Ada"), stateless.load(id, ClaimView.class).getPeople().stream().map(Human::getName).toList());
        assertNull(stateless.load(bob.getId(), Human.class));
        assertThrows(IllegalArgumentException.class, () -> Replace.of(Set.of()));
    }

    @Test
    public void leavesTheFieldsNamedAsExceptUnwritten() {
        Claim saved = stateless.save(new Claim(id, "Ada founded Acme", "checked", null));

        stateless.saveFields(
            new Claim(id, "Ada founded Acme in 1999", "ignored", saved.getStamp()), Add.INSTANCE, NullPolicy.IGNORE, Set.of(), Set.of("note"));

        Claim loaded = stateless.load(id, Claim.class);
        assertEquals("Ada founded Acme in 1999", loaded.getText());
        assertEquals("checked", loaded.getNote());
    }

    @Test
    public void updatesWithAGivenNumberOfAttempts() {
        Claim saved = stateless.save(new Claim(id, "Ada founded Acme", null, null));

        Claim updated = stateless.update(id, Claim.class, 5, claim -> new Claim(claim.getId(), "updated", "noted", claim.getStamp()));

        assertEquals("updated", updated.getText());
        assertNotEquals(saved.getStamp(), updated.getStamp());
        assertEquals(updated.getStamp(), stateless.load(id, Claim.class).getStamp());
        assertNull(stateless.update(id + "-nobody", Claim.class, 5, claim -> claim));
        assertThrows(IllegalArgumentException.class, () -> stateless.update(id, Claim.class, 0, claim -> claim));
    }

    @Test
    public void createsANodeOnlyIfItIsNotThere() {
        Claim made = stateless.create(new Claim(id, "Ada founded Acme", null, null));

        assertNotNull(made.getStamp());
        assertEquals(made.getStamp(), stateless.load(id, Claim.class).getStamp());
        assertNull(stateless.create(new Claim(id, "late", "lost", null)));
        assertEquals(made, stateless.load(id, Claim.class));
        assertThrows(IllegalArgumentException.class, () -> stateless.create(made));
    }

    @Test
    public void createsOrElseUpdates() {
        stateless.create(new Claim(id, "Ada founded Acme", null, null));
        Claim incoming = new Claim(id, "ignored", "noted", null);

        Claim stored = stateless.create(incoming);
        if (stored == null) {
            stored = stateless.update(id, Claim.class, claim -> new Claim(claim.getId(), claim.getText(), incoming.getNote(), claim.getStamp()));
        }

        assertEquals(List.of("Ada founded Acme", "noted"), List.of(stored.getText(), stored.getNote()));
    }

    @Test
    public void savesAllAndHandsEachItsStamp() {
        List<Claim> saved = stateless.saveAll(List.of(new Claim(id + "-1", "one", null, null), new Claim(id + "-2", "two", null, null)));

        assertEquals(List.of(id + "-1", id + "-2"), saved.stream().map(Claim::getId).toList());
        for (Claim claim : saved) {
            assertEquals(stateless.load(claim.getId(), Claim.class).getStamp(), claim.getStamp());
        }
        stateless.save(new Claim(saved.get(0).getId(), "uno", null, saved.get(0).getStamp()));
    }

    @Test
    public void savesAllViewsAndReplacesTheirLists() {
        Human ada = new Human(id + "-ada", "Ada");
        Human bob = new Human(id + "-bob", "Bob");
        List<ClaimView> saved = stateless.saveAll(List.of(
            new ClaimView(new Claim(id + "-1", "one", null, null), List.of(ada, bob), List.of()),
            new ClaimView(new Claim(id + "-2", "two", null, null), List.of(ada), List.of())));

        stateless.saveAll(
            List.of(new ClaimView(saved.get(0).getClaim(), List.of(bob), List.of()), saved.get(1)),
            Replace.of(Set.of("people")),
            NullPolicy.IGNORE);

        assertEquals(List.of("Bob"), stateless.load(id + "-1", ClaimView.class).getPeople().stream().map(Human::getName).toList());
        assertEquals(List.of("Ada"), stateless.load(id + "-2", ClaimView.class).getPeople().stream().map(Human::getName).toList());
    }

    @Test
    public void givesAJavaClassWithSettersItsStampInPlace() {
        JavaNote note = new JavaNote(id, "one");

        JavaNote saved = stateless.save(note);

        assertSame(note, saved);
        assertNotNull(note.getStamp());
        assertEquals(note.getStamp(), stateless.load(id, JavaNote.class).getStamp());

        JavaNote stale = stateless.load(id, JavaNote.class);
        note.setText("two");
        stateless.save(note);
        stale.setText("late");
        assertThrows(StaleObjectException.class, () -> stateless.save(stale));
        assertEquals("two", stateless.load(id, JavaNote.class).getText());
    }

    @Test
    public void returnsAJavaRecordAsACopyWithItsStamp() {
        JavaRecordNote note = new JavaRecordNote(id, "one", null);

        JavaRecordNote saved = stateless.save(note);

        assertNotSame(note, saved);
        assertNull(note.stamp());
        assertNotNull(saved.stamp());
        JavaRecordNote loaded = stateless.load(id, JavaRecordNote.class);
        assertEquals(saved.stamp(), loaded.stamp());
        assertEquals("one", loaded.text());

        stateless.save(new JavaRecordNote(id, "two", saved.stamp()));
        assertThrows(StaleObjectException.class, () -> stateless.save(new JavaRecordNote(id, "late", saved.stamp())));
        assertEquals("two", stateless.load(id, JavaRecordNote.class).text());
    }
}
