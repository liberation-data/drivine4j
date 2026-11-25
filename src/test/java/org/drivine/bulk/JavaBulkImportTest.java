package org.drivine.bulk;

import org.drivine.connection.DatabaseType;
import org.drivine.connection.JdbcConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A bulk import (liberation-data/drivine4j#684) as a Java caller writes it: a lambda that returns nothing, with no type
 * given for its parameter, which only the Consumer overload of run fits. No store: the data source refuses to connect.
 */
public class JavaBulkImportTest {

    private final JdbcConnectionProvider unreachable =
        new JdbcConnectionProvider("bulk", DatabaseType.POSTGRES, new DriverManagerDataSource("jdbc:drivine-none:"));

    @Test
    public void runTakesALambdaThatReturnsNothing() {
        BulkImport importer = new BulkImport(unreachable);
        assertThrows(SQLException.class, () -> importer.run(writer -> writer.nodes("Team", "code", List.of(Map.of("code", "t1")))));
        assertThrows(SQLException.class, () -> importer.run(64, writer -> {
            writer.nodes("Team", "code", List.of(Map.of("code", "t1")));
            writer.relationships("LEADS", new BulkImport.End("Team", "code"), new BulkImport.End("Team", "code"), List.of(new BulkImport.Pair("t1", "t1")));
        }));
        // Refused before it connects.
        assertThrows(IllegalArgumentException.class, () -> importer.run(0, writer -> { }));
    }
}
