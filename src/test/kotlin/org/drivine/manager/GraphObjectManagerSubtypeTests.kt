package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class GraphObjectManagerSubtypeTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (a) WHERE a.createdBy = 'graphobject-subtype-test' DETACH DELETE a")
        )

        // Create test vehicles with different labels
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    """
                    CREATE (c1:Vehicle:Car {uuid: ${'$'}car1Id, model: 'Tesla Model 3', doors: 4, createdBy: 'graphobject-subtype-test'})
                    CREATE (c2:Vehicle:Car {uuid: ${'$'}car2Id, model: 'BMW M3', doors: 2, createdBy: 'graphobject-subtype-test'})
                    CREATE (t1:Vehicle:Truck {uuid: ${'$'}truck1Id, model: 'Ford F-150', payloadTons: 2.5, createdBy: 'graphobject-subtype-test'})
                    CREATE (m1:Vehicle:Motorcycle {uuid: ${'$'}moto1Id, model: 'Harley Davidson', hasGears: true, createdBy: 'graphobject-subtype-test'})
                    CREATE (m2:Vehicle:Motorcycle {uuid: ${'$'}moto2Id, model: 'Vespa', hasGears: false, createdBy: 'graphobject-subtype-test'})
                    """.trimIndent()
                )
                .bind(
                    mapOf(
                        "car1Id" to UUID.randomUUID().toString(),
                        "car2Id" to UUID.randomUUID().toString(),
                        "truck1Id" to UUID.randomUUID().toString(),
                        "moto1Id" to UUID.randomUUID().toString(),
                        "moto2Id" to UUID.randomUUID().toString()
                    )
                )
        )
    }

    // ========================================
    // Test 1: Abstract class with @JsonSubTypes - Auto-detection
    // ========================================

    @JsonSubTypes(
        JsonSubTypes.Type(value = AnnotatedCar::class, name = "Car"),
        JsonSubTypes.Type(value = AnnotatedTruck::class, name = "Truck"),
        JsonSubTypes.Type(value = AnnotatedMotorcycle::class, name = "Motorcycle")
    )
    @NodeFragment(labels = ["Vehicle"])
    abstract class AnnotatedVehicle {
        @NodeId abstract val uuid: UUID
        abstract val model: String
    }

    @NodeFragment(labels = ["Vehicle", "Car"])
    data class AnnotatedCar(
        @NodeId override val uuid: UUID,
        override val model: String,
        val doors: Int
    ) : AnnotatedVehicle()

    @NodeFragment(labels = ["Vehicle", "Truck"])
    data class AnnotatedTruck(
        @NodeId override val uuid: UUID,
        override val model: String,
        val payloadTons: Double
    ) : AnnotatedVehicle()

    @NodeFragment(labels = ["Vehicle", "Motorcycle"])
    data class AnnotatedMotorcycle(
        @NodeId override val uuid: UUID,
        override val model: String,
        val hasGears: Boolean
    ) : AnnotatedVehicle()

    @Test
    fun `GraphObjectManager automatically detects JsonSubTypes annotation`() {
        // GraphObjectManager should auto-register subtypes from @JsonSubTypes
        val vehicles = graphObjectManager.loadAll(AnnotatedVehicle::class.java)

        println("Loaded ${vehicles.size} vehicles")
        vehicles.forEach { vehicle ->
            println("Vehicle: ${vehicle.model} (${vehicle::class.simpleName})")
        }

        assertEquals(5, vehicles.size)

        // Verify correct subtype deserialization
        val cars = vehicles.filterIsInstance<AnnotatedCar>()
        val trucks = vehicles.filterIsInstance<AnnotatedTruck>()
        val motorcycles = vehicles.filterIsInstance<AnnotatedMotorcycle>()

        assertEquals(2, cars.size, "Should have 2 cars")
        assertEquals(1, trucks.size, "Should have 1 truck")
        assertEquals(2, motorcycles.size, "Should have 2 motorcycles")

        // Verify properties
        assertTrue(cars.any { it.model == "Tesla Model 3" && it.doors == 4 })
        assertTrue(trucks.any { it.model == "Ford F-150" && it.payloadTons == 2.5 })
        assertTrue(motorcycles.any { it.model == "Harley Davidson" && it.hasGears })
    }

    // ========================================
    // Test 2: Kotlin sealed class - Auto-detection (no annotation needed!)
    // ========================================

    @NodeFragment(labels = ["Vehicle"])
    sealed class PlainVehicle {
        @NodeId abstract val uuid: UUID
        abstract val model: String
    }

    @NodeFragment(labels = ["Vehicle", "Car"])
    data class PlainCar(
        @NodeId override val uuid: UUID,
        override val model: String,
        val doors: Int
    ) : PlainVehicle()

    @NodeFragment(labels = ["Vehicle", "Truck"])
    data class PlainTruck(
        @NodeId override val uuid: UUID,
        override val model: String,
        val payloadTons: Double
    ) : PlainVehicle()

    @NodeFragment(labels = ["Vehicle", "Motorcycle"])
    data class PlainMotorcycle(
        @NodeId override val uuid: UUID,
        override val model: String,
        val hasGears: Boolean
    ) : PlainVehicle()

    @Test
    fun `GraphObjectManager automatically detects Kotlin sealed class subtypes`() {
        // GraphObjectManager should auto-register subtypes from sealed class
        val vehicles = graphObjectManager.loadAll(PlainVehicle::class.java)

        println("Loaded ${vehicles.size} vehicles from sealed class")
        vehicles.forEach { vehicle ->
            println("Vehicle: ${vehicle.model} (${vehicle::class.simpleName})")
        }

        assertEquals(5, vehicles.size)

        // Verify correct subtype deserialization
        val cars = vehicles.filterIsInstance<PlainCar>()
        val trucks = vehicles.filterIsInstance<PlainTruck>()
        val motorcycles = vehicles.filterIsInstance<PlainMotorcycle>()

        assertEquals(2, cars.size, "Should have 2 cars")
        assertEquals(1, trucks.size, "Should have 1 truck")
        assertEquals(2, motorcycles.size, "Should have 2 motorcycles")

        // Verify properties
        assertTrue(cars.any { it.model == "BMW M3" && it.doors == 2 })
        assertTrue(trucks.any { it.model == "Ford F-150" && it.payloadTons == 2.5 })
        assertTrue(motorcycles.any { it.model == "Vespa" && !it.hasGears })
    }

    // ========================================
    // Test 3: Manual registration via PersistenceManager
    // ========================================

    @NodeFragment(labels = ["Vehicle"])
    open class ManualVehicle(
        @NodeId open val uuid: UUID,
        open val model: String
    )

    @NodeFragment(labels = ["Vehicle", "Car"])
    data class ManualCar(
        @NodeId override val uuid: UUID,
        override val model: String,
        val doors: Int
    ) : ManualVehicle(uuid, model)

    @NodeFragment(labels = ["Vehicle", "Truck"])
    data class ManualTruck(
        @NodeId override val uuid: UUID,
        override val model: String,
        val payloadTons: Double
    ) : ManualVehicle(uuid, model)

    @Test
    fun `Manual subtype registration via PersistenceManager`() {
        // Manually register subtypes (useful for non-sealed classes)
        persistenceManager.registerSubtypes(
            ManualVehicle::class.java,
            "Car" to ManualCar::class.java,
            "Truck" to ManualTruck::class.java
        )

        // Now query using PersistenceManager (not GraphObjectManager)
        val vehicles = persistenceManager.query(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (v:Vehicle)
                    WHERE v.createdBy = 'graphobject-subtype-test'
                    WITH properties(v) AS props, labels(v) AS lbls
                    RETURN props {.*, labels: lbls} AS v
                    ORDER BY v.model
                    """.trimIndent()
                )
                .transform(ManualVehicle::class.java)
        )

        println("Manually registered vehicles: ${vehicles.size}")
        vehicles.forEach { vehicle ->
            println("Vehicle: ${vehicle.model} (${vehicle::class.simpleName})")
        }

        // Should have at least the cars and trucks
        val cars = vehicles.filterIsInstance<ManualCar>()
        val trucks = vehicles.filterIsInstance<ManualTruck>()

        assertEquals(2, cars.size, "Should have 2 cars")
        assertEquals(1, trucks.size, "Should have 1 truck")
    }

    @Test
    fun `Load single vehicle by ID with sealed class hierarchy`() {
        // First, get a vehicle to know its UUID
        val allVehicles = graphObjectManager.loadAll(PlainVehicle::class.java)
        val someCar = allVehicles.filterIsInstance<PlainCar>().first()

        // Now load by ID - should still deserialize to correct subtype
        val loaded = graphObjectManager.load(someCar.uuid.toString(), PlainVehicle::class.java)

        assertTrue(loaded is PlainCar, "Should deserialize to PlainCar")
        assertEquals(someCar.model, loaded.model)
        assertEquals(someCar.doors, (loaded as PlainCar).doors)

        println("Successfully loaded by ID: ${loaded.model} as ${loaded::class.simpleName}")
    }
}