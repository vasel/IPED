package iped.engine.webapi.json;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON representation of a graph node.
 */
@Schema(name = "GraphNode")
public class GraphNodeJSON {
    private long id;
    private List<String> labels;
    private Map<String, Object> properties;
    private String source;

    public GraphNodeJSON() {
    }

    public GraphNodeJSON(long id, List<String> labels, Map<String, Object> properties, String source) {
        this.id = id;
        this.labels = labels;
        this.properties = properties;
        this.source = source;
    }

    @Schema(description = "Node ID in the graph database")
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Schema(description = "List of labels assigned to this node")
    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    @Schema(description = "Node properties as key-value pairs")
    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    @Schema(description = "Source case identifier")
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
