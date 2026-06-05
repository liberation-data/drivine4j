package org.drivine.annotation

/**
 * Deserializes an absent or null collection/map property to an empty collection/map, rather than
 * `null` (or a failure on a non-nullable type).
 *
 * Unlike [Default], this needs no declared default value — it always yields an empty collection —
 * which makes it the tool for **Java records**. A record's state is defined solely by its
 * components, so there is no field initializer or constructor default for [Default] to fall back on:
 *
 * ```java
 * @NodeFragment(labels = {"User"})
 * public record UserNode(
 *     @NodeId String id,
 *     @EmptyWhenAbsent List<String> roles   // missing/null → []
 * ) {}
 * ```
 *
 * Applies to collection and map types only. For scalars, or to use a specific declared default,
 * use [Default]. (The pure-Java alternative is to normalize in the record's compact constructor —
 * `roles = roles == null ? List.of() : roles;` — this annotation is the Drivine-native equivalent.)
 *
 * @see Default
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class EmptyWhenAbsent