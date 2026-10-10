package org.drivine.annotation

/**
 * Marks the field of a `@NodeFragment` that holds the node's stamp. Loading fills the field. A save
 * of an object whose stamp is not null applies only if what it would overwrite is as the stamp says,
 * and otherwise throws; a save of an object whose stamp is null is not checked.
 *
 * The stamp is two random tokens. The first, the data token, is replaced when a save changes the
 * node's properties or labels, and every checked save compares it. The second, the relationship
 * token, is replaced when one of the node's relationships is added or removed, or its properties
 * change, from either end, and a save that replaces a relationship list compares it too.
 *
 * The field is a nullable `String` and carries no other mapping annotation: the stamp is stored once
 * per node under a property of its own, whatever the field is called. A Java field or record
 * component of type `String` serves. A fragment has at most one, and the annotation is refused on a
 * `@GraphView` and on a `@RelationshipFragment`: a stamp is a node's.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeStamp
