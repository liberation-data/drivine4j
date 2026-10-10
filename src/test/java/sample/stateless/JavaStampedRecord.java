package sample.stateless;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.NodeStamp;

/** A stamped node as a Java record: its fields cannot be set, and it is not a Kotlin data class. */
@NodeFragment(labels = {"Recorded"})
public record JavaStampedRecord(@NodeId String id, String text, @NodeStamp String stamp) {
}
