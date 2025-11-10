package org.drivine.connection

import org.drivine.query.QuerySpecification

interface Connection {

    fun sessionId(): String

    fun <T: Any> query(spec: QuerySpecification<T>): List<T>

//    fun <T> openCursor(cursorSpec: CursorSpecification<T>): Cursor<T>

    fun startTransaction()

    fun commitTransaction()

    fun rollbackTransaction()

    fun release(err: Throwable? = null)

}
