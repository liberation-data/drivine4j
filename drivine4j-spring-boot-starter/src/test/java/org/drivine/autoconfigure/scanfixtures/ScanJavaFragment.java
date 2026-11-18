package org.drivine.autoconfigure.scanfixtures;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.RangeIndex;

/** A Java fragment, found and read as a Kotlin one is. */
@NodeFragment(labels = {"ScanJava"})
public class ScanJavaFragment {
    @NodeId
    public String id;

    @RangeIndex
    public String code;
}
