package sample

import drivine.connection.Person
import drivine.manager.PersistenceManager
import drivine.query.CypherStatement
import drivine.query.QuerySpecification
import drivine.utils.ObjectUtils
import drivine.utils.Partial
import drivine.utils.toMap
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class PersonRepository @Autowired constructor(
    @param:Qualifier("neo") private val persistenceManager: PersistenceManager,
    @param:Qualifier("listPersons") private val stmtListPersons: CypherStatement,
    @param:Qualifier("listPersonsByProfession") private val stmtListPersonsByProfession: CypherStatement
) {

    @Transactional
    fun findPersonsByCity(city: String): List<Person> {
        val spec = QuerySpecification
            .withStatement<Any>(stmtListPersons)
            .bind(mapOf("city" to city))
            .limit(15)
            .transform(Person::class.java)
            .filter { it.isActive }

        return persistenceManager.query(spec)
    }

    @Transactional
    fun findPersonsByProfession(profession: String): List<String> {
        val spec = QuerySpecification
            .withStatement<Any>(stmtListPersonsByProfession)
            .bind(mapOf("profession" to profession))
            .limit(10)
            .transform(Person::class.java)
            .filter { it.age != null && it.age > 25 }
            .map { "${it.firstName} ${it.lastName}" }

        return persistenceManager.query(spec)
    }

    @Transactional
    fun findYoungPeople(): List<Person> {
        val spec = QuerySpecification
            .withStatement<Any>("MATCH (p:Person) WHERE p.createdBy = 'test' RETURN properties(p)")
            .transform(Person::class.java)
            .filter { it.age != null && it.age < 30 }
            .filter { it.isActive }

        return persistenceManager.query(spec)
    }

    @Transactional
    fun update(person: Person): Person {
        return update(ObjectUtils.primitiveProps(person, includeNulls = false))
    }

    @Transactional
    fun update(uuid: UUID, partial: Partial<Person>): Person {
        return update(buildMap {
            putAll(partial.toMap())
            put("uuid", uuid.toString())
        })
    }

    private fun update(props: Map<String, Any?>): Person {
        val statement = """
            MERGE (p:Person {uuid: ${'$'}props.uuid})
            SET p += ${'$'}props
            RETURN properties(p)
        """

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement<Any>(statement)
                .bind(mapOf("props" to props))
                .transform(Person::class.java)
        )
    }

}
