package org.drivine.annotation

/**
 * Falls back to the property's declared default when the graph value is absent or null.
 *
 * Loading a node whose property is missing surfaces that property as `null`; for a non-nullable
 * type that fails. Annotate the property with `@Default` to instead use its declared default:
 *
 * ```kotlin
 * @NodeFragment(labels = ["User"])
 * data class UserNode(
 *     @NodeId val id: String,
 *     @Default val roles: List<String> = emptyList(),  // missing/null → []
 *     @Default val status: String = "active",          // missing/null → "active"
 * )
 * ```
 *
 * The default value lives where it is type-safe — the property's own default — so it works for any
 * type. The "declared default" is a Kotlin constructor default (`= emptyList()`) or a Java field
 * initializer (`= new ArrayList<>()`) on a bean/POJO. A provided (non-null) value always wins.
 *
 * Java records have no field initializer for `@Default` to fall back on (their state is defined
 * solely by their components), so use [EmptyWhenAbsent] for collection/map components there.
 *
 * @see EmptyWhenAbsent
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Default