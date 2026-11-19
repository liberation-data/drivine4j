package org.drivine.oracle

import org.drivine.manager.IdKindContract
import org.drivine.manager.NonTransactionalPersistenceManager

/** A `@NodeId` held as a temporal tag, read by each declared id, on Oracle: see [IdKindContract]. */
@OracleTests
class OracleIdKindTest : IdKindContract() {
    override val pm: NonTransactionalPersistenceManager = OracleTestDatabase.graphManager("idkind")
}
