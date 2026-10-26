package org.drivine.connection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcConnectionProvider} has a constructor for each arity, so a Java caller that passed every
 * parameter before {@code setsNoAdaptivePlans} was added still builds, and gets the default: each
 * Oracle session of its data source is set to make no adaptive plan.
 */
class JavaJdbcConnectionProviderConstructorsTest {

    private Statement statement;
    private Connection jdbc;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        statement = mock(Statement.class);
        jdbc = mock(Connection.class);
        when(jdbc.createStatement()).thenReturn(statement);
        dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(jdbc);
    }

    @Test
    void theFormWithEveryParameterBeforeTheOptOutSetsEachSession() throws SQLException {
        new JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, null, false, "drivine").connect();

        verify(statement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS);
    }

    @Test
    void theShortestFormSetsEachSession() throws SQLException {
        new JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource).connect();

        verify(statement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS);
    }

    @Test
    void theFullFormCanLeaveEachSessionAsItIs() throws SQLException {
        new JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, null, false, "drivine", false).connect();

        verify(jdbc, never()).createStatement();
    }
}
