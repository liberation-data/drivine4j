package org.drivine.annotation

/**
 * Marks the field of a `@NodeFragment` that holds the node's stamp: a value every object-manager
 * save replaces. Loading fills the field. A save of an object whose stamp is not null applies only
 * if the node still carries that stamp, and otherwise throws; a save of an object whose stamp is
 * null is not checked.
 *
 * The field is a nullable `String`. The stamp is stored once per node, whatever the field is called.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeStamp
