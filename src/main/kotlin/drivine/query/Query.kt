package drivine.query

import org.springframework.beans.factory.annotation.Qualifier

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Suppress("UNCHECKED_CAST")
@Qualifier
annotation class Query(val path: String)
