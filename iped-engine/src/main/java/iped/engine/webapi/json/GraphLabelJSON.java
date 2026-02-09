package iped.engine.webapi.json;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * JSON representation of a graph label with its count.
 */
@ApiModel(value = "GraphLabel")
public class GraphLabelJSON {
    private String name;
    private long count;

    public GraphLabelJSON() {
    }

    public GraphLabelJSON(String name, long count) {
        this.name = name;
        this.count = count;
    }

    @ApiModelProperty(value = "Label name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @ApiModelProperty(value = "Number of nodes with this label")
    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
