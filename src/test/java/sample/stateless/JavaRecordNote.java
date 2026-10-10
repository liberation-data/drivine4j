package sample.stateless;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.NodeStamp;

/** A stamped node as a Java record declares it: its fields cannot be set, so a save returns a copy with its stamp. */
@NodeFragment(labels = {"JavaRecordNote"})
public record JavaRecordNote(@NodeId String id, String text, @NodeStamp String stamp) {}
