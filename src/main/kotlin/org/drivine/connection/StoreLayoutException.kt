package org.drivine.connection

import org.drivine.DrivineException

/**
 * A graph whose store layout this version of Drivine does not read: written in an earlier layout and holding values, written
 * by a later Drivine, or stamped by two at once (liberation-data/drivine-cypher-spec#161). The compiler's module decides
 * (`StoreLayout.verdict`), and [message] says which, and what to do: reload the graph with this version, or upgrade.
 * Thrown on every connection, as no statement may run over a store whose values are held otherwise.
 */
class StoreLayoutException internal constructor(
    /** The graph refused. */
    val graph: String,
    message: String,
) : DrivineException(message)
