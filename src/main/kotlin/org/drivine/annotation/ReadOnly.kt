package org.drivine.annotation

/**
 * Marks a field of a `@GraphView` that is loaded and never written. A save skips it: no
 * relationship is written for it and the nodes it holds are not saved. Naming it in a `Replace`
 * is an error.
 *
 * On a `@GraphRelationship` field it is a choice. A `@GraphPath`, `@Count` or `@Aggregate` field is
 * read-only with or without it, because none of them names a single relationship that a save could
 * write; there the annotation is allowed and changes nothing. To write along a path, relate the
 * nodes, run Cypher, or save a view rooted where the hop starts.
 *
 * On any other field, a view's `@Root` or a property of a fragment, it has no effect.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReadOnly
