package org.drivine.annotation

/**
 * Marks the field of a `@NodeFragment` that holds the node's stamp: a value an object-manager save
 * replaces when it changes the node. Loading fills the field. A save of an object whose stamp is not null applies only
 * if the node still carries that stamp, and otherwise throws; a save of an object whose stamp is
 * null is not checked.
 *
 * The field is a nullable `String` and carries no other mapping annotation: the stamp is stored once
 * per node under a property of its own, whatever the field is called.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeStamp
