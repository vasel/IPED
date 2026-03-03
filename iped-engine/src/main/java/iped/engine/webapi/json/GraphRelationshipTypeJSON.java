package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON representation of a graph relationship type with its count.
 */
@Schema(name = "GraphRelationshipType")
public class GraphRelationshipTypeJSON {
    private String name;
    private long count;

    public GraphRelationshipTypeJSON() {
    }

    public GraphRelationshipTypeJSON(String name, long count) {
        this.name = name;
        this.count = count;
    }

    @Schema(description = "Relationship type name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "Number of edges with this type")
    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
