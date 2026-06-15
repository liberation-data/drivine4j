package sample.propertybag;

import org.drivine.annotation.NodeFragment;
import org.drivine.annotation.NodeId;
import org.drivine.annotation.PropertyBag;

import java.util.Map;

/** Java fragment fixture with a @PropertyBag map — exercises the Java-reflection detection path. */
@NodeFragment(labels = {"JavaBagged"})
public class JavaBaggedNode {

    @NodeId
    private String id;
    private String title;

    @PropertyBag
    private Map<String, Object> metadata;

    public JavaBaggedNode() {
    }

    public JavaBaggedNode(String id, String title, Map<String, Object> metadata) {
        this.id = id;
        this.title = title;
        this.metadata = metadata;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}