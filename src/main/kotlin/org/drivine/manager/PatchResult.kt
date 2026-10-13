package org.drivine.manager

/**
 * What a patch did to one node. It carries flags and no stamp: a patch holds nothing afterwards, so
 * there is no object to hand a stamp to.
 *
 * @param found whether a node with the id was there. A patch never makes one
 * @param applied whether the patch was applied to the node. A patch with no condition is applied to
 *   every node it finds, so this is then [found]
 * @param changed whether a value the patch wrote differed from what the node held. A node that
 *   changed has a new stamp; one whose values were already as set keeps the stamp it had
 */
data class PatchResult(val found: Boolean, val applied: Boolean, val changed: Boolean) {
    internal companion object {
        /** The result for an id no node has. */
        val ABSENT = PatchResult(found = false, applied = false, changed = false)
    }
}
