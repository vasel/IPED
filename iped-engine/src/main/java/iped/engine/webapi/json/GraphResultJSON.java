package iped.engine.webapi.json;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * JSON wrapper for graph query results containing nodes and edges.
 */
@ApiModel(value = "GraphResult")
public class GraphResultJSON {
    private List<GraphNodeJSON> nodes;
    private List<GraphEdgeJSON> edges;

    public GraphResultJSON() {
    }

    public GraphResultJSON(List<GraphNodeJSON> nodes, List<GraphEdgeJSON> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @ApiModelProperty(value = "List of nodes in the result")
    public List<GraphNodeJSON> getNodes() {
        return nodes;
    }

    public void setNodes(List<GraphNodeJSON> nodes) {
        this.nodes = nodes;
    }

    @ApiModelProperty(value = "List of edges in the result")
    public List<GraphEdgeJSON> getEdges() {
        return edges;
    }

    public void setEdges(List<GraphEdgeJSON> edges) {
        this.edges = edges;
    }
}
