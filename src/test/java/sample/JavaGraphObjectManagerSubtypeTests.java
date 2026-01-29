package sample;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
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

@SpringBootTest(classes = TestAppContext.class)
@Transactional
@Rollback(true)
public class JavaGraphObjectManagerSubtypeTests {

    @Autowired
    private GraphObjectManager graphObjectManager;

    @Autowired
    private PersistenceManager persistenceManager;

    @BeforeEach
    public void setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (a) WHERE a.createdBy = 'graphobject-subtype-test' DETACH DELETE a"
            )
        );

        // Create test vehicles with different labels
        persistenceManager.execute(
            QuerySpecification.withStatement("""
                CREATE (c1:Vehicle:Car {uuid: $car1Id, model: 'Tesla Model 3', doors: 4, createdBy: 'graphobject-subtype-test'})
                CREATE (c2:Vehicle:Car {uuid: $car2Id, model: 'BMW M3', doors: 2, createdBy: 'graphobject-subtype-test'})
                CREATE (t1:Vehicle:Truck {uuid: $truck1Id, model: 'Ford F-150', payloadTons: 2.5, createdBy: 'graphobject-subtype-test'})
                CREATE (m1:Vehicle:Motorcycle {uuid: $moto1Id, model: 'Harley Davidson', hasGears: true, createdBy: 'graphobject-subtype-test'})
                CREATE (m2:Vehicle:Motorcycle {uuid: $moto2Id, model: 'Vespa', hasGears: false, createdBy: 'graphobject-subtype-test'})
                """)
                .bind(Map.of(
                    "car1Id", UUID.randomUUID().toString(),
                    "car2Id", UUID.randomUUID().toString(),
                    "truck1Id", UUID.randomUUID().toString(),
                    "moto1Id", UUID.randomUUID().toString(),
                    "moto2Id", UUID.randomUUID().toString()
                ))
        );
    }

    // ========================================
    // Test 1: Abstract class with @JsonSubTypes - Auto-detection
    // ========================================

    @JsonSubTypes({
        @JsonSubTypes.Type(value = AnnotatedCar.class, name = "Car"),
        @JsonSubTypes.Type(value = AnnotatedTruck.class, name = "Truck"),
        @JsonSubTypes.Type(value = AnnotatedMotorcycle.class, name = "Motorcycle")
    })
    @NodeFragment(labels = {"Vehicle"})
    public static abstract class AnnotatedVehicle {
        @NodeId
        public UUID uuid;
        public String model;

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    @NodeFragment(labels = {"Vehicle", "Car"})
    public static class AnnotatedCar extends AnnotatedVehicle {
        public int doors;

        public AnnotatedCar() {}

        public AnnotatedCar(UUID uuid, String model, int doors) {
            this.uuid = uuid;
            this.model = model;
            this.doors = doors;
        }

        public int getDoors() { return doors; }
        public void setDoors(int doors) { this.doors = doors; }
    }

    @NodeFragment(labels = {"Vehicle", "Truck"})
    public static class AnnotatedTruck extends AnnotatedVehicle {
        public double payloadTons;

        public AnnotatedTruck() {}

        public AnnotatedTruck(UUID uuid, String model, double payloadTons) {
            this.uuid = uuid;
            this.model = model;
            this.payloadTons = payloadTons;
        }

        public double getPayloadTons() { return payloadTons; }
        public void setPayloadTons(double payloadTons) { this.payloadTons = payloadTons; }
    }

    @NodeFragment(labels = {"Vehicle", "Motorcycle"})
    public static class AnnotatedMotorcycle extends AnnotatedVehicle {
        public boolean hasGears;

        public AnnotatedMotorcycle() {}

        public AnnotatedMotorcycle(UUID uuid, String model, boolean hasGears) {
            this.uuid = uuid;
            this.model = model;
            this.hasGears = hasGears;
        }

        public boolean isHasGears() { return hasGears; }
        public void setHasGears(boolean hasGears) { this.hasGears = hasGears; }
    }

    @Test
    public void testGraphObjectManagerAutomaticallyDetectsJsonSubTypesAnnotation() {
        // GraphObjectManager should auto-register subtypes from @JsonSubTypes
        List<AnnotatedVehicle> vehicles = graphObjectManager.loadAll(AnnotatedVehicle.class);

        System.out.println("Loaded " + vehicles.size() + " vehicles");
        vehicles.forEach(vehicle ->
            System.out.println("Vehicle: " + vehicle.getModel() + " (" + vehicle.getClass().getSimpleName() + ")")
        );

        assertEquals(5, vehicles.size());

        // Verify correct subtype deserialization
        List<AnnotatedCar> cars = vehicles.stream()
            .filter(v -> v instanceof AnnotatedCar)
            .map(v -> (AnnotatedCar) v)
            .toList();

        List<AnnotatedTruck> trucks = vehicles.stream()
            .filter(v -> v instanceof AnnotatedTruck)
            .map(v -> (AnnotatedTruck) v)
            .toList();

        List<AnnotatedMotorcycle> motorcycles = vehicles.stream()
            .filter(v -> v instanceof AnnotatedMotorcycle)
            .map(v -> (AnnotatedMotorcycle) v)
            .toList();

        assertEquals(2, cars.size(), "Should have 2 cars");
        assertEquals(1, trucks.size(), "Should have 1 truck");
        assertEquals(2, motorcycles.size(), "Should have 2 motorcycles");

        // Verify properties
        assertTrue(cars.stream().anyMatch(c ->
            "Tesla Model 3".equals(c.getModel()) && c.getDoors() == 4));
        assertTrue(trucks.stream().anyMatch(t ->
            "Ford F-150".equals(t.getModel()) && t.getPayloadTons() == 2.5));
        assertTrue(motorcycles.stream().anyMatch(m ->
            "Harley Davidson".equals(m.getModel()) && m.isHasGears()));
    }

    // ========================================
    // Test 2: Manual registration via PersistenceManager
    // ========================================

    @NodeFragment(labels = {"Vehicle"})
    public static class ManualVehicle {
        @NodeId
        public UUID uuid;
        public String model;

        public ManualVehicle() {}

        public ManualVehicle(UUID uuid, String model) {
            this.uuid = uuid;
            this.model = model;
        }

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    @NodeFragment(labels = {"Vehicle", "Car"})
    public static class ManualCar extends ManualVehicle {
        public int doors;

        public ManualCar() {}

        public ManualCar(UUID uuid, String model, int doors) {
            super(uuid, model);
            this.doors = doors;
        }

        public int getDoors() { return doors; }
        public void setDoors(int doors) { this.doors = doors; }
    }

    @NodeFragment(labels = {"Vehicle", "Truck"})
    public static class ManualTruck extends ManualVehicle {
        public double payloadTons;

        public ManualTruck() {}

        public ManualTruck(UUID uuid, String model, double payloadTons) {
            super(uuid, model);
            this.payloadTons = payloadTons;
        }

        public double getPayloadTons() { return payloadTons; }
        public void setPayloadTons(double payloadTons) { this.payloadTons = payloadTons; }
    }

    @Test
    public void testManualSubtypeRegistrationViaPersistenceManager() {
        // Manually register subtypes (useful for non-sealed classes)
        persistenceManager.registerSubtype(
            ManualVehicle.class,
            Arrays.asList("Vehicle", "Car"),
            ManualCar.class
        );
        persistenceManager.registerSubtype(
            ManualVehicle.class,
            Arrays.asList("Vehicle", "Truck"),
            ManualTruck.class
        );

        // Now query using PersistenceManager (not GraphObjectManager)
        List<ManualVehicle> vehicles = persistenceManager.query(
            QuerySpecification.withStatement("""
                MATCH (v:Vehicle)
                WHERE v.createdBy = 'graphobject-subtype-test'
                WITH properties(v) AS props, labels(v) AS lbls
                RETURN props {.*, labels: lbls} AS v
                ORDER BY v.model
                """)
                .transform(ManualVehicle.class)
        );

        System.out.println("Manually registered vehicles: " + vehicles.size());
        vehicles.forEach(vehicle ->
            System.out.println("Vehicle: " + vehicle.getModel() + " (" + vehicle.getClass().getSimpleName() + ")")
        );

        // Should have at least the cars and trucks
        List<ManualCar> cars = vehicles.stream()
            .filter(v -> v instanceof ManualCar)
            .map(v -> (ManualCar) v)
            .collect(Collectors.toList());

        List<ManualTruck> trucks = vehicles.stream()
            .filter(v -> v instanceof ManualTruck)
            .map(v -> (ManualTruck) v)
            .collect(Collectors.toList());

        assertEquals(2, cars.size(), "Should have 2 cars");
        assertEquals(1, trucks.size(), "Should have 1 truck");
    }

    @Test
    public void testLoadSingleVehicleByIdWithJsonSubTypes() {
        // First, get a vehicle to know its UUID
        List<AnnotatedVehicle> allVehicles = graphObjectManager.loadAll(AnnotatedVehicle.class);
        AnnotatedCar someCar = allVehicles.stream()
            .filter(v -> v instanceof AnnotatedCar)
            .map(v -> (AnnotatedCar) v)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No car found"));

        // Now load by ID - should still deserialize to correct subtype
        AnnotatedVehicle loaded = graphObjectManager.load(someCar.getUuid().toString(), AnnotatedVehicle.class);

        assertTrue(loaded instanceof AnnotatedCar, "Should deserialize to AnnotatedCar");
        assertEquals(someCar.getModel(), loaded.getModel());
        assertEquals(someCar.getDoors(), ((AnnotatedCar) loaded).getDoors());

        System.out.println("Successfully loaded by ID: " + loaded.getModel() + " as " + loaded.getClass().getSimpleName());
    }
}
