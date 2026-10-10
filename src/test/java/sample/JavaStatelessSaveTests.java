package sample;

import org.drivine.StaleObjectException;
import org.drivine.manager.Add;
import org.drivine.manager.NullPolicy;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    public void replacesANamedRelationshipField() {
        Human ada = new Human(id + "-ada", "Ada");
        Human bob = new Human(id + "-bob", "Bob");
        ClaimView view = stateless.save(new ClaimView(new Claim(id, "Ada met Bob", null, null), List.of(ada, bob), List.of()));

        stateless.save(new ClaimView(view.getClaim(), List.of(ada), List.of()), Replace.of(Set.of("people")));

        assertEquals(List.of("Ada"), stateless.load(id, ClaimView.class).getPeople().stream().map(Human::getName).toList());
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
}
