package org.drivine.postgres

import org.drivine.manager.IdKindContract
import org.drivine.manager.NonTransactionalPersistenceManager

/** A `@NodeId` held as a temporal tag, read by each declared id, on Postgres: see [IdKindContract]. */
@PostgresTests
class PostgresIdKindTest : IdKindContract() {
    override val pm: NonTransactionalPersistenceManager = PostgresTestDatabase.graphManager("idkind")
}
