package com.liberation_data.bolt_neptune
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.springframework.boot.test.context.SpringBootTest

//@SpringBootTest
class BoltNeptuneApplicationTests {

	@Test
	fun contextLoads() {
	}

	@Test
	fun doStuffWithBolt() {
		val driver: Driver = GraphDatabase.driver(
			"bolt://db-neptune-2-instance-1.cokvzz862p37.ap-southeast-2.neptune.amazonaws.com:8182",
			AuthTokens.none(),
			Config.builder()
				.withEncryption()
				.withTrustStrategy(Config.TrustStrategy.trustSystemCertificates())
				.build())

		val session = driver.session()
		val result = session.run("match (n) return count(n)")
		println("$$$$$$$$$$$$$$" + result.keys())
		println(result.list())
		println(result)
		session.close()

	}

}
