package iped.engine.webapi.json;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * JSON representation of a graph relationship type with its count.
 */
@ApiModel(value = "GraphRelationshipType")
public class GraphRelationshipTypeJSON {
    private String name;
    private long count;

    public GraphRelationshipTypeJSON() {
    }

    public GraphRelationshipTypeJSON(String name, long count) {
        this.name = name;
        this.count = count;
    }

    @ApiModelProperty(value = "Relationship type name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @ApiModelProperty(value = "Number of edges with this type")
    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
