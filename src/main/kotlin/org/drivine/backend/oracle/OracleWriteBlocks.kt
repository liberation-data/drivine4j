package org.drivine.backend.oracle

import org.drivine.backend.sql.NodeShape
import org.drivine.backend.sql.WriteBlocks
import org.drivine.backend.sql.WriteReturns

/** Oracle carries out a plan by a PL/SQL block written for the shapes it names: see [OracleWriteBlock]. */
internal class OracleWriteBlocks(private val graph: OracleGraph) : WriteBlocks {

    override fun statement(shapes: List<NodeShape>, returns: WriteReturns, patchedIf: String?): String =
        OracleWriteBlock(graph, shapes, OracleWriteBlock.Returns.valueOf(returns.name), patchedIf).text()
}
