//package drivine.connection
//
//import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
//import drivine.manager.NonTransactionalPersistenceManager
//import drivine.query.QuerySpecification
//import drivine.query.cypherStatement
//import org.junit.jupiter.api.Test
//import java.util.*
//import kotlin.collections.HashMap
//
//
//class NonTransactionalPersistenceManagerTests {
//
//    val provider = Neo4jConnectionProvider(
//        name = "test", type = DatabaseType.NEO4J, host = "localhost",
//        port = 7687, user = "neo4j", password = "mypass", database = "neo4j", protocol = "bolt",
//        config = emptyMap()
//    )
//
//    @Test
//    fun query_should_transform_results() {
//        val manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J)
//        val spec = QuerySpecification
//            .withStatement<BusinessPartner>("match (n:BusinessPartner) return properties(n)")
//            .limit(10)
//            .transform(BusinessPartner::class.java)
//
//        val results = manager.query(spec)
//        println(results)
//    }
//
//    @Test
//    fun foobar() {
//        val objectMapper = jacksonObjectMapper()
//        val mapValue: MutableMap<String, Any> = HashMap()
//        mapValue["clientReferenceDataTypes"] = Arrays.asList("type1", "type2")
//        mapValue["code"] = "BP123"
//
//        val businessPartner: BusinessPartner = objectMapper.convertValue(mapValue, BusinessPartner::class.java)
//        println(businessPartner);
//
//    }
//}
