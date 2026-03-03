package iped.engine.webapi.json;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON representation of a graph edge (relationship).
 */
@Schema(name = "GraphEdge")
public class GraphEdgeJSON {
    private long id;
    private String type;
    private long sourceNodeId;
    private long targetNodeId;
    private Map<String, Object> properties;
    private String source;

    public GraphEdgeJSON() {
    }

    public GraphEdgeJSON(long id, String type, long sourceNodeId, long targetNodeId,
            Map<String, Object> properties, String source) {
        this.id = id;
        this.type = type;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.properties = properties;
        this.source = source;
    }

    @Schema(description = "Edge ID in the graph database")
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Schema(description = "Relationship type name")
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Schema(description = "ID of the source node")
    public long getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(long sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    @Schema(description = "ID of the target node")
    public long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    @Schema(description = "Edge properties as key-value pairs")
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
