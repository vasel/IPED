package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON wrapper for graph query results containing nodes and edges.
 */
@Schema(name = "GraphResult")
public class GraphResultJSON {
    private List<GraphNodeJSON> nodes;
    private List<GraphEdgeJSON> edges;

    public GraphResultJSON() {
    }

    public GraphResultJSON(List<GraphNodeJSON> nodes, List<GraphEdgeJSON> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @Schema(description = "List of nodes in the result")
    public List<GraphNodeJSON> getNodes() {
        return nodes;
    }

    public void setNodes(List<GraphNodeJSON> nodes) {
        this.nodes = nodes;
    }

    @Schema(description = "List of edges in the result")
    public List<GraphEdgeJSON> getEdges() {
        return edges;
    }

    public void setEdges(List<GraphEdgeJSON> edges) {
        this.edges = edges;
    }
}
