package sample

import org.drivine.connection.Holiday
import org.drivine.manager.PersistenceManager
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class HolidayRepository @Autowired constructor(
    @param:Qualifier("neo") private val persistenceManager: PersistenceManager,
    @param:Qualifier("listHolidays") private val stmtListHolidays: CypherStatement,
    @param:Qualifier("listHolidaysByType") private val stmtListHolidaysByType: CypherStatement
) {

    @Transactional
    fun findHolidaysByCountry(country: String): List<Holiday> {
        val spec = QuerySpecification
            .withStatement<Any>(stmtListHolidays)
            .bind(mapOf("country" to country))
            .limit(20)
            .transform(Holiday::class.java)
            .filter { it.isPublicHoliday }

        return persistenceManager.query(spec)
    }

    @Transactional
    fun findHolidaysByType(type: String): List<Holiday> {
        val spec = QuerySpecification
            .withStatement<Any>(stmtListHolidaysByType)
            .bind(mapOf("type" to type))
            .limit(15)
            .transform(Holiday::class.java)

        return persistenceManager.query(spec)
    }

}
