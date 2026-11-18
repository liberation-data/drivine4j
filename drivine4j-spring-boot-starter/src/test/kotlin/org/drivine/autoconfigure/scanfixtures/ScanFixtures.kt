package org.drivine.autoconfigure.scanfixtures

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.TextIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex

/** A fragment class, found by the scan of its package. */
@NodeFragment(labels = ["ScanPerson"])
data class ScanPerson(
    @NodeId
    val id: String,
    @RangeIndex
    val name: String,
)

/** A fragment interface, found too. */
@NodeFragment(labels = ["ScanAccount"])
@Unique(properties = ["code"])
interface ScanAccount {
    val code: String
}

/** A fragment with a vector index, read only with a dimension provider. */
@NodeFragment(labels = ["ScanChunk"])
data class ScanChunk(
    @NodeId
    val id: String,
    @VectorIndex
    val embedding: List<Float>?,
)

/** No fragment: not scanned. */
data class ScanPlain(val id: String)

/** No fragment itself, but it holds one. */
class ScanHolder {
    /** A nested fragment, found too, with a TEXT index, which only Neo4j makes. */
    @NodeFragment(labels = ["ScanNested"])
    data class Nested(
        @NodeId
        val id: String,
        @TextIndex
        val title: String,
    )
}
