package sample

import drivine.query.CypherStatement
import drivine.query.QueryLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Queries {

    @Bean
    fun listBusinessPartners(): CypherStatement {
        return CypherStatement(QueryLoader.loadQuery("listBusinessPartners"))
    }

}