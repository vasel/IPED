package iped.engine.webapi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.engine.graph.ConnectionQueryListener;
import iped.engine.graph.EdgeQueryListener;
import iped.engine.graph.GraphService;
import iped.engine.graph.GraphTask;
import iped.engine.graph.LabelQueryListener;
import iped.engine.graph.NodeEdgeQueryListener;
import iped.engine.graph.NodeQueryListener;
import iped.engine.graph.PathQueryListener;
import iped.engine.graph.RelationshipTypeQueryListener;
import iped.engine.webapi.json.DataListJSON;
import iped.engine.webapi.json.GraphEdgeJSON;
import iped.engine.webapi.json.GraphLabelJSON;
import iped.engine.webapi.json.GraphNodeJSON;
import iped.engine.webapi.json.GraphRelationshipTypeJSON;
import iped.engine.webapi.json.GraphResultJSON;

/**
 * REST API endpoint for graph operations.
 */
@Tag(name = "Graph")
@Path("graph")
public class Graph {

    private static final int DEFAULT_MAX_RESULTS = 100;

    @Operation(summary = "Get graph status")
    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        boolean enabled = MultiCaseGraphLoader.isEnabled();
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", enabled);
        status.put("available", MultiCaseGraphLoader.getGraphService() != null);
        return Response.ok(status).build();
    }

    @Operation(summary = "List all available labels")
    @GET
    @Path("labels")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphLabelJSON> getLabels() {
        GraphService gs = getService();
        List<GraphLabelJSON> labels = new ArrayList<>();

        gs.findLabels(new LabelQueryListener() {
            @Override
            public void labelFound(String label) {
                labels.add(new GraphLabelJSON(label, countNodesWithLabel(gs, label)));
            }
        });

        return new DataListJSON<>(labels);
    }

    @Operation(summary = "List all available relationship types (edge types)")
    @GET
    @Path("edge-types")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphRelationshipTypeJSON> getEdgeTypes() {
        GraphService gs = getService();
        List<GraphRelationshipTypeJSON> types = new ArrayList<>();

        gs.findRelationshipTypes(new RelationshipTypeQueryListener() {
            @Override
            public void relationshipTypeFound(String relationshipType) {
                types.add(new GraphRelationshipTypeJSON(relationshipType, countEdgesWithType(gs, relationshipType)));
            }
        });

        return new DataListJSON<>(types);
    }

    @Operation(summary = "Get a node by ID")
    @GET
    @Path("nodes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public GraphNodeJSON getNode(@PathParam("id") long id) {
        GraphService gs = getService();
        List<GraphNodeJSON> result = new ArrayList<>();

        gs.getNodes(List.of(id), new NodeQueryListener() {
            @Override
            public boolean nodeFound(Node node) {
                result.add(convertNode(node));
                return true;
            }
        });

        if (result.isEmpty()) {
            throw new RuntimeException("Node not found: " + id);
        }

        return result.get(0);
    }

    @Operation(summary = "Get multiple nodes by IDs")
    @POST
    @Path("nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphNodeJSON> getNodes(
            @Parameter(description = "List of node IDs", required = true) List<String> stringIds) {
        GraphService gs = getService();
        List<GraphNodeJSON> nodes = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        
        if (stringIds != null) {
            for (String idStr : stringIds) {
                try {
                    ids.add(Long.parseLong(idStr));
                } catch (NumberFormatException e) {
                    // Ignore non-numeric IDs like frontend-generated UUIDs
                }
            }
        }

        if (!ids.isEmpty()) {
            gs.getNodes(ids, new NodeQueryListener() {
                @Override
                public boolean nodeFound(Node node) {
                    nodes.add(convertNode(node));
                    return true;
                }
            });
        }

        return new DataListJSON<>(nodes);
    }

    @Operation(summary = "Get neighbours of a node")
    @GET
    @Path("nodes/{id}/neighbours")
    @Produces(MediaType.APPLICATION_JSON)
    public GraphResultJSON getNeighbours(
            @PathParam("id") long id,
            @QueryParam("maxResults") @DefaultValue("100") int maxResults) {
        GraphService gs = getService();
        List<GraphNodeJSON> nodes = new ArrayList<>();
        List<GraphEdgeJSON> edges = new ArrayList<>();

        gs.getNeighbours(id, new NodeEdgeQueryListener() {
            int count = 0;

            @Override
            public boolean nodeFound(Node node) {
                if (count >= maxResults)
                    return false;
                nodes.add(convertNode(node));
                count++;
                return true;
            }

            @Override
            public boolean edgeFound(Relationship edge) {
                edges.add(convertEdge(edge));
                return count < maxResults;
            }
        }, maxResults);

        return new GraphResultJSON(nodes, edges);
    }

    @Operation(summary = "Get connection types for a node")
    @GET
    @Path("nodes/{id}/connections")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConnections(@PathParam("id") long id) {
        GraphService gs = getService();
        Map<String, Long> connections = new HashMap<>();

        gs.findConnections(id, new ConnectionQueryListener() {
            @Override
            public void connectionsFound(String label, int quantity) {
                connections.put(label, (long) quantity);
            }
        });

        return Response.ok(connections).build();
    }

    @Operation(summary = "Search nodes by text")
    @GET
    @Path("search")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphNodeJSON> search(
            @QueryParam("q") String query,
            @QueryParam("maxResults") @DefaultValue("100") int maxResults) {
        GraphService gs = getService();
        List<GraphNodeJSON> nodes = new ArrayList<>();

        gs.search(query, new NodeQueryListener() {
            int count = 0;

            @Override
            public boolean nodeFound(Node node) {
                if (count >= maxResults)
                    return false;
                nodes.add(convertNode(node));
                count++;
                return true;
            }
        });

        return new DataListJSON<>(nodes);
    }

    @Operation(summary = "Search nodes by label (node type)")
    @GET
    @Path("nodes/by-label/{label}")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphNodeJSON> searchByLabel(
            @PathParam("label") String label,
            @QueryParam("maxResults") @DefaultValue("100") int maxResults) {
        GraphService gs = getService();
        List<GraphNodeJSON> nodes = new ArrayList<>();

        gs.searchByLabel(label, new NodeQueryListener() {
            @Override
            public boolean nodeFound(Node node) {
                nodes.add(convertNode(node));
                return nodes.size() < maxResults;
            }
        }, maxResults);

        return new DataListJSON<>(nodes);
    }

    @Operation(summary = "Search edges by relationship type (edge type)")
    @GET
    @Path("edges/by-type/{type}")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphEdgeJSON> searchByEdgeType(
            @PathParam("type") String type,
            @QueryParam("maxResults") @DefaultValue("100") int maxResults) {
        GraphService gs = getService();
        List<GraphEdgeJSON> edges = new ArrayList<>();

        gs.searchByRelationshipType(type, new EdgeQueryListener() {
            @Override
            public boolean edgeFound(Relationship edge) {
                edges.add(convertEdge(edge));
                return edges.size() < maxResults;
            }
        }, maxResults);

        return new DataListJSON<>(edges);
    }

    @Operation(summary = "Find paths between two nodes")
    @POST
    @Path("paths")
    @Produces(MediaType.APPLICATION_JSON)
    public GraphResultJSON findPaths(
            @Parameter(description = "Path request containing source, target, and maxDistance") PathRequest request) {
        GraphService gs = getService();
        Set<GraphNodeJSON> nodesSet = new HashSet<>();
        List<GraphEdgeJSON> edges = new ArrayList<>();

        gs.getPaths(request.source, request.target, request.maxDistance, new PathQueryListener() {
            @Override
            public boolean pathFound(org.neo4j.graphdb.Path path) {
                for (Node node : path.nodes()) {
                    nodesSet.add(convertNode(node));
                }
                for (Relationship rel : path.relationships()) {
                    edges.add(convertEdge(rel));
                }
                return true;
            }
        });

        return new GraphResultJSON(new ArrayList<>(nodesSet), edges);
    }

    @Operation(summary = "Get most connected nodes")
    @GET
    @Path("top-connected")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<GraphNodeJSON> getTopConnected(
            @QueryParam("maxResults") @DefaultValue("50") int maxResults) {
        GraphService gs = getService();
        List<Long> topIds = gs.getMoreConnectedNodes(maxResults);
        List<GraphNodeJSON> nodes = new ArrayList<>();

        gs.getNodes(topIds, new NodeQueryListener() {
            @Override
            public boolean nodeFound(Node node) {
                nodes.add(convertNode(node));
                return true;
            }
        });

        return new DataListJSON<>(nodes);
    }

    @Operation(summary = "Execute a Cypher query")
    @POST
    @Path("cypher")
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeCypher(
            @Parameter(description = "Cypher query request", required = true) CypherRequest request) {
        GraphService gs = getService();
        
        // Validate query - only allow read operations
        String query = request.query != null ? request.query.trim() : "";
        if (query.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query cannot be empty"))
                    .build();
        }
        
        // Block write operations for security
        String upperQuery = query.toUpperCase();
        if (upperQuery.contains("CREATE") || upperQuery.contains("DELETE") || 
            upperQuery.contains("SET") || upperQuery.contains("REMOVE") ||
            upperQuery.contains("MERGE") || upperQuery.contains("DROP") ||
            upperQuery.contains("DETACH")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Write operations are not allowed. Only read queries (MATCH, RETURN) are permitted."))
                    .build();
        }
        
        List<GraphNodeJSON> nodes = new ArrayList<>();
        List<GraphEdgeJSON> edges = new ArrayList<>();
        List<Map<String, Object>> rawResults = new ArrayList<>();
        
        try (Transaction tx = gs.getGraphDb().beginTx()) {
            Map<String, Object> params = request.parameters != null ? request.parameters : Map.of();
            Result result = tx.execute(query, params);
            
            int count = 0;
            int maxResults = request.maxResults > 0 ? request.maxResults : DEFAULT_MAX_RESULTS;
            
            while (result.hasNext() && count < maxResults) {
                Map<String, Object> row = result.next();
                Map<String, Object> processedRow = new HashMap<>();
                
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    Object value = entry.getValue();
                    
                    if (value instanceof Node) {
                        Node node = (Node) value;
                        nodes.add(convertNode(node));
                        processedRow.put(entry.getKey(), Map.of("_type", "node", "id", node.getId()));
                    } else if (value instanceof Relationship) {
                        Relationship rel = (Relationship) value;
                        edges.add(convertEdge(rel));
                        processedRow.put(entry.getKey(), Map.of("_type", "edge", "id", rel.getId()));
                    } else if (value instanceof org.neo4j.graphdb.Path) {
                        org.neo4j.graphdb.Path path = (org.neo4j.graphdb.Path) value;
                        for (Node node : path.nodes()) {
                            nodes.add(convertNode(node));
                        }
                        for (Relationship rel : path.relationships()) {
                            edges.add(convertEdge(rel));
                        }
                        processedRow.put(entry.getKey(), Map.of("_type", "path", "length", path.length()));
                    } else {
                        processedRow.put(entry.getKey(), value);
                    }
                }
                
                rawResults.add(processedRow);
                count++;
            }
            
            tx.commit();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query execution failed: " + e.getMessage()))
                    .build();
        }
        
        // Remove duplicates from nodes and edges
        Set<Long> seenNodeIds = new HashSet<>();
        List<GraphNodeJSON> uniqueNodes = new ArrayList<>();
        for (GraphNodeJSON node : nodes) {
            if (seenNodeIds.add(node.getId())) {
                uniqueNodes.add(node);
            }
        }
        
        Set<Long> seenEdgeIds = new HashSet<>();
        List<GraphEdgeJSON> uniqueEdges = new ArrayList<>();
        for (GraphEdgeJSON edge : edges) {
            if (seenEdgeIds.add(edge.getId())) {
                uniqueEdges.add(edge);
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("nodes", uniqueNodes);
        response.put("edges", uniqueEdges);
        response.put("results", rawResults);
        response.put("count", rawResults.size());
        
        return Response.ok(response).build();
    }

    // Helper methods

    private GraphService getService() {
        GraphService gs = MultiCaseGraphLoader.getGraphService();
        if (gs == null) {
            throw new RuntimeException("Graph service not available. Start server with --enable-graph option.");
        }
        return gs;
    }

    private long countNodesWithLabel(GraphService gs, String label) {
        try (Transaction tx = gs.getGraphDb().beginTx()) {
            Result result = tx.execute(
                    "MATCH (n) WHERE $label IN labels(n) RETURN count(n) AS count",
                    Map.of("label", label));
            Object count = result.hasNext() ? result.next().get("count") : 0L;
            tx.commit();
            return (count instanceof Number) ? ((Number) count).longValue() : 0L;
        }
    }

    private long countEdgesWithType(GraphService gs, String type) {
        try (Transaction tx = gs.getGraphDb().beginTx()) {
            // Sanitize the type name for safe use in Cypher
            String sanitizedType = type.replaceAll("[^a-zA-Z0-9_]", "_");
            Result result = tx.execute(
                    "MATCH ()-[r:" + sanitizedType + "]->() RETURN count(r) AS count");
            Object count = result.hasNext() ? result.next().get("count") : 0L;
            tx.commit();
            return (count instanceof Number) ? ((Number) count).longValue() : 0L;
        }
    }

    private GraphNodeJSON convertNode(Node node) {
        List<String> labels = new ArrayList<>();
        for (Label label : node.getLabels()) {
            labels.add(label.name());
        }

        Map<String, Object> props = new HashMap<>();
        String source = null;

        for (String key : node.getPropertyKeys()) {
            Object value = node.getProperty(key);
            props.put(key, value);

            // Try to extract source from dataSource property
            if (GraphTask.RELATIONSHIP_SOURCE.equals(key)) {
                source = String.valueOf(value);
            }
        }

        // If no source found in properties, try to get from Sources mapping
        if (source == null && Sources.sourceIntToString != null) {
            // Default to first source if not identifiable
            source = Sources.sourceIntToString.get(0);
        }

        return new GraphNodeJSON(node.getId(), labels, props, source);
    }

    private GraphEdgeJSON convertEdge(Relationship rel) {
        Map<String, Object> props = new HashMap<>();
        String source = null;

        for (String key : rel.getPropertyKeys()) {
            Object value = rel.getProperty(key);
            props.put(key, value);

            if (GraphTask.RELATIONSHIP_SOURCE.equals(key)) {
                source = String.valueOf(value);
            }
        }

        return new GraphEdgeJSON(
                rel.getId(),
                rel.getType().name(),
                rel.getStartNodeId(),
                rel.getEndNodeId(),
                props,
                source);
    }

    // Request/Response DTOs

    public static class PathRequest {
        public Long source;
        public Long target;
        public int maxDistance = 5;
    }

    public static class CypherRequest {
        public String query;
        public Map<String, Object> parameters;
        public int maxResults = 100;
    }
}
