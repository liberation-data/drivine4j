package sample.stateless;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.NodeStamp;

/** A stamped node as a Java class with setters declares it: a save gives it its stamp in place. */
@NodeFragment(labels = {"JavaNote"})
public class JavaNote {
    @NodeId
    private String id;
    private String text;
    @NodeStamp
    private String stamp;

    public JavaNote() {}

    public JavaNote(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getStamp() { return stamp; }
    public void setStamp(String stamp) { this.stamp = stamp; }
}
