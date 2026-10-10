package sample.stateless;

import java.util.List;
import org.drivine.annotation.Aggregate;
import org.drivine.annotation.AggregateFunction;
import org.drivine.annotation.Count;
import org.drivine.annotation.Direction;
import org.drivine.annotation.GraphPath;
import org.drivine.annotation.GraphRelationship;
import org.drivine.annotation.GraphView;
import org.drivine.annotation.Hop;
import org.drivine.annotation.ReadOnly;
import org.drivine.annotation.Root;

/**
 * A Java view whose path, count and aggregate fields are not declared read-only, beside a
 * relationship field that is written and one that is declared read-only.
 */
@GraphView
public class JavaClaimSummary {
    @Root
    public Claim claim;

    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    public List<Human> people;

    @ReadOnly
    @GraphRelationship(type = "REVIEWED_BY", direction = Direction.OUTGOING)
    public List<Human> reviewers;

    @GraphPath(hops = {
        @Hop(type = "MENTIONS", direction = Direction.OUTGOING, label = "Human"),
        @Hop(type = "WORKS_AT", direction = Direction.OUTGOING),
    })
    public List<Company> employers;

    @Count(type = "MENTIONS")
    public long mentionCount;

    @Aggregate(function = AggregateFunction.AVG, type = "MENTIONS", property = "weight")
    public double averageWeight;

    public JavaClaimSummary() {}
}
