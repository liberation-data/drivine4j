package org.drivine.manager;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every arity of {@code loadNearest} and {@code loadMatching} a Java caller had while they carried
 * {@code @JvmOverloads} is still there, and {@code loadNearestAmong} has the same, on the interface
 * both managers implement, as do its forms that name the neighbours kept, and each hands the full form
 * the arguments it was given and the defaults for the rest.
 */
class JavaSearchOverloadsTest {

    private static final List<Float> VECTOR = List.of(0.1f, 0.2f);

    private static final List<String> IDS = List.of("A", "B");

    private final GraphObjectOperations operations = mock(GraphObjectOperations.class);

    @Test
    void loadNearestWithOnlyTheRequiredArguments() {
        when(operations.loadNearest(Object.class, VECTOR, 5)).thenCallRealMethod();

        operations.loadNearest(Object.class, VECTOR, 5);

        verify(operations).loadNearest(Object.class, VECTOR, 5, null, null, null);
    }

    @Test
    void loadNearestWithAThreshold() {
        when(operations.loadNearest(Object.class, VECTOR, 5, 0.5)).thenCallRealMethod();

        operations.loadNearest(Object.class, VECTOR, 5, 0.5);

        verify(operations).loadNearest(Object.class, VECTOR, 5, 0.5, null, null);
    }

    @Test
    void loadNearestWithAThresholdAndASearchK() {
        when(operations.loadNearest(Object.class, VECTOR, 5, 0.5, 50)).thenCallRealMethod();

        operations.loadNearest(Object.class, VECTOR, 5, 0.5, 50);

        verify(operations).loadNearest(Object.class, VECTOR, 5, 0.5, 50, null);
    }

    @Test
    void loadNearestOnANamedProperty() {
        when(operations.loadNearest(Object.class, "embedding", VECTOR, 5)).thenCallRealMethod();

        operations.loadNearest(Object.class, "embedding", VECTOR, 5);

        verify(operations).loadNearest(Object.class, "embedding", VECTOR, 5, null, null, null);
    }

    @Test
    void loadNearestOnANamedPropertyWithAThreshold() {
        when(operations.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5)).thenCallRealMethod();

        operations.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5);

        verify(operations).loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, null, null);
    }

    @Test
    void loadNearestOnANamedPropertyWithAThresholdAndASearchK() {
        when(operations.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50)).thenCallRealMethod();

        operations.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50);

        verify(operations).loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50, null);
    }

    @Test
    void loadMatchingWithOnlyTheRequiredArguments() {
        when(operations.loadMatching(Object.class, "graph", 5)).thenCallRealMethod();

        operations.loadMatching(Object.class, "graph", 5);

        verify(operations).loadMatching(Object.class, "graph", 5, 0.0);
    }

    @Test
    void loadMatchingOnANamedProperty() {
        when(operations.loadMatching(Object.class, "text", "graph", 5)).thenCallRealMethod();

        operations.loadMatching(Object.class, "text", "graph", 5);

        verify(operations).loadMatching(Object.class, "text", "graph", 5, 0.0);
    }

    @Test
    void loadNearestAmongWithOnlyTheRequiredArguments() {
        when(operations.loadNearestAmong(Object.class, IDS, 5)).thenCallRealMethod();

        operations.loadNearestAmong(Object.class, IDS, 5);

        verify(operations).loadNearestAmong(Object.class, IDS, 5, null, null, null);
    }

    @Test
    void loadNearestAmongWithAThreshold() {
        when(operations.loadNearestAmong(Object.class, IDS, 5, 0.5)).thenCallRealMethod();

        operations.loadNearestAmong(Object.class, IDS, 5, 0.5);

        verify(operations).loadNearestAmong(Object.class, IDS, 5, 0.5, null, null);
    }

    @Test
    void loadNearestAmongOnANamedProperty() {
        when(operations.loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding")).thenCallRealMethod();

        operations.loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding");

        verify(operations).loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding", null);
    }

    /**
     * A literal null for the threshold, as Java code written before the neighbours passes it, still resolves to the forms it
     * did: the neighbours are taken only in the full form, so no shorter overload makes these calls ambiguous.
     */
    @Test
    void aLiteralNullThresholdStillResolvesToTheFormsWithoutTheNeighbours() {
        when(operations.loadNearestAmong(Object.class, IDS, 5, null)).thenCallRealMethod();
        when(operations.loadNearestAmong(Object.class, IDS, 5, null, null)).thenCallRealMethod();

        operations.loadNearestAmong(Object.class, IDS, 5, null);
        operations.loadNearestAmong(Object.class, IDS, 5, null, null);

        verify(operations, times(2)).loadNearestAmong(Object.class, IDS, 5, null, null, null);
    }

    /**
     * An implementation that predates the neighbours runs every neighbour through the form it has, and refuses only for each
     * pair once, which it cannot answer.
     */
    @Test
    void anImplementationWithoutTheNeighboursRunsAllAndRefusesAfter() {
        when(operations.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.ALL, 0.5, "embedding", 50)).thenCallRealMethod();
        when(operations.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.AFTER, 0.5, "embedding", 50)).thenCallRealMethod();

        operations.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.ALL, 0.5, "embedding", 50);

        verify(operations).loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding", 50);
        assertThrows(
            UnsupportedOperationException.class,
            () -> operations.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.AFTER, 0.5, "embedding", 50)
        );
    }

    /** The full forms, and the same arities on each manager, resolve from Java. */
    @Test
    void theFullFormsAndBothManagersResolve() {
        operations.loadNearest(Object.class, VECTOR, 5, 0.5, 50, "Partition");
        operations.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50, "Partition");
        operations.loadMatching(Object.class, "graph", 5, 0.2);
        operations.loadMatching(Object.class, "text", "graph", 5, 0.2);
        operations.loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding", 50);
        operations.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.AFTER, 0.5, "embedding", 50);

        StatelessGraphObjectManager stateless = mock(StatelessGraphObjectManager.class);
        stateless.loadNearest(Object.class, VECTOR, 5, 0.5);
        stateless.loadNearest(Object.class, VECTOR, 5, 0.5, 50);
        stateless.loadNearest(Object.class, "embedding", VECTOR, 5);
        stateless.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5);
        stateless.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50);
        stateless.loadMatching(Object.class, "text", "graph", 5);
        stateless.loadNearestAmong(Object.class, IDS, 5);
        stateless.loadNearestAmong(Object.class, IDS, 5, 0.5);
        stateless.loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding");
        stateless.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.AFTER, 0.5, "embedding", 50);

        @SuppressWarnings("deprecation")
        GraphObjectManager deprecated = mock(GraphObjectManager.class);
        deprecated.loadNearest(Object.class, VECTOR, 5, 0.5);
        deprecated.loadNearest(Object.class, VECTOR, 5, 0.5, 50);
        deprecated.loadNearest(Object.class, "embedding", VECTOR, 5);
        deprecated.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5);
        deprecated.loadNearest(Object.class, "embedding", VECTOR, 5, 0.5, 50);
        deprecated.loadMatching(Object.class, "text", "graph", 5);
        deprecated.loadNearestAmong(Object.class, IDS, 5);
        deprecated.loadNearestAmong(Object.class, IDS, 5, 0.5);
        deprecated.loadNearestAmong(Object.class, IDS, 5, 0.5, "embedding");
        deprecated.loadNearestAmong(Object.class, IDS, 5, AmongNeighbours.AFTER, 0.5, "embedding", 50);
    }
}
