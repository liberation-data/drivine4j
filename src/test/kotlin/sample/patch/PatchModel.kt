package sample.patch

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/** A fragment whose id may be null, for the refusal of a patch that has no node to find. */
@NodeFragment(labels = ["LooseNote"])
data class LooseNote(@NodeId val id: String?, val text: String? = null)
