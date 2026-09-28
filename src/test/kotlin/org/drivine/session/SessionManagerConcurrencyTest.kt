package org.drivine.session

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.junit.jupiter.api.Test
import sample.mapped.fragment.Person
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class SessionManagerConcurrencyTest {

    @Test
    fun `concurrent snapshots neither throw nor lose entries`() {
        val session = SessionManager(Neo4jObjectMapper.instance)
        val model = FragmentModel.from(Person::class.java)
        val threads = 16
        val perThread = 2_000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = (0 until threads).map {
                Callable {
                    val people = List(perThread) { Person(UUID.randomUUID(), "name", "bio") }
                    start.await()
                    people.forEach { person ->
                        session.snapshot(person, model)
                        session.getDirtyFields(person, person.uuid)
                    }
                    people
                }
            }
            val futures = tasks.map { pool.submit(it) }
            start.countDown()
            val people = futures.flatMap { it.get(2, TimeUnit.MINUTES) }

            assertEquals(threads * perThread, session.size)
            people.forEach { assertEquals(emptySet(), session.getDirtyFields(it, it.uuid), "lost entry for ${it.uuid}") }
        } finally {
            pool.shutdownNow()
        }
    }
}
