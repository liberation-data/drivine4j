package sample;

import org.drivine.connection.Person;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.QuerySpecification;
import org.drivine.test.DrivineTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure Java test to verify that optionalGetOne works correctly from Java code.
 * This tests the fix for InvocationTargetException issues when calling from Java.
 */
@SpringBootTest(classes = TestAppContext.class)
@DrivineTest
public class JavaOptionalGetOneTest {

    @Autowired
    private PersistenceManager manager;

    private String testUuid;

    @BeforeEach
    public void setupTestData() {
        // Clean up any existing test data
        manager.execute(QuerySpecification
                .withStatement("MATCH (p:Person) WHERE p.createdBy = 'java-test' DETACH DELETE p"));

        // Insert a test person
        testUuid = UUID.randomUUID().toString();
        String insertQuery = """
            CREATE (p:Person {
                uuid: $person.uuid,
                firstName: $person.firstName,
                lastName: $person.lastName,
                email: $person.email,
                age: $person.age,
                city: $person.city,
                country: $person.country,
                profession: $person.profession,
                isActive: $person.isActive,
                createdBy: $person.createdBy,
                createdTimestamp: datetime().epochMillis
            })
            """;

        var personData = java.util.Map.of(
                "uuid", testUuid,
                "firstName", "John",
                "lastName", "Doe",
                "email", "john.doe@example.com",
                "age", 30,
                "city", "San Francisco",
                "country", "USA",
                "profession", "Software Engineer",
                "isActive", true,
                "createdBy", "java-test"
        );

        manager.execute(QuerySpecification
                .withStatement(insertQuery)
                .bind(java.util.Map.of("person", personData)));
    }

    @Test
    public void testOptionalGetOne_whenResultExists_returnsPresent() {
        // Test when a result exists
        QuerySpecification<Person> spec = QuerySpecification
                .withStatement("MATCH (p:Person {uuid: $uuid}) RETURN properties(p)")
                .bind(java.util.Map.of("uuid", testUuid))
                .transform(Person.class);

        Optional<Person> result = manager.optionalGetOne(spec);

        assertTrue(result.isPresent(), "Result should be present");
        assertEquals("John", result.get().getFirstName());
        assertEquals("Doe", result.get().getLastName());
        assertEquals("john.doe@example.com", result.get().getEmail());
    }

    @Test
    public void testOptionalGetOne_whenNoResult_returnsEmpty() {
        // Test when no result exists
        String nonExistentUuid = UUID.randomUUID().toString();
        QuerySpecification<Person> spec = QuerySpecification
                .withStatement("MATCH (p:Person {uuid: $uuid}) RETURN properties(p)")
                .bind(java.util.Map.of("uuid", nonExistentUuid))
                .transform(Person.class);

        Optional<Person> result = manager.optionalGetOne(spec);

        assertFalse(result.isPresent(), "Result should be empty");
        assertTrue(result.isEmpty(), "Result should be empty");
    }

    @Test
    public void testOptionalGetOne_withJavaOptionalMethods() {
        // Test Java Optional methods work correctly
        QuerySpecification<Person> spec = QuerySpecification
                .withStatement("MATCH (p:Person {uuid: $uuid}) RETURN properties(p)")
                .bind(java.util.Map.of("uuid", testUuid))
                .transform(Person.class);

        Optional<Person> result = manager.optionalGetOne(spec);

        // Test various Optional methods
        result.ifPresent(person -> {
            assertNotNull(person.getFirstName());
            System.out.println("Found person: " + person.getFirstName() + " " + person.getLastName());
        });

        String name = result.map(Person::getFirstName).orElse("Unknown");
        assertEquals("John", name);

        Person person = result.orElseThrow(() -> new RuntimeException("Person not found"));
        assertNotNull(person);
    }

    @Test
    public void testOptionalGetOne_multipleResults_throwsException() {
        // Insert another person with same first name to test multiple results error
        String secondUuid = UUID.randomUUID().toString();
        String insertQuery = """
            CREATE (p:Person {
                uuid: $uuid,
                firstName: 'John',
                lastName: 'Smith',
                email: 'john.smith@example.com',
                createdBy: 'java-test',
                createdTimestamp: datetime().epochMillis
            })
            """;

        manager.execute(QuerySpecification
                .withStatement(insertQuery)
                .bind(java.util.Map.of("uuid", secondUuid)));

        QuerySpecification<Person> spec = QuerySpecification
                .withStatement("MATCH (p:Person) WHERE p.firstName = 'John' AND p.createdBy = 'java-test' RETURN properties(p)")
                .transform(Person.class);

        // Should throw DrivineException because multiple results exist
        assertThrows(Exception.class, () -> manager.optionalGetOne(spec),
                "Should throw exception when multiple results found");
    }
}