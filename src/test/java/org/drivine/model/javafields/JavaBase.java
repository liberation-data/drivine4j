package org.drivine.model.javafields;

import org.drivine.annotation.GraphProperty;
import org.drivine.annotation.GraphTransient;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.Unique;

/** A Java base class that keeps its fields private, as Java classes do, behind getters and setters. */
public abstract class JavaBase {
    public static final String KIND = "base";

    @NodeId
    @Unique
    private String id;

    @GraphProperty("created_on")
    private String createdOn;

    @GraphTransient
    private String cache;

    /** Declared here, and again by the subclass, whose declaration is the one that counts. */
    private String shadowed;

    /** Declared here, and again by the subclass as transient, which hides this one: neither is stored. */
    private String hidden;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCreatedOn() { return createdOn; }
    public void setCreatedOn(String createdOn) { this.createdOn = createdOn; }
    public String getCache() { return cache; }
    public void setCache(String cache) { this.cache = cache; }
}
