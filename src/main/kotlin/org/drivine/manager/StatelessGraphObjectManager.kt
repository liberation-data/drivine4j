package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.SubtypeRegistry

/**
 * An object manager that remembers nothing between calls. What a save writes is decided by the
 * object and the arguments, never by whether the object was loaded here before.
 *
 * Not implemented yet: the signatures are here so the tests that describe it compile.
 */
@Suppress("UNUSED_PARAMETER", "unused")
class StatelessGraphObjectManager(
    private val persistenceManager: PersistenceManager,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry,
) {

    fun <T : Any> load(id: String, graphClass: Class<T>): T? = TODO("StatelessGraphObjectManager.load")

    /** Saves [obj] and returns it carrying its new stamp. */
    fun <T : Any> save(
        obj: T,
        relationships: RelationshipWrite = Add,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): T = TODO("StatelessGraphObjectManager.save")
}

/** Loads a single graph object by ID, with a reified type. */
inline fun <reified T : Any> StatelessGraphObjectManager.load(id: String): T? = load(id, T::class.java)
