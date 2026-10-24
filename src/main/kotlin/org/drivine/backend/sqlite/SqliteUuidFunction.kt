package org.drivine.backend.sqlite

import org.sqlite.Function
import java.util.UUID

/** `drivine_uuid()`: a new random UUID, a version 4 one, as text: Cypher's `randomUUID()`. */
internal class SqliteUuidFunction : Function() {

    override fun xFunc() = result(UUID.randomUUID().toString())
}
