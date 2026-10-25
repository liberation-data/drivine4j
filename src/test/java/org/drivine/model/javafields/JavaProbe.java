package org.drivine.model.javafields;

import org.drivine.annotation.GraphProperty;
import org.drivine.annotation.GraphTransient;
import org.drivine.annotation.NodeFragment;

@NodeFragment(labels = {"JavaProbe"})
public final class JavaProbe extends JavaBase {
    private String name;

    @GraphProperty("shadow")
    private String shadowed;

    @GraphTransient
    private String hidden;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getShadowed() { return shadowed; }
    public void setShadowed(String shadowed) { this.shadowed = shadowed; }
}
