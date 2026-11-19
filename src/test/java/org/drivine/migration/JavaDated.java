package org.drivine.migration;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.RelationshipFragment;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/** A Java node fragment and relationship fragment whose fields a store layout migration is declared from (drivine4j#832). */
public final class JavaDated {

    private JavaDated() { }

    @NodeFragment(labels = {"JavaDated"})
    public static final class Node {
        @NodeId
        private String id;
        private LocalDate born;
        private List<LocalDate> days;
        private double weight;
        private Date stamped;
        private String name;

        public String getId() { return id; }
        public LocalDate getBorn() { return born; }
        public List<LocalDate> getDays() { return days; }
        public double getWeight() { return weight; }
        public Date getStamped() { return stamped; }
        public String getName() { return name; }
    }

    @RelationshipFragment
    public static final class Link {
        private LocalDate since;
        private List<LocalDate> days;
        private double weight;
        private Date stamped;
        private String note;
        private Node target;

        public LocalDate getSince() { return since; }
        public List<LocalDate> getDays() { return days; }
        public double getWeight() { return weight; }
        public Date getStamped() { return stamped; }
        public String getNote() { return note; }
        public Node getTarget() { return target; }
    }
}
