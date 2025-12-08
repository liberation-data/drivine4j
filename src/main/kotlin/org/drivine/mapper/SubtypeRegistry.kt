package org.drivine.mapper

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for polymorphic type mappings.
 * Stores base class to subtype name/class mappings for Jackson deserialization.
 *
 * Thread-safe for concurrent registration and lookup.
 */
class SubtypeRegistry {
    // Map from base class to (subtype name -> subtype class)
    private val registry = ConcurrentHashMap<Class<*>, MutableMap<String, Class<*>>>()

    /**
     * Registers a subtype for a base class.
     *
     * @param baseClass The base class
     * @param name The subtype name (used to match Neo4j labels or type properties)
     * @param subClass The concrete subtype class
     */
    fun register(baseClass: Class<*>, name: String, subClass: Class<*>) {
        registry.computeIfAbsent(baseClass) { ConcurrentHashMap() }[name] = subClass
    }

    /**
     * Registers multiple subtypes for a base class.
     *
     * @param baseClass The base class
     * @param subtypes Pairs of name to subtype class
     */
    fun register(baseClass: Class<*>, vararg subtypes: Pair<String, Class<*>>) {
        val subtypeMap = registry.computeIfAbsent(baseClass) { ConcurrentHashMap() }
        subtypes.forEach { (name, subClass) ->
            subtypeMap[name] = subClass
        }
    }

    /**
     * Gets the subtype map for a base class.
     *
     * @param baseClass The base class
     * @return Map of subtype names to classes, or null if no subtypes registered
     */
    fun getSubtypes(baseClass: Class<*>): Map<String, Class<*>>? {
        return registry[baseClass]
    }

    /**
     * Checks if a base class has registered subtypes.
     */
    fun hasSubtypes(baseClass: Class<*>): Boolean {
        return registry.containsKey(baseClass)
    }

    /**
     * Clears all registered subtypes (useful for testing).
     */
    fun clear() {
        registry.clear()
    }
}