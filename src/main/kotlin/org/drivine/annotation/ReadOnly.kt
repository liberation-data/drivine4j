package org.drivine.annotation

/**
 * Marks a field of a `@GraphView` that is loaded and never written. A save skips it: no
 * relationship is written for it and the nodes it holds are not saved. Naming it in a `Replace`
 * is an error.
 *
 * On a `@GraphRelationship` field it is a choice. On a `@GraphPath`, `@Count` or `@Aggregate` field
 * it is required, because none of them names a single relationship that a save could write. To
 * write along a path, relate the nodes, run Cypher, or save a view rooted where the hop starts.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReadOnly
