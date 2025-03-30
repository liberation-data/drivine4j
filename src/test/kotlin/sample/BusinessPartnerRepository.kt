package sample

import drivine.connection.BusinessPartner
import drivine.manager.PersistenceManager
import drivine.query.QuerySpecification
import drivine.transaction.DrivineTransactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class BusinessPartnerRepository @Autowired constructor(
    @Qualifier("neo") private val persistenceManager: PersistenceManager,
    @Qualifier("listBusinessPartners") private val stmtListPartners: String
) {

    @DrivineTransactional
    fun listBusinessPartner(nameStartsWith: String): List<BusinessPartner> {
        val spec = QuerySpecification
            .withStatement<BusinessPartner>(stmtListPartners)
            .bind(mapOf("startsWith" to nameStartsWith))
            .limit(10)
            .transform(BusinessPartner::class.java)

        val results = persistenceManager.query(spec)
        return results
    }
}
