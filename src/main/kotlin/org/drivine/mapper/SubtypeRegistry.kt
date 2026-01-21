package org.drivine.mapper

import com.fasterxml.jackson.databind.module.SimpleModule
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for polymorphic type mappings.
 * Stores base class to subtype name/class mappings for Jackson deserialization.
 *
 * Supports both single-label and multi-label (composite) registrations.
 * For multi-label types, labels are sorted alphabetically and joined with comma
 * to create a deterministic composite key (e.g., ["WebUser", "Anonymous"] -> "Anonymous,WebUser").
 *
 * Thread-safe for concurrent registration and lookup.
 */
class SubtypeRegistry {
    // Map from base class to (subtype name -> subtype class)
    private val registry = ConcurrentHashMap<Class<*>, MutableMap<String, Class<*>>>()

    // Tracks which base->subclass mappings have been registered with Jackson (to avoid duplicates)
    private val jacksonMappings = ConcurrentHashMap.newKeySet<String>()

    /**
     * Registers a subtype for a base class using a single label/name.
     *
     * Also registers the abstract type mapping with Jackson's ObjectMapper to enable
     * deserialization of the interface/abstract type to the concrete implementation.
     * This is needed for SessionManager.getSnapshot() which uses Jackson's treeToValue().
     *
     * @param baseClass The base class
     * @param name The subtype name (used to match Neo4j labels or type properties)
     * @param subClass The concrete subtype class
     */
    fun register(baseClass: Class<*>, name: String, subClass: Class<*>) {
        registry.computeIfAbsent(baseClass) { ConcurrentHashMap() }[name] = subClass

        // Also register with Jackson for abstract type mapping (needed for snapshot deserialization)
        registerWithJackson(baseClass, subClass)
    }

    /**
     * Registers a subtype for a base class using multiple labels.
     * Labels are sorted alphabetically and joined with comma to create
     * a composite key for matching.
     *
     * Also registers the abstract type mapping with Jackson's ObjectMapper.
     *
     * Example:
     * ```kotlin
     * registry.registerWithLabels(WebUserData::class.java,
     *     listOf("WebUser", "Anonymous"),
     *     AnonymousWebUserData::class.java)
     * ```
     * This registers with key "Anonymous,WebUser".
     *
     * @param baseClass The base class
     * @param labels The Neo4j labels for this subtype
     * @param subClass The concrete subtype class
     */
    fun registerWithLabels(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) {
        val compositeKey = labelsToKey(labels)
        registry.computeIfAbsent(baseClass) { ConcurrentHashMap() }[compositeKey] = subClass

        // Also register with Jackson for abstract type mapping
        registerWithJackson(baseClass, subClass)
    }

    /**
     * Registers multiple subtypes for a base class.
     *
     * Also registers abstract type mappings with Jackson's ObjectMapper for each subtype.
     *
     * @param baseClass The base class
     * @param subtypes Pairs of name to subtype class
     */
    fun register(baseClass: Class<*>, vararg subtypes: Pair<String, Class<*>>) {
        val subtypeMap = registry.computeIfAbsent(baseClass) { ConcurrentHashMap() }
        subtypes.forEach { (name, subClass) ->
            subtypeMap[name] = subClass
            // Also register with Jackson for abstract type mapping
            registerWithJackson(baseClass, subClass)
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
     * Resolves a subtype for a base class given a list of Neo4j labels.
     * First tries composite key match (most specific), then falls back
     * to individual label matching.
     *
     * @param baseClass The base class
     * @param labels The Neo4j labels from the node
     * @return The matching subtype class, or null if no match
     */
    fun resolveByLabels(baseClass: Class<*>, labels: List<String>): Class<*>? {
        val subtypeMap = registry[baseClass] ?: return null

        // First, try composite key (most specific match)
        val compositeKey = labelsToKey(labels)
        subtypeMap[compositeKey]?.let { return it }

        // Fall back to individual label matching (first match wins)
        for (label in labels) {
            subtypeMap[label]?.let { return it }
        }

        return null
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

    /**
     * Registers an abstract type mapping with Jackson's ObjectMapper.
     * This enables Jackson to deserialize the abstract/interface type to the concrete implementation
     * when using treeToValue() in SessionManager.getSnapshot().
     *
     * Only registers if the base class is actually abstract (interface or abstract class),
     * as Jackson's addAbstractTypeMapping() requires an abstract base type.
     *
     * Uses a set to track already-registered mappings to avoid redundant module registrations.
     */
    @Suppress("UNCHECKED_CAST")
    private fun registerWithJackson(baseClass: Class<*>, subClass: Class<*>) {
        // Only register with Jackson if base class is abstract (interface or abstract class)
        if (!baseClass.isInterface && !java.lang.reflect.Modifier.isAbstract(baseClass.modifiers)) {
            return
        }

        val mappingKey = "${baseClass.name}->${subClass.name}"
        if (jacksonMappings.add(mappingKey)) {
            Neo4jObjectMapper.instance.registerModule(
                SimpleModule().addAbstractTypeMapping(
                    baseClass as Class<Any>,
                    subClass as Class<Any>
                )
            )
        }
    }

    companion object {
        /**
         * Converts a list of labels to a composite key.
         * Labels are sorted alphabetically and joined with comma.
         *
         * Example: ["WebUser", "Anonymous"] -> "Anonymous,WebUser"
         */
        fun labelsToKey(labels: List<String>): String {
            return labels.sorted().joinToString(",")
        }
    }
}