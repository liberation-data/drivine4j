package sample

import drivine.connection.BusinessPartner
import drivine.manager.PersistenceManager
import drivine.query.QuerySpecification
import drivine.query.cypherStatement
import drivine.transaction.DrivineTransactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class BusinessPartnerRepository @Autowired constructor(
    @Qualifier("neo") private val persistenceManager: PersistenceManager
) {

    @DrivineTransactional
    fun listBusinessPartner(): List<BusinessPartner> {
        val spec = QuerySpecification<BusinessPartner>(
            cypherStatement("MATCH (n:BusinessPartner) RETURN properties(n)")
        )
            .limit(10)
            .transform(BusinessPartner::class.java)

        val results = persistenceManager.query(spec)
        return results
    }
}
