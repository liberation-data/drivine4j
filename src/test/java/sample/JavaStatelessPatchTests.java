package sample;

import org.drivine.manager.PatchResult;
import org.drivine.manager.StatelessGraphObjectManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import sample.patch.ClaimQueryDsl;
import sample.simple.TestAppContext;
import sample.stateless.Claim;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A patch as a Java caller writes it: a lambda that returns nothing, with no type given for its parameter. */
@SpringBootTest(classes = TestAppContext.class)
@Transactional
@Rollback(true)
public class JavaStatelessPatchTests {

    @Autowired
    private StatelessGraphObjectManager stateless;

    private final String id = "java-" + UUID.randomUUID();

    private final ClaimQueryDsl dsl = ClaimQueryDsl.Companion.getINSTANCE();

    @Test
    public void writesAFieldWhereThePredicateHolds() {
        stateless.save(new Claim(id, "one", "first", null));

        PatchResult unmet = stateless.patch(id, Claim.class, dsl, spec -> spec.set(dsl.getNote().assign("patched")).onlyIf(dsl.getText().isEqualTo("two")));
        PatchResult changed = stateless.patch(id, Claim.class, dsl, spec -> spec.set(dsl.getNote().assign("patched")).onlyIf(dsl.getText().isEqualTo("one")));

        assertEquals(new PatchResult(true, false, false), unmet);
        assertEquals(new PatchResult(true, true, true), changed);
        assertEquals("patched", stateless.load(id, Claim.class).getNote());
    }

    @Test
    public void clearsAFieldWhereAnyConditionHolds() {
        stateless.save(new Claim(id, "one", "first", null));

        PatchResult cleared = stateless.patch(id, Claim.class, dsl, spec -> {
            spec.set(dsl.getNote().cleared());
            spec.onlyIfAny(dsl.getText().isEqualTo("two"), dsl.getNote().isPresent());
        });

        assertEquals(new PatchResult(true, true, true), cleared);
        assertNull(stateless.load(id, Claim.class).getNote());
    }

    @Test
    public void aNullIsNoValueToAssign() {
        assertThrows(NullPointerException.class, () -> dsl.getNote().assign(null));
    }
}
