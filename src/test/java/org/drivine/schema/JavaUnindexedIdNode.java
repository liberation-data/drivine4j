package org.drivine.schema;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;

/** Java fragment fixture whose {@code @NodeId} opts out of the range index it is given by default (#827). */
@NodeFragment(labels = {"JavaAccount"}, nodeIdIndex = false)
public class JavaUnindexedIdNode {

    @NodeId
    private String id;

    public String getId() {
        return id;
    }
}
