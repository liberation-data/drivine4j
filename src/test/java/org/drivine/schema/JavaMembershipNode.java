package org.drivine.schema;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.RangeIndex;
import org.drivine.annotation.Unique;

/**
 * Java fragment fixture — exercises the Java-reflection path of fragment schema scanning
 * (class-level composite declarations plus field-level annotations on a plain Java class).
 */
@NodeFragment(labels = {"Membership"})
@Unique(properties = {"tenantId", "userId"})
public class JavaMembershipNode {

    @NodeId
    @Unique
    private String id;

    @RangeIndex
    private String tenantId;

    private String userId;

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }
}