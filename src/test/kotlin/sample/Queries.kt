package sample

import org.drivine.query.CypherStatement
import org.drivine.query.QueryLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Queries {

    @Bean
    fun listBusinessPartners(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listBusinessPartners"))
    }

    @Bean
    fun listHolidays(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listHolidays"))
    }

    @Bean
    fun listPersons(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listPersons"))
    }

    @Bean
    fun listHolidaysByType(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listHolidaysByType"))
    }

    @Bean
    fun listPersonsByProfession(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listPersonsByProfession"))
    }

}
