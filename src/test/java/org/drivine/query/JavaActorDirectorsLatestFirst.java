package org.drivine.query;

import java.util.List;
import org.drivine.annotation.Direction;
import org.drivine.annotation.GraphPath;
import org.drivine.annotation.GraphView;
import org.drivine.annotation.Hop;
import org.drivine.annotation.ReadOnly;
import org.drivine.annotation.Root;

/** A Java view of the directors an actor worked with, in the order of the latest movie that led to each. */
@GraphView
public class JavaActorDirectorsLatestFirst {
    @Root
    public PActor actor;

    @ReadOnly
    @GraphPath(hops = {
        @Hop(type = "ACTED_IN", direction = Direction.OUTGOING, label = "PMovie", orderBy = "year", ascending = false),
        @Hop(type = "DIRECTED_BY", direction = Direction.OUTGOING),
    })
    public List<PDirector> directors;

    public JavaActorDirectorsLatestFirst() {}
}
