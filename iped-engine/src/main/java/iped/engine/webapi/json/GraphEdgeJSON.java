package iped.engine.webapi.json;

import java.util.Map;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * JSON representation of a graph edge (relationship).
 */
@ApiModel(value = "GraphEdge")
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

    @ApiModelProperty(value = "Edge ID in the graph database")
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @ApiModelProperty(value = "Relationship type name")
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @ApiModelProperty(value = "ID of the source node")
    public long getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(long sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    @ApiModelProperty(value = "ID of the target node")
    public long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    @ApiModelProperty(value = "Edge properties as key-value pairs")
    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    @ApiModelProperty(value = "Source case identifier")
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
