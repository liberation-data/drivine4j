package org.drivine.mapper

import kotlin.reflect.KProperty1

/**
 * Helper class for sorting collections based on property paths.
 * Supports nested property paths using dot notation (e.g., "person.name").
 */
internal class CollectionSorter {

    /**
     * Sorts a collection based on a property path.
     *
     * @param collection The collection to sort
     * @param propertyPath The property path to sort by (e.g., "name" or "person.name")
     * @param ascending True for ascending order, false for descending
     * @return The sorted collection
     */
    fun sort(collection: List<*>, propertyPath: String, ascending: Boolean): List<*> {
        val comparator = Comparator<Any?> { a, b ->
            val valueA = getNestedPropertyValue(a, propertyPath)
            val valueB = getNestedPropertyValue(b, propertyPath)
            compareValues(valueA, valueB)
        }

        val sorted = collection.sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    /**
     * Gets a nested property value from an object using a dot-separated path.
     * For example, "person.name" would get obj.person.name.
     */
    private fun getNestedPropertyValue(obj: Any?, propertyPath: String): Comparable<*>? {
        if (obj == null) return null

        var current: Any? = obj
        val parts = propertyPath.split(".")

        for (part in parts) {
            if (current == null) return null
            current = extractProperty(current, part)
        }

        return current as? Comparable<*>
    }

    /**
     * Extracts a single property from an object.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractProperty(obj: Any, propertyName: String): Any? {
        return try {
            val kClass = obj::class
            val property = kClass.members.find { it.name == propertyName }
            if (property is KProperty1<*, *>) {
                (property as KProperty1<Any, *>).get(obj)
            } else {
                // Fall back to Java reflection for Java classes
                val field = obj.javaClass.getDeclaredField(propertyName)
                field.isAccessible = true
                field.get(obj)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compares two Comparable values, handling nulls.
     * Nulls are sorted to the end.
     */
    @Suppress("UNCHECKED_CAST")
    private fun compareValues(a: Comparable<*>?, b: Comparable<*>?): Int {
        return when {
            a == null && b == null -> 0
            a == null -> 1  // nulls go to end
            b == null -> -1
            else -> (a as Comparable<Any>).compareTo(b)
        }
    }
}