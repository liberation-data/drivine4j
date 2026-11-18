package org.drivine.autoconfigure.scanconflict

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.RangeIndex

/** Two fragments of one label that name one index differently: a conflict the scan passes over. */
@NodeFragment(labels = ["ScanClash"])
@RangeIndex(properties = ["name"], name = "clash_one")
interface ScanClashOne

@NodeFragment(labels = ["ScanClash"])
@RangeIndex(properties = ["name"], name = "clash_two")
interface ScanClashTwo

/** A fragment beside them that conflicts with neither: applied. */
@NodeFragment(labels = ["ScanCalm"])
@RangeIndex(properties = ["name"])
interface ScanCalm
