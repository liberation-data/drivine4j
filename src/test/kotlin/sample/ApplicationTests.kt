package sample

import drivine.connection.BusinessPartner
import drivine.manager.PersistenceManager
import drivine.manager.PersistenceManagerFactory
import drivine.query.QuerySpecification
import drivine.query.cypherStatement
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.AnnotationConfigApplicationContext



@SpringBootTest(classes = [AppContext::class])
class ApplicationTests @Autowired constructor(
    private val manager: PersistenceManager,
    private val repo: BusinessPartnerRepository
) {

    @Test
    fun foo() {
        val spec = QuerySpecification<BusinessPartner>(
            cypherStatement("MATCH (n:BusinessPartner) RETURN properties(n)")
        )
            .limit(10)
            .transform(BusinessPartner::class.java)

        val results = manager.query(spec)
        println(results)
    }

    @Test
    fun bar() {
        val results = repo.listBusinessPartner()
        println(results)
    }
}