package org.drivine.query

import java.util.UUID

/**
 * The @NodeId types a graph engine holds as text, as [org.drivine.mapper.convertValueForNeo4j] writes them: a `String` as
 * itself, a UUID as its text, an enum as its name. Every other kind it writes as the value it is (a number, a truth value,
 * a temporal), which a search among ids, given each id's text, can't find (liberation-data/drivine-cypher-spec#422).
 * `GraphIdTextTest` holds this to what the writer does, so the two can't drift apart.
 */
internal object GraphIdText {

    /** Whether a graph engine holds an id of [type] as text. */
    fun isText(type: Class<*>): Boolean =
        type == String::class.java || type == UUID::class.java || type.isEnum
}
