package sample

import org.drivine.connection.Holiday
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.*

@SpringBootTest(classes = [TestAppContext::class])
class HolidayRepositoryTests @Autowired constructor(
    private val manager: PersistenceManager,
    private val holidayRepository: HolidayRepository
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (h:Holiday) WHERE h.createdBy = 'test' DETACH DELETE h"))

        // Insert test holidays
        val holidayData = listOf(
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Independence Day",
                "date" to "2024-07-04",
                "country" to "USA",
                "type" to "national",
                "description" to "American Independence Day",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("patriotic", "fireworks")
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Christmas Day",
                "date" to "2024-12-25",
                "country" to "USA",
                "type" to "religious",
                "description" to "Christian celebration",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("christian", "family")
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Canada Day",
                "date" to "2024-07-01",
                "country" to "Canada",
                "type" to "national",
                "description" to "Canadian national day",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("patriotic", "maple")
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Diwali",
                "date" to "2024-11-01",
                "country" to "India",
                "type" to "religious",
                "description" to "Festival of lights",
                "isPublicHoliday" to false,
                "createdBy" to "test",
                "tags" to listOf("hindu", "lights")
            )
        )

        holidayData.forEach { holiday ->
            val query = """
                MERGE (h:Holiday {uuid: ${'$'}holiday.uuid})
                SET h.createdTimestamp = datetime().epochMillis,
                h += ${'$'}holiday
            """.trimIndent()
            manager.execute(QuerySpecification
                .withStatement(query)
                .bind(mapOf("holiday" to holiday)))
        }
    }

    @Test
    fun testHolidayQueryChaining() {
        val spec = QuerySpecification
            .withStatement("""
                MATCH (h:Holiday) WHERE h.createdBy = 'test' RETURN properties(h)
                """.trimIndent())
            .limit(10)
            .transform(Holiday::class.java)
            .filter { it.isPublicHoliday }

        val results = manager.query(spec)
        println("Public holidays: ${results.map { it.name }}")
        assert(results.all { it.isPublicHoliday })
    }

    @Test
    fun testHolidayRepositoryMethods() {
        val usaHolidays = holidayRepository.findHolidaysByCountry("USA")
        println("USA holidays: ${usaHolidays.map { it.name }}")

        val nationalHolidays = holidayRepository.findHolidaysByType("national")
        println("National holidays: ${nationalHolidays.map { it.name }}")
    }
}
