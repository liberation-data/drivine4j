package org.drivine.sqlite

import org.drivine.manager.IdKindContract
import org.drivine.manager.NonTransactionalPersistenceManager

/** A `@NodeId` held as a temporal tag, read by each declared id, on SQLite: see [IdKindContract]. No container. */
class SqliteIdKindTest : IdKindContract() {
    override val pm: NonTransactionalPersistenceManager = SqliteTestDatabase.graphManager("idkind")
}
